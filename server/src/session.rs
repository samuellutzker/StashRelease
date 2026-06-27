use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_tungstenite::WebSocketStream;
use tungstenite::Message;
use uuid::Uuid;

use tracing::{error, info, warn};

use crate::auth;
use crate::db::{Database, UpsertResult};
use crate::mirror::MirrorTx;
use crate::protocol::{ClientMsg, ServerMsg};
use crate::upload::UploadRegistry;

/// Reject any client-supplied id that is not a well-formed UUID to prevent path traversal.
pub fn is_valid_uuid(id: &str) -> bool {
    Uuid::parse_str(id).is_ok()
}

pub type Tx = mpsc::UnboundedSender<Message>;

pub struct ClientRegistry {
    clients: Mutex<HashMap<Uuid, Tx>>,
}

impl ClientRegistry {
    pub fn new() -> Self {
        Self {
            clients: Mutex::new(HashMap::new()),
        }
    }

    pub fn register(&self, id: Uuid, tx: Tx) {
        self.clients
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, tx);
    }

    pub fn unregister(&self, id: &Uuid) {
        self.clients
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(id);
    }

    /// Number of currently-connected client sessions (includes any incoming mirror peers).
    pub fn count(&self) -> usize {
        self.clients.lock().unwrap_or_else(|e| e.into_inner()).len()
    }

    pub fn broadcast_except(&self, exclude: &Uuid, msg: &ServerMsg) {
        let Ok(text) = serde_json::to_string(msg) else {
            error!("serialize error in broadcast_except");
            return;
        };
        let clients = self.clients.lock().unwrap_or_else(|e| e.into_inner());
        for (id, tx) in clients.iter() {
            if id != exclude {
                let _ = tx.send(Message::Text(text.clone().into()));
            }
        }
    }

    pub fn broadcast_all(&self, msg: &ServerMsg) {
        let Ok(text) = serde_json::to_string(msg) else {
            error!("serialize error in broadcast_all");
            return;
        };
        let clients = self.clients.lock().unwrap_or_else(|e| e.into_inner());
        for tx in clients.values() {
            let _ = tx.send(Message::Text(text.clone().into()));
        }
    }
}

fn send_msg(tx: &Tx, msg: &ServerMsg) {
    let Ok(text) = serde_json::to_string(msg) else {
        error!("serialize error in send_msg");
        return;
    };
    let _ = tx.send(Message::Text(text.into()));
}

#[allow(clippy::too_many_arguments)]
pub async fn run<S>(
    ws: WebSocketStream<S>,
    addr: SocketAddr,
    db: Arc<Database>,
    registry: Arc<ClientRegistry>,
    upload_registry: Arc<UploadRegistry>,
    password_hash: Arc<RwLock<String>>,
    data_dir: Arc<String>,
    max_file_size_mb: u64,
    mirror_tx: MirrorTx,
    rate_limiter: Arc<crate::ratelimit::RateLimiter>,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let max_chunks = (max_file_size_mb * 1024 * 1024 / crate::constants::CHUNK_SIZE).max(1) as u32;
    let (mut ws_tx, mut ws_rx) = ws.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();

    let write_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_tx.send(msg).await.is_err() {
                break;
            }
        }
    });

    // Send challenge (nonce retained for forward compatibility; not used in current HMAC-less auth).
    let nonce = Uuid::new_v4().to_string();
    send_msg(
        &tx,
        &ServerMsg::Challenge {
            nonce: nonce.clone(),
        },
    );

    let ip = addr.ip();

    // Check rate limit before doing any crypto work.
    if !rate_limiter.check(ip) {
        warn!("[{addr}] auth rate-limited");
        send_msg(&tx, &ServerMsg::AuthFail);
        let _ = write_task.await;
        return;
    }

    // Wait for auth (5-second timeout)
    let auth_result = timeout(Duration::from_secs(5), async {
        while let Some(Ok(msg)) = ws_rx.next().await {
            if let Message::Text(text) = msg {
                if let Ok(ClientMsg::Auth {
                    hash: auth_hash,
                    known_ids,
                }) = serde_json::from_str::<ClientMsg>(&text)
                {
                    let stored = password_hash
                        .read()
                        .unwrap_or_else(|e| e.into_inner())
                        .clone();
                    if auth::verify_hash(&stored, &auth_hash) {
                        return Some(known_ids);
                    } else {
                        return None;
                    }
                }
            }
        }
        None
    })
    .await
    .unwrap_or(None);

    let known_ids = match auth_result {
        Some(ids) => {
            rate_limiter.record_success(ip);
            ids
        }
        None => {
            rate_limiter.record_failure(ip);
            send_msg(&tx, &ServerMsg::AuthFail);
            let _ = write_task.await;
            return;
        }
    };

    send_msg(&tx, &ServerMsg::AuthOk);
    info!("[{addr}] authenticated");

    match db.list_items() {
        Ok(mut items) => {
            // Strip thumbnail_b64 for items the client already has to reduce reconnect bandwidth.
            let known_set: std::collections::HashSet<&str> =
                known_ids.iter().map(|s| s.as_str()).collect();
            for item in &mut items {
                if known_set.contains(item.id.as_str()) {
                    item.thumbnail_b64.clear();
                }
            }
            send_msg(&tx, &ServerMsg::SyncMeta { items });
        }
        Err(e) => error!("[{addr}] db error listing items: {e}"),
    }

    // Replay tombstones so the client drops any locally-held deleted items BEFORE it sends
    // its own SyncMeta back. Without this, an offline client that still holds a deleted item
    // would re-sync it and resurrect it for everyone.
    if let Ok(tombstones) = db.list_tombstones() {
        for (id, _) in tombstones {
            send_msg(&tx, &ServerMsg::ItemDeleted { id });
        }
    }

    let client_id = Uuid::new_v4();
    registry.register(client_id, tx.clone());

    'msg: while let Some(Ok(msg)) = ws_rx.next().await {
        match msg {
            Message::Text(text) => {
                let parsed: Result<ClientMsg, _> = serde_json::from_str(&text);
                match parsed {
                    Ok(ClientMsg::SyncMeta { items }) => {
                        // Bulk reconcile on (re)connect. Newly inserted or newer-edited items are
                        // propagated to other clients + the mirror so offline changes catch up.
                        let thumb_present: std::collections::HashMap<String, bool> = db
                            .list_items()
                            .unwrap_or_default()
                            .into_iter()
                            .map(|i| (i.id.clone(), !i.thumbnail_b64.is_empty()))
                            .collect();
                        for item in items {
                            if !is_valid_uuid(&item.id) {
                                warn!("[{addr}] sync_meta: skipping item with invalid id");
                                continue;
                            }
                            // Fill a missing thumbnail without disturbing metadata timestamps.
                            if !item.thumbnail_b64.is_empty()
                                && matches!(thumb_present.get(&item.id), Some(false))
                            {
                                let _ = db.update_thumbnail(&item.id, &item.thumbnail_b64);
                            }
                            match db.upsert_item(&item) {
                                Ok(UpsertResult::Inserted) | Ok(UpsertResult::Updated) => {
                                    let _ = mirror_tx.send(
                                        crate::mirror::MirrorEvent::ItemUpserted(item.clone()),
                                    );
                                    registry.broadcast_except(
                                        &client_id,
                                        &ServerMsg::ItemAdded { item },
                                    );
                                }
                                Ok(UpsertResult::Unchanged) => {}
                                Err(e) => warn!("[{addr}] sync_meta upsert error: {e}"),
                            }
                        }
                    }
                    Ok(ClientMsg::PushItem { item }) => {
                        if !is_valid_uuid(&item.id) {
                            send_msg(
                                &tx,
                                &ServerMsg::Error {
                                    message: "invalid id".to_string(),
                                },
                            );
                            continue 'msg;
                        }
                        match db.upsert_item(&item) {
                            Ok(r @ (UpsertResult::Inserted | UpsertResult::Updated)) => {
                                let verb = if r == UpsertResult::Inserted {
                                    "added"
                                } else {
                                    "updated"
                                };
                                info!(
                                    "[{addr}] item {verb}: {} type={} has_file={}",
                                    item.id, item.item_type, item.has_file
                                );
                                let _ = mirror_tx
                                    .send(crate::mirror::MirrorEvent::ItemUpserted(item.clone()));
                                registry
                                    .broadcast_except(&client_id, &ServerMsg::ItemAdded { item });
                            }
                            // Already current — drop silently (mirror loop prevention).
                            Ok(UpsertResult::Unchanged) => {
                                info!("[{addr}] item unchanged (dropped): {}", item.id);
                            }
                            Err(e) => warn!("[{addr}] upsert error: {e}"),
                        }
                    }
                    Ok(ClientMsg::DeleteItem { id }) => {
                        if !is_valid_uuid(&id) {
                            send_msg(
                                &tx,
                                &ServerMsg::Error {
                                    message: "invalid id".to_string(),
                                },
                            );
                            continue 'msg;
                        }
                        let _ = db.delete_item(&id);
                        let path = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                        let removed = tokio::fs::remove_file(&path).await.is_ok();
                        upload_registry.cleanup(&id);
                        info!("[{addr}] item deleted: {id} (file_removed={removed})");
                        let _ = mirror_tx.send(crate::mirror::MirrorEvent::ItemDeleted(id.clone()));
                        registry.broadcast_except(&client_id, &ServerMsg::ItemDeleted { id });
                    }
                    Ok(ClientMsg::QueryUploadState { id, total }) => {
                        if !is_valid_uuid(&id) {
                            continue 'msg;
                        }
                        // If the file is already stored (has_file), report it complete so the client
                        // doesn't re-stream it. The upload registry's resume state is cleaned up on
                        // finalize, so without this it would return 0 and the whole file would be
                        // re-uploaded on the next reconnect/sync.
                        let already_have = matches!(db.get_item(&id), Ok(Some(it)) if it.has_file);
                        let received = if already_have {
                            info!("[{addr}] upload query for {id}: already stored, reporting complete");
                            total
                        } else {
                            let reg = Arc::clone(&upload_registry);
                            let id2 = id.clone();
                            let r =
                                tokio::task::spawn_blocking(move || reg.query_state(&id2, total))
                                    .await
                                    .unwrap_or(0);
                            info!("[{addr}] upload begins for {id}: resuming from {r}/{total}");
                            r
                        };
                        send_msg(&tx, &ServerMsg::UploadState { id, received });
                    }
                    Ok(ClientMsg::RequestFile { id, from_chunk }) => {
                        if !is_valid_uuid(&id) {
                            send_msg(
                                &tx,
                                &ServerMsg::Error {
                                    message: "invalid id".to_string(),
                                },
                            );
                            continue 'msg;
                        }
                        let tx2 = tx.clone();
                        let data_dir2 = Arc::clone(&data_dir);
                        let db2 = Arc::clone(&db);
                        tokio::spawn(async move {
                            let path = PathBuf::from(data_dir2.as_str()).join("files").join(&id);
                            match tokio::fs::File::open(&path).await {
                                Ok(mut file) => {
                                    let chunk_size: u64 = crate::constants::CHUNK_SIZE;
                                    let file_len =
                                        file.metadata().await.map(|m| m.len()).unwrap_or(0);
                                    let total = file_len.div_ceil(chunk_size).max(1) as u32;
                                    let start = from_chunk.min(total);
                                    info!("[{addr}] serving file {id}: {file_len} bytes, from chunk {start}/{total}");

                                    if start > 0 {
                                        let _ = file
                                            .seek(std::io::SeekFrom::Start(
                                                start as u64 * chunk_size,
                                            ))
                                            .await;
                                    }

                                    let mut buf = vec![0u8; chunk_size as usize];
                                    let mut chunk_idx = start;
                                    loop {
                                        match file.read(&mut buf).await {
                                            Ok(0) => break,
                                            Ok(n) => {
                                                let encoded = base64::Engine::encode(
                                                    &base64::engine::general_purpose::STANDARD,
                                                    &buf[..n],
                                                );
                                                send_msg(
                                                    &tx2,
                                                    &ServerMsg::FileChunk {
                                                        id: id.clone(),
                                                        chunk: encoded,
                                                        index: chunk_idx,
                                                        total,
                                                    },
                                                );
                                                tokio::task::yield_now().await;
                                                chunk_idx += 1;
                                            }
                                            Err(e) => {
                                                error!("read error for {id}: {e}");
                                                break;
                                            }
                                        }
                                    }
                                    send_msg(&tx2, &ServerMsg::FileTransferComplete { id });
                                }
                                Err(e) => {
                                    // Distinguish a genuinely-deleted item from one that's just
                                    // not-yet-available (e.g. mirror hasn't pulled the file yet).
                                    // If the item is gone from the DB entirely, tell the client to
                                    // drop its orphaned cloud-only entry instead of retrying forever.
                                    match db2.get_item(&id) {
                                        Ok(None) => {
                                            warn!("[{addr}] file requested for unknown item {id} — telling client to delete");
                                            send_msg(&tx2, &ServerMsg::ItemDeleted { id });
                                        }
                                        _ => {
                                            warn!("[{addr}] file requested but not present yet: {id} ({e})");
                                            // Keyed + transient: the client retries just this id.
                                            send_msg(&tx2, &ServerMsg::FileUnavailable { id });
                                        }
                                    }
                                }
                            }
                        });
                    }
                    Ok(ClientMsg::PushFileChunk {
                        id,
                        chunk,
                        index,
                        total,
                    }) => {
                        if !is_valid_uuid(&id) {
                            send_msg(
                                &tx,
                                &ServerMsg::Error {
                                    message: "invalid id".to_string(),
                                },
                            );
                            continue 'msg;
                        }
                        if total > max_chunks {
                            send_msg(
                                &tx,
                                &ServerMsg::Error {
                                    message: format!("file too large (max {}MB)", max_file_size_mb),
                                },
                            );
                            continue 'msg;
                        }
                        let data = match base64::Engine::decode(
                            &base64::engine::general_purpose::STANDARD,
                            &chunk,
                        ) {
                            Ok(d) => d,
                            Err(_) => {
                                warn!("[{addr}] bad base64 in chunk {index}/{total} for {id}");
                                send_msg(
                                    &tx,
                                    &ServerMsg::Error {
                                        message: format!("bad base64 in chunk {index}"),
                                    },
                                );
                                continue 'msg;
                            }
                        };

                        let reg = Arc::clone(&upload_registry);
                        let id2 = id.clone();
                        let maybe_part = tokio::task::spawn_blocking(move || {
                            reg.receive_chunk(&id2, data, index, total)
                        })
                        .await
                        .unwrap_or(None);

                        if let Some(part_path) = maybe_part {
                            let dest = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                            if let Err(e) = tokio::fs::rename(&part_path, &dest).await {
                                error!("[{addr}] file finalize error: {e}");
                                upload_registry.cleanup(&id);
                                continue 'msg;
                            }
                            upload_registry.cleanup_meta(&id);

                            // Integrity check: the file is only accepted if its SHA-256 matches the
                            // hash recorded with the item. A truncated/corrupt upload is rejected here
                            // instead of silently surfacing as a failed download later.
                            let expected = db
                                .get_item(&id)
                                .ok()
                                .flatten()
                                .map(|i| i.file_hash)
                                .unwrap_or_default();
                            if !expected.is_empty() {
                                let actual =
                                    crate::hash::file_sha256(&dest).await.unwrap_or_default();
                                if actual != expected {
                                    warn!("[{addr}] hash mismatch for {id}: expected {expected}, got {actual} — rejecting");
                                    let _ = tokio::fs::remove_file(&dest).await;
                                    upload_registry.cleanup(&id);
                                    send_msg(&tx, &ServerMsg::FileVerifyFailed { id: id.clone() });
                                    continue 'msg;
                                }
                            }

                            let _ = db.set_has_file(&id, true);
                            let _ = mirror_tx
                                .send(crate::mirror::MirrorEvent::FileAvailable(id.clone()));
                            if let Ok(Some(item)) = db.get_item(&id) {
                                registry
                                    .broadcast_except(&client_id, &ServerMsg::ItemAdded { item });
                            }
                            // Confirm verified upload completion to the uploader.
                            send_msg(&tx, &ServerMsg::FileTransferComplete { id: id.clone() });
                            info!("[{addr}] stored + verified file {id} → {}", dest.display());
                        }
                    }
                    Ok(ClientMsg::Auth { .. }) => {}
                    Err(e) => warn!("[{addr}] parse error: {e}: {text}"),
                }
            }
            Message::Close(_) => break,
            Message::Ping(data) => {
                let _ = tx.send(Message::Pong(data));
            }
            _ => {}
        }
    }

    registry.unregister(&client_id);
    drop(tx);
    let _ = write_task.await;
}

// ── H2: UUID validation tests ─────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;

    /// H2 — is_valid_uuid must accept proper UUIDs and reject path-traversal / arbitrary strings.
    /// The SyncMeta + PushItem + DeleteItem handlers all gate on this function before touching
    /// the DB or filesystem, so rejecting "../evil" prevents path traversal of the files/ dir.
    #[test]
    fn valid_uuid_accepted() {
        assert!(is_valid_uuid("550e8400-e29b-41d4-a716-446655440000"));
        // Uppercase also valid per RFC 4122
        assert!(is_valid_uuid("550E8400-E29B-41D4-A716-446655440000"));
    }

    #[test]
    fn path_traversal_id_rejected() {
        // Bug scenario (H2): a client-supplied id like "../evil" would escape the files/ dir
        // when constructing PathBuf::from(data_dir).join("files").join(id).
        // is_valid_uuid must return false for any non-UUID string.
        assert!(!is_valid_uuid("../evil"));
        assert!(!is_valid_uuid("../../etc/passwd"));
        assert!(!is_valid_uuid(""));
        assert!(!is_valid_uuid("not-a-uuid-at-all"));
        assert!(!is_valid_uuid("550e8400-e29b-41d4-a716")); // truncated
    }

    #[test]
    fn sync_meta_with_non_uuid_id_not_stored() {
        // Simulate the SyncMeta handler path: upsert_item is ONLY called after is_valid_uuid
        // passes (in PushItem/DeleteItem; SyncMeta items skip individual UUID checks but the
        // SyncMeta path trusts client IDs to be UUIDs for file ops). The critical guard is on
        // PushItem and DeleteItem. Verify that a DB upsert of a non-UUID id does NOT cause a
        // path traversal: the UUID check must happen before any filesystem access.
        // This test verifies the guard function itself is sufficient.
        let evil_ids = ["../evil", "../../etc/passwd", "/abs/path", ""];
        for id in &evil_ids {
            assert!(
                !is_valid_uuid(id),
                "is_valid_uuid({id:?}) must return false to block path traversal"
            );
        }
    }

    // On reconnect the server sends SyncMeta, THEN an ItemDeleted for every tombstone, so a
    // reconnecting client purges deleted items before re-syncing. This exercises the DB logic
    // the session relies on (no live WebSocket needed).
    #[test]
    fn test_sync_meta_includes_tombstones_for_reconnect() {
        use crate::db::{Database, UpsertResult};
        use crate::protocol::Item;

        let db = Database::open(":memory:").unwrap();
        let x = Item {
            id: "X".to_string(),
            title: "to-delete".to_string(),
            content: "c".to_string(),
            item_type: "text".to_string(),
            mime_type: String::new(),
            is_pinned: false,
            created_at: 10,
            updated_at: 10,
            has_file: false,
            file_hash: String::new(),
            thumbnail_b64: String::new(),
        };
        db.upsert_item(&x).unwrap();
        db.delete_item("X").unwrap();

        // Server reconnect path: tombstone is listed so an ItemDeleted gets replayed.
        let tombstoned: Vec<String> = db
            .list_tombstones()
            .unwrap()
            .into_iter()
            .map(|(id, _)| id)
            .collect();
        assert!(
            tombstoned.contains(&"X".to_string()),
            "reconnecting client must be told X was deleted"
        );

        // And a stale re-sync of X (same updated_at) must be dropped, not resurrected.
        assert_eq!(db.upsert_item(&x).unwrap(), UpsertResult::Unchanged);
        assert!(db.get_item("X").unwrap().is_none());
    }
}
