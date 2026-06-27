// Local protocol test client for reproducing/verifying sync behavior without an Android device.
//
//   cargo run --release --example probe -- listen <ws_url> <password> <seconds>
//   cargo run --release --example probe -- push   <ws_url> <password> <type> <content>
//
// `listen` connects, authenticates, then prints every server message (and counts item_added
// per id so duplicates are obvious). `push` sends a single push_item then exits.

use std::collections::HashMap;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use tokio_tungstenite::connect_async;
use tungstenite::Message;

type HmacSha256 = Hmac<Sha256>;

fn password_hash(pw: &str) -> String {
    let mut h = Sha256::new();
    h.update(pw.as_bytes());
    hex::encode(h.finalize())
}

fn hmac_response(pw_hash_hex: &str, nonce: &str) -> String {
    let key = hex::decode(pw_hash_hex).unwrap();
    let mut mac = HmacSha256::new_from_slice(&key).unwrap();
    mac.update(nonce.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("");
    let url = args.get(2).cloned().unwrap_or_default();
    let pw = args.get(3).cloned().unwrap_or_default();
    let pw_hash = password_hash(&pw);

    let (ws, _) = connect_async(&url).await.expect("connect");
    let (mut tx, mut rx) = ws.split();

    // Handshake: challenge → auth → auth_ok.
    loop {
        match rx.next().await {
            Some(Ok(Message::Text(t))) => {
                let v: serde_json::Value = serde_json::from_str(&t).unwrap_or_default();
                match v["type"].as_str() {
                    Some("challenge") => {
                        let nonce = v["nonce"].as_str().unwrap_or("");
                        let resp = hmac_response(&pw_hash, nonce);
                        let auth =
                            serde_json::json!({"type":"auth","response":resp,"known_ids":[]});
                        tx.send(Message::Text(auth.to_string().into()))
                            .await
                            .unwrap();
                    }
                    Some("auth_ok") => {
                        eprintln!("[probe] auth ok");
                        break;
                    }
                    Some("auth_fail") => {
                        eprintln!("[probe] AUTH FAILED");
                        return;
                    }
                    _ => {}
                }
            }
            _ => {
                eprintln!("[probe] connection closed during auth");
                return;
            }
        }
    }

    match mode {
        "push" => {
            let item_type = args.get(4).cloned().unwrap_or_else(|| "link".into());
            let content = args
                .get(5)
                .cloned()
                .unwrap_or_else(|| "https://example.com".into());
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as i64;
            // Must be a valid UUID — the server rejects non-UUID ids (path-traversal guard).
            let id = uuid::Uuid::new_v4().to_string();
            let item = serde_json::json!({
                "id": id, "title": content, "content": content, "type": item_type,
                "mime_type": "", "is_pinned": false, "created_at": now, "updated_at": now,
                "has_file": false, "file_hash": "", "thumbnail_b64": ""
            });
            // Mirror the real client: send sync_meta (empty) then push_item.
            tx.send(Message::Text(
                serde_json::json!({"type":"sync_meta","items":[]})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
            let push = serde_json::json!({"type":"push_item","item":item});
            tx.send(Message::Text(push.to_string().into()))
                .await
                .unwrap();
            eprintln!("[probe] pushed {item_type} id={id}");
            tokio::time::sleep(Duration::from_millis(1500)).await;
        }
        "upload" => {
            // Push file metadata (has_file=false), pause so it propagates, THEN upload the file —
            // reproducing the real two-step that makes the has_file=true broadcast upsert as
            // Unchanged on a mirror. Prints the new id to stdout.
            let path = args.get(4).cloned().unwrap_or_default();
            let data = std::fs::read(&path).expect("read file");
            let mut hh = Sha256::new();
            hh.update(&data);
            let file_hash = hex::encode(hh.finalize());
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as i64;
            let id = uuid::Uuid::new_v4().to_string();
            let fname = std::path::Path::new(&path)
                .file_name()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "file".into());
            let item = serde_json::json!({
                "id": id, "title": fname, "content": fname, "type": "file",
                "mime_type": "application/octet-stream", "is_pinned": false,
                "created_at": now, "updated_at": now,
                "has_file": false, "file_hash": file_hash, "thumbnail_b64": ""
            });
            tx.send(Message::Text(
                serde_json::json!({"type":"sync_meta","items":[]})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
            tx.send(Message::Text(
                serde_json::json!({"type":"push_item","item":item})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
            eprintln!(
                "[probe] pushed file metadata id={id} ({} bytes)",
                data.len()
            );
            tokio::time::sleep(Duration::from_secs(2)).await;

            const CHUNK: usize = 262_144;
            let total = ((data.len() + CHUNK - 1) / CHUNK).max(1);
            tx.send(Message::Text(
                serde_json::json!({"type":"query_upload_state","id":id,"total":total})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
            let mut start = 0usize;
            if let Ok(Some(Ok(Message::Text(t)))) =
                tokio::time::timeout(Duration::from_secs(5), rx.next()).await
            {
                let v: serde_json::Value = serde_json::from_str(&t).unwrap_or_default();
                if v["type"] == "upload_state" {
                    start = v["received"].as_u64().unwrap_or(0) as usize;
                }
            }
            for i in start..total {
                let from = i * CHUNK;
                let to = ((i + 1) * CHUNK).min(data.len());
                let b64 = base64::Engine::encode(
                    &base64::engine::general_purpose::STANDARD,
                    &data[from..to],
                );
                tx.send(Message::Text(serde_json::json!({"type":"push_file_chunk","id":id,"chunk":b64,"index":i,"total":total}).to_string().into())).await.unwrap();
            }
            eprintln!("[probe] uploaded {total} chunks for id={id}");
            tokio::time::sleep(Duration::from_secs(1)).await;
            println!("{id}");
        }
        "reqfile" => {
            let id = args.get(4).cloned().unwrap_or_default();
            let secs: u64 = args.get(5).and_then(|s| s.parse().ok()).unwrap_or(3);
            let req = serde_json::json!({"type":"request_file","id":id});
            tx.send(Message::Text(req.to_string().into()))
                .await
                .unwrap();
            eprintln!("[probe] requested file id={id}; listening {secs}s for response…");
            let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
            loop {
                let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
                if remaining.is_zero() {
                    break;
                }
                match tokio::time::timeout(remaining, rx.next()).await {
                    Ok(Some(Ok(Message::Text(t)))) => {
                        let v: serde_json::Value = serde_json::from_str(&t).unwrap_or_default();
                        match v["type"].as_str() {
                            Some("item_deleted") => eprintln!("[probe] ← item_deleted id={} (server says orphan — client should delete)", v["id"].as_str().unwrap_or("?")),
                            Some("error") => eprintln!("[probe] ← error: {} (transient — keep + retry)", v["message"].as_str().unwrap_or("")),
                            Some("file_chunk") => eprintln!("[probe] ← file_chunk index={}", v["index"]),
                            Some("file_transfer_complete") => eprintln!("[probe] ← file_transfer_complete"),
                            Some(other) => eprintln!("[probe] ← {other}"),
                            None => {}
                        }
                    }
                    Ok(Some(Ok(Message::Ping(_)))) | Ok(Some(_)) => {}
                    Ok(None) | Err(_) => break,
                }
            }
        }
        "listen" => {
            let secs: u64 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(10);
            eprintln!("[probe] listening {secs}s…");
            let mut counts: HashMap<String, u32> = HashMap::new();
            let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
            loop {
                let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
                if remaining.is_zero() {
                    break;
                }
                match tokio::time::timeout(remaining, rx.next()).await {
                    Ok(Some(Ok(Message::Text(t)))) => {
                        let v: serde_json::Value = serde_json::from_str(&t).unwrap_or_default();
                        match v["type"].as_str() {
                            Some("sync_meta") => {
                                let n = v["items"].as_array().map(|a| a.len()).unwrap_or(0);
                                eprintln!("[probe] ← sync_meta ({n} items)");
                            }
                            Some("item_added") => {
                                let id = v["item"]["id"].as_str().unwrap_or("?").to_string();
                                let c = counts.entry(id.clone()).or_insert(0);
                                *c += 1;
                                eprintln!("[probe] ← item_added id={id} (count={})", c);
                            }
                            Some("item_deleted") => {
                                eprintln!(
                                    "[probe] ← item_deleted id={}",
                                    v["id"].as_str().unwrap_or("?")
                                );
                            }
                            Some(other) => eprintln!("[probe] ← {other}"),
                            None => {}
                        }
                    }
                    Ok(Some(Ok(Message::Ping(_)))) => {}
                    Ok(Some(_)) => {}
                    Ok(None) | Err(_) => break,
                }
            }
            eprintln!("[probe] --- item_added counts ---");
            let mut dup = false;
            for (id, c) in &counts {
                eprintln!("[probe]   {id}: {c}");
                if *c > 1 {
                    dup = true;
                }
            }
            eprintln!("[probe] DUPLICATES: {}", if dup { "YES" } else { "no" });
        }
        _ => eprintln!("usage: probe <listen|push> <ws_url> <password> [..]"),
    }
}
