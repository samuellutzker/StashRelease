use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Multipart, Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{Html, IntoResponse, Response},
    routing::{delete, get, post},
    Form, Json, Router,
};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use tokio_util::io::ReaderStream;
use tracing::warn;

use crate::auth;
use crate::config::{hash_password, sha256_hex, Config};
use crate::db::Database;
use crate::protocol::{Item, ServerMsg};
use crate::ratelimit::RateLimiter;
use crate::session::is_valid_uuid;

type HmacSha256 = Hmac<Sha256>;

static HTML: &str = include_str!("../templates/index.html");

pub struct WebState {
    pub db: Arc<Database>,
    pub password_hash: Arc<RwLock<String>>,
    pub config_path: String,
    pub data_dir: Arc<String>,
    pub registry: Arc<crate::session::ClientRegistry>,
    pub mirror_tx: crate::mirror::MirrorTx,
    pub mirror_connected: Arc<std::sync::atomic::AtomicUsize>,
    pub mirror_configured: bool,
    /// SHA-256 fingerprint of the TLS cert, or None when serving plain ws://.
    pub tls_fingerprint: Option<String>,
    pub rate_limiter: Arc<RateLimiter>,
}

// ── Session token ─────────────────────────────────────────────────────────────

const COOKIE: &str = "stash_session";
const SESSION_TTL: u64 = 60 * 60 * 24 * 7; // 7 days

fn make_token(key: &str, expiry: u64) -> String {
    let mut mac = HmacSha256::new_from_slice(key.as_bytes()).unwrap();
    mac.update(expiry.to_string().as_bytes());
    format!("{}.{}", expiry, hex::encode(mac.finalize().into_bytes()))
}

fn verify_token(key: &str, token: &str) -> bool {
    let Some((expiry_str, sig)) = token.split_once('.') else {
        return false;
    };
    let Ok(expiry) = expiry_str.parse::<u64>() else {
        return false;
    };
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    if now > expiry {
        return false;
    }
    let Ok(sig_bytes) = hex::decode(sig) else {
        return false;
    };
    let mut mac = HmacSha256::new_from_slice(key.as_bytes()).unwrap();
    mac.update(expiry_str.as_bytes());
    mac.verify_slice(&sig_bytes).is_ok()
}

fn extract_cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    let cookie_str = headers.get(header::COOKIE)?.to_str().ok()?;
    for part in cookie_str.split(';') {
        let kv: Vec<&str> = part.trim().splitn(2, '=').collect();
        if kv.len() == 2 && kv[0].trim() == name {
            return Some(kv[1].to_string());
        }
    }
    None
}

fn is_authed(headers: &HeaderMap, password_hash: &RwLock<String>) -> bool {
    let hash = password_hash.read().unwrap_or_else(|e| e.into_inner());
    extract_cookie(headers, COOKIE).is_some_and(|tok| verify_token(&hash, &tok))
}

// ── Handlers ──────────────────────────────────────────────────────────────────

async fn get_index() -> Html<&'static str> {
    Html(HTML)
}

#[derive(Deserialize)]
pub struct LoginForm {
    password: String,
}

async fn post_login(
    State(state): State<Arc<WebState>>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> impl IntoResponse {
    // Extract IP for rate limiting: prefer X-Forwarded-For (reverse proxy), else 127.0.0.1.
    let ip: IpAddr = headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next())
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(IpAddr::V4(std::net::Ipv4Addr::LOCALHOST));

    if !state.rate_limiter.check(ip) {
        warn!("web login rate-limited for {ip}");
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({"ok": false})),
        )
            .into_response();
    }

    let sha256 = sha256_hex(&form.password);
    let stored = state
        .password_hash
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    if auth::verify_hash(&stored, &sha256) {
        state.rate_limiter.record_success(ip);
        let expiry = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
            + SESSION_TTL;
        let token = make_token(&stored, expiry);
        let cookie = format!(
            "{}={}; HttpOnly; Path=/; Max-Age={}; SameSite=Lax",
            COOKIE, token, SESSION_TTL
        );
        (
            StatusCode::OK,
            [(header::SET_COOKIE, cookie)],
            Json(serde_json::json!({"ok": true})),
        )
            .into_response()
    } else {
        state.rate_limiter.record_failure(ip);
        (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"ok": false})),
        )
            .into_response()
    }
}

// Thin wrapper so thumbnail_b64 (potentially large) is still included but only
// for items that actually have it — it's useful for the web preview.
#[derive(Serialize)]
struct WebItem<'a> {
    id: &'a str,
    title: &'a str,
    content: &'a str,
    #[serde(rename = "type")]
    item_type: &'a str,
    mime_type: &'a str,
    is_pinned: bool,
    created_at: i64,
    has_file: bool,
    thumbnail_b64: &'a str,
}

impl<'a> From<&'a Item> for WebItem<'a> {
    fn from(i: &'a Item) -> Self {
        Self {
            id: &i.id,
            title: &i.title,
            content: &i.content,
            item_type: &i.item_type,
            mime_type: &i.mime_type,
            is_pinned: i.is_pinned,
            created_at: i.created_at,
            has_file: i.has_file,
            thumbnail_b64: &i.thumbnail_b64,
        }
    }
}

async fn get_items(State(state): State<Arc<WebState>>, headers: HeaderMap) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    match state.db.list_items() {
        Ok(items) => {
            let web: Vec<WebItem> = items.iter().map(WebItem::from).collect();
            Json(web).into_response()
        }
        Err(e) => {
            warn!("web api db error: {e}");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

async fn get_file(
    State(state): State<Arc<WebState>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    if !is_valid_uuid(&id) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let path = PathBuf::from(state.data_dir.as_str())
        .join("files")
        .join(&id);
    match tokio::fs::File::open(&path).await {
        Ok(file) => {
            let (filename, mime) = match state.db.get_item(&id) {
                Ok(Some(ref item)) => {
                    let name = if !item.content.is_empty() {
                        item.content.clone()
                    } else if !item.title.is_empty() {
                        item.title.clone()
                    } else {
                        id.clone()
                    };
                    let safe_name: String = name
                        .chars()
                        .map(|c| {
                            if c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | ' ') {
                                c
                            } else {
                                '_'
                            }
                        })
                        .collect();
                    let mime = if item.mime_type.is_empty() {
                        "application/octet-stream".to_string()
                    } else {
                        item.mime_type.clone()
                    };
                    (safe_name, mime)
                }
                _ => (id.clone(), "application/octet-stream".to_string()),
            };
            let stream = ReaderStream::new(file);
            let body = Body::from_stream(stream);
            let disposition = format!("attachment; filename=\"{}\"", filename);
            Response::builder()
                .header(header::CONTENT_TYPE, mime)
                .header(header::CONTENT_DISPOSITION, disposition)
                .body(body)
                .unwrap()
                .into_response()
        }
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Classify an uploaded file into the same item-type taxonomy the Android client uses
/// (see ContentTypeDetector.kt). Prefers the MIME type; falls back to the filename
/// extension when the browser sends a generic/blank content-type.
fn classify_type(mime: &str, filename: &str) -> &'static str {
    let m = mime.to_ascii_lowercase();
    let by_mime = match m.as_str() {
        _ if m.starts_with("image/") => Some("image"),
        _ if m.starts_with("video/") => Some("video"),
        _ if m.starts_with("audio/") => Some("audio"),
        "application/pdf" => Some("document"),
        _ if m.contains("msword") || m.contains("officedocument") => Some("document"),
        "text/csv" | "application/rtf" => Some("document"),
        "text/plain" => Some("text"),
        "application/zip"
        | "application/x-rar-compressed"
        | "application/x-7z-compressed"
        | "application/x-tar"
        | "application/gzip"
        | "application/x-gzip" => Some("archive"),
        "application/vnd.android.package-archive" => Some("apk"),
        "text/vcard" | "text/x-vcard" => Some("contact"),
        "" | "application/octet-stream" => None,
        _ => None,
    };
    if let Some(t) = by_mime {
        return t;
    }
    // Fall back to the filename extension.
    let ext = filename
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    match ext.as_str() {
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "bmp" | "heic" | "svg" => "image",
        "mp4" | "mkv" | "webm" | "mov" | "avi" | "m4v" | "3gp" => "video",
        "mp3" | "wav" | "ogg" | "flac" | "aac" | "m4a" | "opus" => "audio",
        "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "csv" | "rtf" | "odt" => {
            "document"
        }
        "txt" | "md" | "log" => "text",
        "zip" | "rar" | "7z" | "tar" | "gz" | "tgz" => "archive",
        "apk" => "apk",
        "vcf" => "contact",
        _ => "other",
    }
}

/// Accept a file uploaded via the web UI's multipart form, store it under data/files/<uuid>,
/// classify it, persist an Item, and broadcast it to connected clients + the mirror — so it
/// behaves exactly like a file added from the Android app.
async fn post_upload(
    State(state): State<Arc<WebState>>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return (StatusCode::UNAUTHORIZED, "unauthorized").into_response();
    }

    let mut filename = String::new();
    let mut mime = String::new();
    let mut data: Vec<u8> = Vec::new();
    let mut got_file = false;

    loop {
        let field = match multipart.next_field().await {
            Ok(Some(f)) => f,
            Ok(None) => break,
            Err(e) => return (StatusCode::BAD_REQUEST, format!("bad upload: {e}")).into_response(),
        };
        if field.name() == Some("file") {
            filename = field.file_name().unwrap_or("upload").to_string();
            mime = field.content_type().unwrap_or("").to_string();
            data = match field.bytes().await {
                Ok(b) => b.to_vec(),
                Err(e) => {
                    return (StatusCode::BAD_REQUEST, format!("read error: {e}")).into_response()
                }
            };
            got_file = true;
        }
    }

    if !got_file || data.is_empty() {
        return (StatusCode::BAD_REQUEST, "no file provided").into_response();
    }

    // Sanitize the supplied filename to a bare basename so it can't influence the storage path.
    let safe_filename = std::path::Path::new(&filename)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("upload")
        .to_string();
    let mut item_type = classify_type(&mime, &safe_filename);
    let mime_type = if mime.is_empty() {
        "application/octet-stream".to_string()
    } else {
        mime
    };

    let id = uuid::Uuid::new_v4().to_string();
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    // A small .txt upload becomes an inline text item — no file on disk — to match how the Android
    // client stores notes (content = the text, title = first non-blank line). Larger text files are
    // treated as ordinary documents so the body isn't held wholesale in the DB/sync metadata.
    if item_type == "text" && data.len() <= TEXT_INLINE_MAX {
        if let Ok(text) = String::from_utf8(data.clone()) {
            let title = text
                .lines()
                .find(|l| !l.trim().is_empty())
                .map(|l| l.chars().take(60).collect::<String>())
                .unwrap_or_default();
            let item = Item {
                id: id.clone(),
                title,
                content: text,
                item_type: "text".to_string(),
                mime_type: "text/plain".to_string(),
                is_pinned: false,
                created_at: now,
                updated_at: now,
                has_file: false,
                file_hash: String::new(),
                thumbnail_b64: String::new(),
            };
            return match state.db.upsert_item(&item) {
                Ok(_) => {
                    let _ = state
                        .mirror_tx
                        .send(crate::mirror::MirrorEvent::ItemUpserted(item.clone()));
                    state.registry.broadcast_all(&ServerMsg::ItemAdded { item });
                    (
                        StatusCode::OK,
                        Json(serde_json::json!({"ok": true, "id": id})),
                    )
                        .into_response()
                }
                Err(e) => {
                    warn!("web upload: text db upsert failed: {e}");
                    (StatusCode::INTERNAL_SERVER_ERROR, "could not save item").into_response()
                }
            };
        }
        // Not valid UTF-8 — fall through and store it as a regular file.
    }

    // A text file that wasn't inlined (too large, or not valid UTF-8) is stored on disk as a
    // document rather than a text item — a "text" item is expected to carry its body in `content`.
    if item_type == "text" {
        item_type = "document";
    }

    let dest = PathBuf::from(state.data_dir.as_str())
        .join("files")
        .join(&id);
    let file_hash = crate::hash::bytes_sha256(&data);
    if let Err(e) = tokio::fs::write(&dest, &data).await {
        warn!("web upload: write failed: {e}");
        return (StatusCode::INTERNAL_SERVER_ERROR, "could not store file").into_response();
    }

    // Mirror the Android model: the filename lives in `content`, `title` is the (blank) user comment,
    // and the file is already present on this server (has_file = true).
    let item = Item {
        id: id.clone(),
        title: String::new(),
        content: safe_filename,
        item_type: item_type.to_string(),
        mime_type,
        is_pinned: false,
        created_at: now,
        updated_at: now,
        has_file: true,
        file_hash,
        thumbnail_b64: String::new(),
    };

    match state.db.upsert_item(&item) {
        Ok(_) => {
            let _ = state.db.set_has_file(&id, true);
            // Tell the mirror peer about the item with has_file=false: the actual bytes are pushed
            // separately by the FileAvailable event below. Advertising has_file=true here would make
            // the peer's QueryUploadState handler report the upload "already complete" (it keys off
            // the item's has_file flag), so the file would never be streamed. This matches the
            // Android flow, where the metadata is pushed before the file and has_file flips to true
            // only once the peer has verified the bytes. Local clients still get has_file=true.
            let mut meta = item.clone();
            meta.has_file = false;
            let _ = state
                .mirror_tx
                .send(crate::mirror::MirrorEvent::ItemUpserted(meta));
            let _ = state
                .mirror_tx
                .send(crate::mirror::MirrorEvent::FileAvailable(id.clone()));
            state.registry.broadcast_all(&ServerMsg::ItemAdded { item });
            (
                StatusCode::OK,
                Json(serde_json::json!({"ok": true, "id": id})),
            )
                .into_response()
        }
        Err(e) => {
            warn!("web upload: db upsert failed: {e}");
            let _ = tokio::fs::remove_file(&dest).await;
            (StatusCode::INTERNAL_SERVER_ERROR, "could not save item").into_response()
        }
    }
}

/// Max size for an uploaded text file to be stored inline as a text item (matches the spec's
/// 64 KB threshold). Larger text files are stored as documents.
const TEXT_INLINE_MAX: usize = 64 * 1024;

#[derive(Serialize)]
struct StatusResp {
    clients: usize,
    mirror_connected: usize,
    mirror_configured: bool,
    tls_enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    tls_fingerprint: Option<String>,
}

async fn get_status(State(state): State<Arc<WebState>>, headers: HeaderMap) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    Json(StatusResp {
        clients: state.registry.count(),
        mirror_connected: state
            .mirror_connected
            .load(std::sync::atomic::Ordering::Relaxed),
        mirror_configured: state.mirror_configured,
        tls_enabled: state.tls_fingerprint.is_some(),
        tls_fingerprint: state.tls_fingerprint.clone(),
    })
    .into_response()
}

#[derive(Serialize)]
struct SettingsResp {
    bind: String,
    web_bind: String,
    mirror_host: String,
    mirror_password_hash: String,
    tls_enabled: bool,
    mirror_tls: bool,
    mirror_fingerprint: String,
}

async fn get_settings(State(state): State<Arc<WebState>>, headers: HeaderMap) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let cfg = if std::path::Path::new(&state.config_path).exists() {
        Config::load(&state.config_path).unwrap_or_default()
    } else {
        Config::default()
    };
    Json(SettingsResp {
        bind: cfg.bind.unwrap_or_else(|| "0.0.0.0:9876".to_string()),
        web_bind: cfg.web_bind.unwrap_or_else(|| "0.0.0.0:9877".to_string()),
        mirror_host: cfg
            .mirror
            .as_ref()
            .map(|m| m.host.clone())
            .unwrap_or_default(),
        mirror_password_hash: cfg
            .mirror
            .as_ref()
            .map(|m| m.password_hash.clone())
            .unwrap_or_default(),
        tls_enabled: cfg.tls.as_ref().map(|t| t.enabled).unwrap_or(false),
        mirror_tls: cfg.mirror.as_ref().map(|m| m.tls).unwrap_or(false),
        mirror_fingerprint: cfg
            .mirror
            .as_ref()
            .and_then(|m| m.fingerprint.clone())
            .unwrap_or_default(),
    })
    .into_response()
}

#[derive(Deserialize)]
struct SettingsUpdate {
    bind: Option<String>,
    web_bind: Option<String>,
    new_password: Option<String>,
    mirror_host: Option<String>,
    mirror_password: Option<String>,
    mirror_password_hash: Option<String>,
    tls_enabled: Option<bool>,
    mirror_tls: Option<bool>,
    mirror_fingerprint: Option<String>,
}

async fn post_settings(
    State(state): State<Arc<WebState>>,
    headers: HeaderMap,
    Json(update): Json<SettingsUpdate>,
) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return (StatusCode::UNAUTHORIZED, "unauthorized").into_response();
    }
    let mut cfg = if std::path::Path::new(&state.config_path).exists() {
        Config::load(&state.config_path).unwrap_or_default()
    } else {
        Config::default()
    };
    if let Some(bind) = update.bind {
        if bind.parse::<std::net::SocketAddr>().is_err() {
            return (StatusCode::BAD_REQUEST, "invalid bind address").into_response();
        }
        cfg.bind = Some(bind);
    }
    if let Some(web_bind) = update.web_bind {
        if web_bind.parse::<std::net::SocketAddr>().is_err() {
            return (StatusCode::BAD_REQUEST, "invalid web_bind address").into_response();
        }
        cfg.web_bind = Some(web_bind);
    }
    // Serve-over-TLS toggle. Preserve any custom cert/key paths already configured.
    if let Some(tls_on) = update.tls_enabled {
        let existing = cfg.tls.take();
        cfg.tls = Some(crate::config::TlsConfig {
            enabled: tls_on,
            cert_path: existing.as_ref().and_then(|t| t.cert_path.clone()),
            key_path: existing.and_then(|t| t.key_path.clone()),
        });
    }
    let mut new_hash: Option<String> = None;
    if let Some(pw) = update.new_password {
        if !pw.is_empty() {
            let h = hash_password(&pw);
            cfg.password_hash = Some(h.clone());
            new_hash = Some(h);
        }
    }
    // Mirror config: clear if host is empty, otherwise update.
    if let Some(host) = update.mirror_host {
        if host.trim().is_empty() {
            cfg.mirror = None;
        } else {
            let ph = if let Some(pw) = update.mirror_password.filter(|p| !p.is_empty()) {
                sha256_hex(&pw) // mirror auth sends SHA-256 hex directly, not Argon2id
            } else if let Some(h) = update.mirror_password_hash.filter(|h| !h.is_empty()) {
                h
            } else {
                cfg.mirror
                    .as_ref()
                    .map(|m| m.password_hash.clone())
                    .unwrap_or_default()
            };
            if !ph.is_empty() {
                // Mirror TLS: take the update's values if present, else keep what's configured.
                let (cur_tls, cur_fp) = cfg
                    .mirror
                    .as_ref()
                    .map(|m| (m.tls, m.fingerprint.clone()))
                    .unwrap_or((false, None));
                let tls = update.mirror_tls.unwrap_or(cur_tls);
                let fingerprint = match update.mirror_fingerprint {
                    Some(ref f) if f.trim().is_empty() => None,
                    Some(f) => Some(f.trim().to_string()),
                    None => cur_fp,
                };
                cfg.mirror = Some(crate::config::MirrorConfig {
                    host: host.trim().to_string(),
                    password_hash: ph,
                    tls,
                    fingerprint,
                });
            }
        }
    }
    match Config::save(&state.config_path, &cfg) {
        Ok(_) => {
            // Apply password change immediately so existing sessions are invalidated
            // and new logins use the new credential without a restart.
            if let Some(h) = new_hash {
                *state
                    .password_hash
                    .write()
                    .unwrap_or_else(|e| e.into_inner()) = h;
            }
            (
                StatusCode::OK,
                "saved — restart the server to apply TLS/address changes",
            )
                .into_response()
        }
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

/// Restart the server in place by re-executing the same binary with the same arguments. Needed to
/// apply changes that are wired up only at startup (TLS listener, bind addresses, mirror task).
async fn post_restart(State(state): State<Arc<WebState>>, headers: HeaderMap) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return (StatusCode::UNAUTHORIZED, "unauthorized").into_response();
    }
    tracing::info!("restart requested via web UI");
    // Spawn so the HTTP 200 flushes to the browser before the process is replaced.
    tokio::spawn(async {
        tokio::time::sleep(std::time::Duration::from_millis(400)).await;
        let exe = match std::env::current_exe() {
            Ok(p) => p,
            Err(e) => {
                tracing::error!("restart: current_exe failed: {e}");
                std::process::exit(1);
            }
        };
        let args: Vec<std::ffi::OsString> = std::env::args_os().skip(1).collect();
        tracing::info!("re-executing {} {:?}", exe.display(), args);
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            // exec replaces the image; the listening sockets are CLOEXEC so the new process rebinds.
            let err = std::process::Command::new(&exe).args(&args).exec();
            tracing::error!("restart: exec failed: {err}");
            std::process::exit(1);
        }
        #[cfg(not(unix))]
        {
            // Fallback: spawn a fresh process then exit (works under a supervisor too).
            let _ = std::process::Command::new(&exe).args(&args).spawn();
            std::process::exit(0);
        }
    });
    (StatusCode::OK, "restarting").into_response()
}

async fn delete_item(
    State(state): State<Arc<WebState>>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return (StatusCode::UNAUTHORIZED, "unauthorized").into_response();
    }
    if !crate::session::is_valid_uuid(&id) {
        return (StatusCode::BAD_REQUEST, "invalid id").into_response();
    }
    // Must succeed: delete_item writes the tombstone that prevents a later stale re-sync from
    // resurrecting this item. Swallowing the error here (the old `let _ = ...`) meant a failed
    // delete still returned 200, broadcast the removal and deleted the file — leaving NO tombstone,
    // so the item could silently come back on the next sync. Surface the failure instead.
    if let Err(e) = state.db.delete_item(&id) {
        warn!("web api delete failed for {id}: {e}");
        return (StatusCode::INTERNAL_SERVER_ERROR, "could not delete item").into_response();
    }
    let path = PathBuf::from(state.data_dir.as_str())
        .join("files")
        .join(&id);
    let _ = tokio::fs::remove_file(&path).await;
    let _ = state
        .mirror_tx
        .send(crate::mirror::MirrorEvent::ItemDeleted(id.clone()));
    state
        .registry
        .broadcast_all(&crate::protocol::ServerMsg::ItemDeleted { id });
    (StatusCode::OK, "deleted").into_response()
}

#[derive(Serialize)]
struct ConnectionInfoResp {
    url: String,
    qr_svg: String,
    lan_ip: String,
    port: u16,
    tls: bool,
}

async fn get_connection_info(
    State(state): State<Arc<WebState>>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if !is_authed(&headers, &state.password_hash) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let cfg = if std::path::Path::new(&state.config_path).exists() {
        Config::load(&state.config_path).unwrap_or_default()
    } else {
        Config::default()
    };
    let bind = cfg.bind.unwrap_or_else(|| "0.0.0.0:9876".to_string());
    let port = bind
        .rsplit(':')
        .next()
        .and_then(|p| p.parse::<u16>().ok())
        .unwrap_or(9876);
    let tls = state.tls_fingerprint.is_some();
    let lan_ip = crate::setup::detect_lan_ip().unwrap_or_else(|| "127.0.0.1".to_string());

    let mut url = format!("stash://{}:{}", lan_ip, port);
    if tls {
        url.push_str("?tls=1");
        if let Some(fp) = &state.tls_fingerprint {
            url.push_str("&fp=");
            url.push_str(fp);
        }
    }
    let qr_svg = crate::setup::qr_svg_inline(&url);

    Json(ConnectionInfoResp {
        url,
        qr_svg,
        lan_ip,
        port,
        tls,
    })
    .into_response()
}

// ── Router ────────────────────────────────────────────────────────────────────

pub fn router(state: Arc<WebState>) -> Router {
    Router::new()
        .route("/", get(get_index))
        .route("/login", post(post_login))
        .route("/api/items", get(get_items))
        .route(
            "/api/upload",
            post(post_upload)
                // Allow large media uploads (default Axum limit is 2 MB); cap at 2 GiB.
                .layer(DefaultBodyLimit::max(2 * 1024 * 1024 * 1024)),
        )
        .route("/api/status", get(get_status))
        .route("/api/file/:id", get(get_file))
        .route("/api/items/:id", delete(delete_item))
        .route("/api/settings", get(get_settings).post(post_settings))
        .route("/api/connection-info", get(get_connection_info))
        .route("/api/restart", post(post_restart))
        .with_state(state)
}

// ── M1 + L10: cookie SameSite and bind address validation tests ───────────────
#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Body, http::Request};
    use tower::ServiceExt; // for `oneshot`

    /// Build a minimal WebState backed by an in-memory DB and a temp config file.
    fn test_state(config_path: &str) -> Arc<WebState> {
        let db = Arc::new(crate::db::Database::open(":memory:").expect("mem db"));
        let password_hash = crate::config::hash_password("test");
        Arc::new(WebState {
            db,
            password_hash: Arc::new(RwLock::new(password_hash)),
            config_path: config_path.to_string(),
            data_dir: Arc::new("/tmp".to_string()),
            registry: Arc::new(crate::session::ClientRegistry::new()),
            mirror_tx: crate::mirror::new_channel(),
            mirror_connected: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            mirror_configured: false,
            tls_fingerprint: None,
            rate_limiter: Arc::new(crate::ratelimit::RateLimiter::new()),
        })
    }

    /// Obtain a valid session cookie by posting the correct password to /login.
    async fn login_cookie(app: axum::Router, password: &str) -> String {
        let body = format!("password={password}");
        let req = Request::builder()
            .method("POST")
            .uri("/login")
            .header("content-type", "application/x-www-form-urlencoded")
            .body(Body::from(body))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK, "login must succeed");
        resp.headers()
            .get(header::SET_COOKIE)
            .expect("login must set a cookie")
            .to_str()
            .unwrap()
            .to_string()
    }

    // ── M1: /login Set-Cookie must include SameSite=Lax ─────────────────────
    //
    // Without SameSite the cookie is sent on any cross-origin POST, enabling CSRF.
    // After the fix, the Set-Cookie header must contain "SameSite=Lax".
    #[tokio::test]
    async fn login_cookie_has_samesite_lax() {
        let tmp = format!("/tmp/stash_web_test_{}.toml", std::process::id());
        let state = test_state(&tmp);
        let app = router(state);
        let cookie = login_cookie(app, "test").await;
        let _ = std::fs::remove_file(&tmp);
        assert!(
            cookie.to_lowercase().contains("samesite=lax"),
            "Set-Cookie header must contain SameSite=Lax; got: {cookie}"
        );
    }

    // ── L10: POST /api/settings with invalid bind address must return 400 ────
    //
    // Before the fix, any string was accepted and silently stored; a garbage bind
    // address would only error at server restart (too late, no feedback to the user).
    // After the fix, the endpoint validates the address with SocketAddr::parse and
    // returns 400 immediately.
    #[tokio::test]
    async fn settings_rejects_invalid_bind_address() {
        let tmp = format!("/tmp/stash_web_test_bind_{}.toml", std::process::id());
        let state = test_state(&tmp);
        let app = router(Arc::clone(&state));

        // First obtain a session cookie.
        let app2 = router(Arc::clone(&state));
        let cookie = login_cookie(app2, "test").await;

        // POST with an invalid bind address.
        let body = r#"{"bind":"not_an_address"}"#;
        let req = Request::builder()
            .method("POST")
            .uri("/api/settings")
            .header("content-type", "application/json")
            .header("cookie", &cookie)
            .body(Body::from(body))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        let _ = std::fs::remove_file(&tmp);

        assert_eq!(
            resp.status(),
            StatusCode::BAD_REQUEST,
            "invalid bind address must yield 400 Bad Request"
        );
    }

    // ── L10: POST /api/settings with a valid bind address must return 200 ────
    #[tokio::test]
    async fn settings_accepts_valid_bind_address() {
        let tmp = format!("/tmp/stash_web_test_bindok_{}.toml", std::process::id());
        let state = test_state(&tmp);

        let app = router(Arc::clone(&state));
        let app2 = router(Arc::clone(&state));

        let cookie = login_cookie(app2, "test").await;

        let body = r#"{"bind":"127.0.0.1:9999"}"#;
        let req = Request::builder()
            .method("POST")
            .uri("/api/settings")
            .header("content-type", "application/json")
            .header("cookie", &cookie)
            .body(Body::from(body))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        let _ = std::fs::remove_file(&tmp);

        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "valid bind address must yield 200 OK"
        );
    }

    // ── Web UI delete must create a tombstone (parity with WebSocket deletes) ──────
    //
    // The web DELETE /api/items/:id handler calls state.db.delete_item(id), which writes a
    // tombstone before removing the live row. (The handler is exercised here via the same DB
    // call it makes — driving the Axum handler directly is awkward because of multipart/State
    // plumbing.) Before the fix the handler used `let _ = state.db.delete_item(&id)`, swallowing
    // any failure so a delete could complete with no tombstone written.
    fn web_item(id: &str) -> Item {
        Item {
            id: id.to_string(),
            title: "web".to_string(),
            content: "body".to_string(),
            item_type: "text".to_string(),
            mime_type: String::new(),
            is_pinned: false,
            created_at: 100,
            updated_at: 100,
            has_file: false,
            file_hash: String::new(),
            thumbnail_b64: String::new(),
        }
    }

    #[tokio::test]
    async fn test_web_delete_creates_tombstone() {
        let tmp = format!("/tmp/stash_web_del_{}.toml", std::process::id());
        let state = test_state(&tmp);
        let id = "11111111-1111-1111-1111-111111111111";

        state.db.upsert_item(&web_item(id)).expect("insert item");
        assert!(
            state.db.list_items().unwrap().iter().any(|i| i.id == id),
            "item must be present before delete"
        );

        // Same call the DELETE /api/items/:id handler makes.
        state.db.delete_item(id).expect("delete must succeed");

        assert!(
            state.db.is_tombstoned(id).unwrap(),
            "web delete must record a tombstone (parity with WebSocket deletes)"
        );
        assert!(
            state.db.list_items().unwrap().iter().all(|i| i.id != id),
            "deleted item must not appear in list_items"
        );
        let _ = std::fs::remove_file(&tmp);
    }

    #[tokio::test]
    async fn test_web_delete_blocks_resurrection() {
        let tmp = format!("/tmp/stash_web_resurrect_{}.toml", std::process::id());
        let state = test_state(&tmp);
        let id = "22222222-2222-2222-2222-222222222222";

        // Insert X, then delete X (tombstone written with deleted_at ≈ now ≫ updated_at=100).
        state.db.upsert_item(&web_item(id)).expect("insert item");
        state.db.delete_item(id).expect("delete must succeed");
        assert!(state.db.is_tombstoned(id).unwrap());

        // A stale re-sync of X with the same (older) updated_at must be blocked by the tombstone.
        let r = state.db.upsert_item(&web_item(id)).expect("upsert ok");
        assert_eq!(
            r,
            crate::db::UpsertResult::Unchanged,
            "tombstone must block resurrection of a web-deleted item"
        );
        assert!(
            state.db.list_items().unwrap().iter().all(|i| i.id != id),
            "resurrected item must not appear in list_items"
        );
        let _ = std::fs::remove_file(&tmp);
    }
}
