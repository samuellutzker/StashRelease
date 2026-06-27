/// Server-to-server mirror: this server connects to a peer as a WebSocket client,
/// syncs items bidirectionally on connect, then forwards live events in both directions.
///
/// Loop prevention: events received FROM the peer are stored directly into the DB and
/// broadcast to local clients — they do NOT go back through mirror_tx. Only events
/// originating from LOCAL client sessions go through mirror_tx, so A→B→A cycles can
/// never form regardless of how many servers mirror each other.
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::sync::{broadcast, mpsc};
use tokio_tungstenite::{connect_async, connect_async_tls_with_config};
use tracing::{error, info, warn};
use tungstenite::Message;

use crate::db::{Database, UpsertResult};
use crate::protocol::{ClientMsg, Item, ServerMsg};
use crate::session::ClientRegistry;

#[derive(Clone, Debug)]
pub enum MirrorEvent {
    ItemUpserted(Item),
    ItemDeleted(String),
    /// A file is now locally available on this server — upload it to the peer.
    FileAvailable(String),
}

pub type MirrorTx = broadcast::Sender<MirrorEvent>;

/// Create a broadcast channel for mirror events. Returns the sender; sessions clone it.
pub fn new_channel() -> MirrorTx {
    let (tx, _) = broadcast::channel(512);
    tx
}

/// Spawn the mirror task. It runs forever, reconnecting after disconnects.
/// `connected` is kept at 1 while the peer session is live, 0 otherwise (for the web status).
#[allow(clippy::too_many_arguments)]
pub fn spawn(
    host: String,
    password_hash: String,
    tls: bool,
    fingerprint: Option<String>,
    db: Arc<Database>,
    registry: Arc<ClientRegistry>,
    mirror_tx: MirrorTx,
    data_dir: Arc<String>,
    connected: std::sync::Arc<std::sync::atomic::AtomicUsize>,
) {
    tokio::spawn(async move {
        run_loop(
            host,
            password_hash,
            tls,
            fingerprint,
            db,
            registry,
            mirror_tx,
            data_dir,
            connected,
        )
        .await;
    });
}

#[allow(clippy::too_many_arguments)]
async fn run_loop(
    host: String,
    password_hash: String,
    tls: bool,
    fingerprint: Option<String>,
    db: Arc<Database>,
    registry: Arc<ClientRegistry>,
    mirror_tx: MirrorTx,
    data_dir: Arc<String>,
    connected: std::sync::Arc<std::sync::atomic::AtomicUsize>,
) {
    use std::sync::atomic::Ordering;
    let scheme = if tls { "wss" } else { "ws" };
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        let url = format!("{scheme}://{}", host);
        info!("[mirror] connecting to {url} (attempt {attempt})");

        // For wss:// build a pinning TLS connector each attempt (cheap); ws:// uses the plain path.
        let conn_result = if tls {
            match crate::tls::mirror_connector(fingerprint.clone()) {
                Ok(connector) => {
                    connect_async_tls_with_config(&url, None, false, Some(connector)).await
                }
                Err(e) => {
                    error!("[mirror] TLS connector error: {e}");
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    continue;
                }
            }
        } else {
            connect_async(&url).await
        };

        match conn_result {
            Ok((ws, _)) => {
                attempt = 0;
                info!("[mirror] connected to {}", host);
                connected.store(1, Ordering::Relaxed);
                let rx = mirror_tx.subscribe();
                run_session(
                    ws,
                    &password_hash,
                    Arc::clone(&db),
                    Arc::clone(&registry),
                    rx,
                    Arc::clone(&data_dir),
                )
                .await;
                connected.store(0, Ordering::Relaxed);
                warn!("[mirror] disconnected from {}, retrying…", host);
            }
            Err(e) => {
                let delay = (2u64.pow(attempt.min(6))).min(60);
                warn!("[mirror] connect failed: {e}, retry in {delay}s");
                tokio::time::sleep(Duration::from_secs(delay)).await;
                continue;
            }
        }

        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn run_session(
    ws: WsStream,
    password_hash: &str,
    db: Arc<Database>,
    registry: Arc<ClientRegistry>,
    mut mirror_rx: broadcast::Receiver<MirrorEvent>,
    data_dir: Arc<String>,
) {
    let (mut ws_tx, mut ws_rx) = ws.split();

    // ── Auth ────────────────────────────────────────────────────────────────────

    // 1. Receive challenge (nonce not used since auth sends SHA-256 directly).
    let _challenge_ok = loop {
        match ws_rx.next().await {
            Some(Ok(Message::Text(t))) => {
                if let Ok(ServerMsg::Challenge { .. }) = serde_json::from_str(&t) {
                    break true;
                }
            }
            _ => {
                warn!("[mirror] no challenge received");
                return;
            }
        }
    };

    // 2. Send auth — the mirror's password_hash field is already the SHA-256 hex of the peer's password.
    let Ok(msg) = serde_json::to_string(&ClientMsg::Auth {
        hash: password_hash.to_string(),
        known_ids: vec![],
    }) else {
        error!("[mirror] serialize error sending auth");
        return;
    };
    if ws_tx.send(Message::Text(msg.into())).await.is_err() {
        return;
    }

    // 3. Await AuthOk then SyncMeta
    let mut remote_items: Vec<Item> = vec![];
    let mut auth_ok = false;
    loop {
        match ws_rx.next().await {
            Some(Ok(Message::Text(t))) => match serde_json::from_str::<ServerMsg>(&t) {
                Ok(ServerMsg::AuthOk) => {
                    auth_ok = true;
                }
                Ok(ServerMsg::AuthFail) => {
                    error!("[mirror] auth rejected by peer — check mirror password_hash");
                    return;
                }
                Ok(ServerMsg::SyncMeta { items }) => {
                    remote_items = items;
                    break;
                }
                _ => {
                    if !auth_ok {
                        warn!("[mirror] unexpected message before auth");
                        return;
                    }
                }
            },
            _ => return,
        }
    }

    info!("[mirror] received {} items from peer", remote_items.len());

    // ── Bidirectional initial sync ───────────────────────────────────────────────

    // Merge remote items into local DB; broadcast newly arrived ones to local clients.
    let mut to_download: Vec<String> = vec![];
    // Remote items that we've tombstoned locally: tell the peer to delete them too, so the
    // deletion propagates instead of the peer endlessly re-advertising the item.
    let mut deletes_for_peer: Vec<String> = vec![];
    for item in &remote_items {
        let was_tombstoned = db.is_tombstoned(&item.id).unwrap_or(false);
        match db.upsert_item(item) {
            Ok(UpsertResult::Inserted) | Ok(UpsertResult::Updated) => {
                registry.broadcast_all(&ServerMsg::ItemAdded { item: item.clone() });
            }
            Ok(UpsertResult::Unchanged) => {
                // If the upsert was blocked because we hold a (still-winning) tombstone,
                // push the deletion back to the peer.
                if was_tombstoned && db.is_tombstoned(&item.id).unwrap_or(false) {
                    deletes_for_peer.push(item.id.clone());
                }
            }
            Err(e) => warn!("[mirror] upsert error: {e}"),
        }
        // Queue file download if remote has it and we don't.
        if item.has_file {
            let path = PathBuf::from(data_dir.as_str())
                .join("files")
                .join(&item.id);
            if !path.exists() {
                to_download.push(item.id.clone());
            }
        }
    }

    // Send our full SyncMeta so the remote can import anything we have that it doesn't.
    let local_items = db.list_items().unwrap_or_default();
    let _remote_ids: std::collections::HashSet<&str> =
        remote_items.iter().map(|i| i.id.as_str()).collect();

    // Build list of files to upload: items the remote doesn't have or has without the file.
    let remote_file_ids: std::collections::HashSet<&str> = remote_items
        .iter()
        .filter(|i| i.has_file)
        .map(|i| i.id.as_str())
        .collect();

    let to_upload: Vec<String> = local_items
        .iter()
        .filter(|i| i.has_file && !remote_file_ids.contains(i.id.as_str()))
        .map(|i| i.id.clone())
        .collect();

    // Advertise has_file=false: the peer must only mark a file present once it has actually
    // received and verified it (which happens via the uploads below). Otherwise a peer client
    // could request a file the peer doesn't yet hold and hit "file not found".
    let meta_items: Vec<Item> = local_items
        .into_iter()
        .map(|mut i| {
            i.has_file = false;
            i
        })
        .collect();
    let Ok(sync_msg) = serde_json::to_string(&ClientMsg::SyncMeta { items: meta_items }) else {
        error!("[mirror] serialize error sending SyncMeta");
        return;
    };
    if ws_tx.send(Message::Text(sync_msg.into())).await.is_err() {
        return;
    }

    // Propagate our tombstones for items the peer still advertises, so the deletion wins on
    // both sides instead of the peer re-syncing the item back to us forever.
    for id in deletes_for_peer {
        if let Ok(del_msg) = serde_json::to_string(&ClientMsg::DeleteItem { id }) {
            if ws_tx.send(Message::Text(del_msg.into())).await.is_err() {
                return;
            }
        }
    }

    // ── Main event loop — use a write channel so uploads run concurrently ────────

    let (write_tx, mut write_rx) = mpsc::unbounded_channel::<Message>();

    // Forward write_rx → ws_tx in a background task.
    let write_task = tokio::spawn(async move {
        while let Some(msg) = write_rx.recv().await {
            if ws_tx.send(msg).await.is_err() {
                break;
            }
        }
    });

    // Files we've asked the peer for and not yet finished receiving — dedups repeated requests
    // (a second request would make the peer open a concurrent stream).
    let mut requested: std::collections::HashSet<String> = Default::default();

    // Request files the remote has but we don't.
    for id in to_download {
        requested.insert(id.clone());
        let Ok(req) = serde_json::to_string(&ClientMsg::RequestFile { id, from_chunk: 0 }) else {
            error!("[mirror] serialize error building RequestFile");
            continue;
        };
        let _ = write_tx.send(Message::Text(req.into()));
    }

    // Upload files the remote doesn't have yet.
    for id in to_upload {
        let path = PathBuf::from(data_dir.as_str()).join("files").join(&id);
        let wtx = write_tx.clone();
        tokio::spawn(async move {
            upload_file(&path, &id, 0, wtx).await;
        });
    }

    // Track incoming file chunks from remote: syncId → (received, total)
    let mut incoming: std::collections::HashMap<String, (u32, u32)> = Default::default();

    // syncIds for which we've sent a QueryUploadState and are awaiting the UploadState reply
    // (so we can resume the upload from the chunk the peer already has).
    let mut upload_state_waiters: std::collections::HashSet<String> = Default::default();

    loop {
        tokio::select! {
            event = mirror_rx.recv() => {
                match event {
                    Ok(MirrorEvent::ItemUpserted(item)) => {
                        info!("[mirror] → peer push_item {}", item.id);
                        let Ok(msg) = serde_json::to_string(&ClientMsg::PushItem { item }) else {
                            error!("[mirror] serialize error");
                            continue;
                        };
                        let _ = write_tx.send(Message::Text(msg.into()));
                    }
                    Ok(MirrorEvent::ItemDeleted(id)) => {
                        info!("[mirror] → peer delete_item {id}");
                        let Ok(msg) = serde_json::to_string(&ClientMsg::DeleteItem { id }) else {
                            error!("[mirror] serialize error");
                            continue;
                        };
                        let _ = write_tx.send(Message::Text(msg.into()));
                    }
                    Ok(MirrorEvent::FileAvailable(id)) => {
                        // Ask the peer how many chunks it already has; the UploadState reply
                        // (handled in the ws_rx branch) resumes the upload from there.
                        let path = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                        let total = tokio::fs::metadata(&path).await
                            .map(|m| m.len().div_ceil(crate::constants::CHUNK_SIZE).max(1) as u32)
                            .unwrap_or(0);
                        upload_state_waiters.insert(id.clone());
                        let Ok(qry) = serde_json::to_string(&ClientMsg::QueryUploadState { id, total }) else {
                            error!("[mirror] serialize error");
                            continue;
                        };
                        let _ = write_tx.send(Message::Text(qry.into()));
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("[mirror] {n} events dropped (lagged)");
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }

            msg = ws_rx.next() => {
                let Some(Ok(msg)) = msg else { break };
                match msg {
                    Message::Text(t) => {
                        match serde_json::from_str::<ServerMsg>(&t) {
                            Ok(ServerMsg::ItemAdded { item }) => {
                                info!("[mirror] ← peer item_added {} (has_file={})", item.id, item.has_file);
                                let upsert = db.upsert_item(&item);
                                // Pull the file whenever the peer has it and we don't — INDEPENDENT of
                                // whether the metadata changed. A has_file=true broadcast (sent after
                                // the peer's client finishes uploading) usually carries the same
                                // updated_at as the earlier metadata-only insert, so it upserts as
                                // Unchanged. Gating the file request on Inserted/Updated then skipped
                                // the pull, and the file never arrived until the next reconnect.
                                if item.has_file && !requested.contains(&item.id)
                                    && !incoming.contains_key(&item.id)
                                {
                                    let path = PathBuf::from(data_dir.as_str())
                                        .join("files").join(&item.id);
                                    if !path.exists() {
                                        requested.insert(item.id.clone());
                                        if let Ok(req) = serde_json::to_string(&ClientMsg::RequestFile {
                                            id: item.id.clone(), from_chunk: 0,
                                        }) {
                                            let _ = write_tx.send(Message::Text(req.into()));
                                        } else {
                                            error!("[mirror] serialize error building RequestFile");
                                        }
                                    }
                                }
                                match upsert {
                                    Ok(UpsertResult::Inserted) | Ok(UpsertResult::Updated) => {
                                        registry.broadcast_all(&ServerMsg::ItemAdded { item });
                                    }
                                    // Metadata already current — drop (breaks the forwarding loop).
                                    Ok(UpsertResult::Unchanged) => {}
                                    Err(e) => warn!("[mirror] upsert: {e}"),
                                }
                            }
                            Ok(ServerMsg::ItemDeleted { id }) => {
                                let _ = db.delete_item(&id);
                                let path = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                                let _ = tokio::fs::remove_file(&path).await;
                                requested.remove(&id);
                                incoming.remove(&id);
                                registry.broadcast_all(&ServerMsg::ItemDeleted { id });
                            }
                            Ok(ServerMsg::FileChunk { id, chunk, index, total }) => {
                                if let Ok(data) = base64::engine::general_purpose::STANDARD.decode(&chunk) {
                                    let part = PathBuf::from(data_dir.as_str())
                                        .join("uploads").join(format!("{id}.mirror.part"));
                                    // Create uploads dir if needed.
                                    let _ = tokio::fs::create_dir_all(
                                        PathBuf::from(data_dir.as_str()).join("uploads")
                                    ).await;
                                    if let Ok(mut f) = tokio::fs::OpenOptions::new()
                                        .create(true).write(true).truncate(false).open(&part).await
                                    {
                                        let _ = f.seek(std::io::SeekFrom::Start(
                                            index as u64 * crate::constants::CHUNK_SIZE,
                                        )).await;
                                        let _ = f.write_all(&data).await;
                                    }
                                    incoming.insert(id, (index + 1, total));
                                }
                            }
                            Ok(ServerMsg::FileTransferComplete { id }) => {
                                incoming.remove(&id);
                                requested.remove(&id);
                                let part = PathBuf::from(data_dir.as_str())
                                    .join("uploads").join(format!("{id}.mirror.part"));
                                // No .part file means this acks one of OUR outgoing uploads, not
                                // a completed download — exactly the same split as Android's
                                // handleFileTransferComplete. Skip; the peer holds the file now.
                                if !tokio::fs::try_exists(&part).await.unwrap_or(false) {
                                    info!("[mirror] peer confirmed upload of {id}");
                                    continue;
                                }
                                let dest = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                                match tokio::fs::rename(&part, &dest).await {
                                    Ok(_) => {
                                        // Verify the mirrored file before trusting it, same as a
                                        // direct client upload.
                                        let expected = db.get_item(&id).ok().flatten()
                                            .map(|i| i.file_hash).unwrap_or_default();
                                        if !expected.is_empty() {
                                            let actual = crate::hash::file_sha256(&dest).await.unwrap_or_default();
                                            if actual != expected {
                                                warn!("[mirror] hash mismatch for {id} — discarding");
                                                let _ = tokio::fs::remove_file(&dest).await;
                                                continue;
                                            }
                                        }
                                        let _ = db.set_has_file(&id, true);
                                        if let Ok(Some(item)) = db.get_item(&id) {
                                            registry.broadcast_all(&ServerMsg::ItemAdded { item });
                                        }
                                        info!("[mirror] received + verified file {id}");
                                    }
                                    Err(e) => error!("[mirror] finalize {id}: {e}"),
                                }
                            }
                            Ok(ServerMsg::UploadState { id, received }) if upload_state_waiters.remove(&id) => {
                                let path = PathBuf::from(data_dir.as_str()).join("files").join(&id);
                                let wtx = write_tx.clone();
                                tokio::spawn(async move {
                                    upload_file(&path, &id, received, wtx).await;
                                });
                            }
                            Ok(ServerMsg::FileUnavailable { id }) => {
                                // Peer doesn't have this file yet — drop the request so a later
                                // item_added(has_file=true) re-triggers the pull once it arrives.
                                requested.remove(&id);
                            }
                            Ok(ServerMsg::Error { message }) => {
                                warn!("[mirror] peer error: {message}");
                                requested.retain(|id| incoming.contains_key(id));
                            }
                            _ => {}
                        }
                    }
                    Message::Close(_) => break,
                    Message::Ping(data) => {
                        let _ = write_tx.send(Message::Pong(data));
                    }
                    _ => {}
                }
            }
        }
    }

    write_task.abort();
    info!("[mirror] session ended");
}

async fn upload_file(
    path: &PathBuf,
    id: &str,
    start_chunk: u32,
    write_tx: mpsc::UnboundedSender<Message>,
) {
    let chunk_size = crate::constants::CHUNK_SIZE as usize;
    let mut file = match tokio::fs::File::open(path).await {
        Ok(f) => f,
        Err(e) => {
            warn!("[mirror] upload: file {id} not found: {e}");
            return;
        }
    };
    let file_len = file.metadata().await.map(|m| m.len()).unwrap_or(0);
    let total = file_len.div_ceil(crate::constants::CHUNK_SIZE).max(1) as u32;

    if start_chunk >= total {
        info!("[mirror] remote already has {id}");
        return;
    }

    if start_chunk > 0 {
        let _ = file
            .seek(std::io::SeekFrom::Start(
                start_chunk as u64 * crate::constants::CHUNK_SIZE,
            ))
            .await;
    }

    let mut buf = vec![0u8; chunk_size];
    let mut idx = start_chunk;
    loop {
        let n = match file.read(&mut buf).await {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) => {
                error!("[mirror] read error {id}: {e}");
                break;
            }
        };
        let encoded = base64::engine::general_purpose::STANDARD.encode(&buf[..n]);
        let Ok(msg) = serde_json::to_string(&ClientMsg::PushFileChunk {
            id: id.to_string(),
            chunk: encoded,
            index: idx,
            total,
        }) else {
            error!("[mirror] serialize error building PushFileChunk");
            break;
        };
        if write_tx.send(Message::Text(msg.into())).is_err() {
            break;
        }
        idx += 1;
    }
    info!("[mirror] uploaded {id} to peer ({idx}/{total} chunks)");
}

// ── H6 + L1: backoff + serialisation tests ───────────────────────────────────
#[cfg(test)]
mod tests {
    /// Replicate the backoff formula from run_loop so it can be tested in isolation.
    /// Formula (post-fix): `(2u64.pow(attempt.min(6))).min(60)`
    ///
    /// H6 finding: the original formula `5u64.min(attempt as u64 * 2)` capped at 5s
    /// regardless of attempt count, providing almost no backoff after a few retries.
    /// The fixed formula gives exponential growth capped at 60s.
    fn backoff_secs(attempt: u32) -> u64 {
        (2u64.pow(attempt.min(6))).min(60)
    }

    #[test]
    fn backoff_increases_with_attempts() {
        let delays: Vec<u64> = (0..=6).map(backoff_secs).collect();
        for window in delays.windows(2) {
            assert!(
                window[1] >= window[0],
                "backoff must be non-decreasing: {:?}",
                delays
            );
        }
    }

    #[test]
    fn backoff_caps_at_60s() {
        for attempt in [6u32, 7, 10, 100] {
            let d = backoff_secs(attempt);
            assert!(
                d <= 60,
                "backoff at attempt={attempt} is {d}s — must be ≤60s"
            );
        }
    }

    #[test]
    fn backoff_never_zero() {
        // u64 is unsigned so it can't go below 0, but verify it is always ≥1.
        for attempt in [0u32, 1, 2, 3, 5, 10] {
            let d = backoff_secs(attempt);
            assert!(d >= 1, "backoff at attempt={attempt} must be ≥1, got {d}");
        }
    }

    #[test]
    fn backoff_known_values() {
        // Spot-check concrete values so the formula can't silently regress.
        assert_eq!(backoff_secs(0), 1); // 2^0 = 1
        assert_eq!(backoff_secs(1), 2); // 2^1 = 2
        assert_eq!(backoff_secs(2), 4); // 2^2 = 4
        assert_eq!(backoff_secs(3), 8); // 2^3 = 8
        assert_eq!(backoff_secs(5), 32); // 2^5 = 32
        assert_eq!(backoff_secs(6), 60); // 2^6 = 64 → capped at 60
        assert_eq!(backoff_secs(10), 60); // still capped
    }

    // ── L1: serde_json serialisation of every protocol message variant ────────
    //
    // The L1 fix replaces unwrap() with proper error handling in send_msg /
    // broadcast_except / broadcast_all.  This test verifies that every message
    // variant serialises without panicking (the pre-condition that made the
    // original unwrap safe is now documented here as a test).
    #[test]
    fn protocol_messages_serialize_without_panic() {
        use crate::protocol::{ClientMsg, Item, ServerMsg};

        fn dummy_item() -> Item {
            Item {
                id: "550e8400-e29b-41d4-a716-446655440000".to_string(),
                title: "t".to_string(),
                content: "c".to_string(),
                item_type: "text".to_string(),
                mime_type: "".to_string(),
                is_pinned: false,
                created_at: 1000,
                updated_at: 1000,
                has_file: false,
                file_hash: "".to_string(),
                thumbnail_b64: "".to_string(),
            }
        }

        let server_variants: Vec<ServerMsg> = vec![
            ServerMsg::Challenge {
                nonce: "n".to_string(),
            },
            ServerMsg::AuthOk,
            ServerMsg::AuthFail,
            ServerMsg::SyncMeta {
                items: vec![dummy_item()],
            },
            ServerMsg::ItemAdded { item: dummy_item() },
            ServerMsg::ItemDeleted {
                id: "x".to_string(),
            },
            ServerMsg::FileChunk {
                id: "x".to_string(),
                chunk: "abc".to_string(),
                index: 0,
                total: 1,
            },
            ServerMsg::FileTransferComplete {
                id: "x".to_string(),
            },
            ServerMsg::FileVerifyFailed {
                id: "x".to_string(),
            },
            ServerMsg::UploadState {
                id: "x".to_string(),
                received: 0,
            },
            ServerMsg::FileUnavailable {
                id: "x".to_string(),
            },
            ServerMsg::Error {
                message: "oops".to_string(),
            },
        ];
        for msg in &server_variants {
            assert!(
                serde_json::to_string(msg).is_ok(),
                "ServerMsg variant failed to serialize: {:?}",
                msg
            );
        }

        let client_variants: Vec<ClientMsg> = vec![
            ClientMsg::Auth {
                hash: "a".repeat(64),
                known_ids: vec![],
            },
            ClientMsg::SyncMeta {
                items: vec![dummy_item()],
            },
            ClientMsg::PushItem { item: dummy_item() },
            ClientMsg::DeleteItem {
                id: "x".to_string(),
            },
            ClientMsg::RequestFile {
                id: "x".to_string(),
                from_chunk: 0,
            },
            ClientMsg::PushFileChunk {
                id: "x".to_string(),
                chunk: "abc".to_string(),
                index: 0,
                total: 1,
            },
            ClientMsg::QueryUploadState {
                id: "x".to_string(),
                total: 1,
            },
        ];
        for msg in &client_variants {
            assert!(
                serde_json::to_string(msg).is_ok(),
                "ClientMsg variant failed to serialize: {:?}",
                msg
            );
        }
    }
}
