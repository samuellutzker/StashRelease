mod auth;
mod config;
mod constants;
mod db;
mod hash;
mod mirror;
mod protocol;
mod ratelimit;
mod session;
mod setup;
mod tls;
mod upload;
mod web;

use tracing::{error, info, warn};

use clap::{Parser, Subcommand};
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use tokio::net::TcpListener;
use tokio_tungstenite::accept_async;

use config::{hash_password, Config};
use db::Database;
use ratelimit::RateLimiter;
use session::ClientRegistry;
use upload::UploadRegistry;

#[derive(Parser, Debug)]
#[command(name = "stash-server", about = "Stash sync server")]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,

    #[arg(long, global = true, default_value = "stash-server.toml")]
    config: String,

    #[arg(long)]
    bind: Option<String>,
    #[arg(long)]
    password_hash: Option<String>,
    #[arg(long)]
    data_dir: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Command {
    Run {
        #[arg(long)]
        bind: Option<String>,
        #[arg(long)]
        password_hash: Option<String>,
        #[arg(long)]
        data_dir: Option<String>,
    },
    SetPassword {
        #[arg(long)]
        password: String,
    },
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let cli = Cli::parse();
    match cli.command {
        Some(Command::SetPassword { password }) => cmd_set_password(&cli.config, &password),
        Some(Command::Run {
            bind,
            password_hash,
            data_dir,
        }) => {
            cmd_run(&cli.config, bind, password_hash, data_dir).await;
        }
        None => cmd_run(&cli.config, cli.bind, cli.password_hash, cli.data_dir).await,
    }
}

fn cmd_set_password(config_path: &str, password: &str) {
    let hash = hash_password(password);
    let mut cfg = if std::path::Path::new(config_path).exists() {
        Config::load(config_path).unwrap_or_default()
    } else {
        Config::default()
    };
    cfg.password_hash = Some(hash.clone());
    match Config::save(config_path, &cfg) {
        Ok(_) => info!("Password hash saved to '{}'. Hash: {}", config_path, hash),
        Err(e) => error!("Error saving config: {e}"),
    }
}

async fn cmd_run(
    config_path: &str,
    bind_override: Option<String>,
    hash_override: Option<String>,
    data_dir_override: Option<String>,
) {
    // First-run: if no valid config exists, open the setup wizard and block until done.
    setup::run_if_needed(config_path).await;

    let file_cfg = if std::path::Path::new(config_path).exists() {
        Config::load(config_path).unwrap_or_else(|e| {
            warn!("Config warning: {e}");
            Config::default()
        })
    } else {
        Config::default()
    };

    let bind = bind_override
        .or(file_cfg.bind)
        .unwrap_or_else(|| "0.0.0.0:9876".to_string());

    let password_hash = hash_override.or(file_cfg.password_hash).unwrap_or_else(|| {
        error!("No password hash set. Run `stash-server set-password --password <pw>` first.");
        std::process::exit(1);
    });

    if config::is_legacy_hash(&password_hash) {
        error!(
            "Config uses a legacy SHA-256 password hash. Upgrade by running:\n  \
             stash-server --config {} set-password --password <your-password>",
            config_path
        );
        std::process::exit(1);
    }

    let data_dir = data_dir_override
        .or(file_cfg.data_dir)
        .unwrap_or_else(|| "./data".to_string());
    let max_file_size_mb = file_cfg.max_file_size_mb.unwrap_or(2048);
    let web_bind = file_cfg
        .web_bind
        .unwrap_or_else(|| "127.0.0.1:9877".to_string());

    std::fs::create_dir_all(&data_dir).expect("cannot create data dir");
    std::fs::create_dir_all(format!("{}/files", data_dir)).expect("cannot create files dir");

    let data_dir = Arc::new(data_dir);

    let db =
        Arc::new(Database::open(&format!("{}/stash.db", data_dir)).expect("cannot open database"));
    let registry = Arc::new(ClientRegistry::new());
    let upload_registry = Arc::new(UploadRegistry::new(&data_dir));
    let listener = TcpListener::bind(&bind).await.expect("cannot bind");

    // Optional TLS for the sync WebSocket (wss://).
    let tls = match file_cfg.tls.as_ref().filter(|t| t.enabled) {
        Some(t) => match tls::load(&data_dir, t.cert_path.as_deref(), t.key_path.as_deref()) {
            Ok(t) => Some(Arc::new(t)),
            Err(e) => {
                error!("TLS enabled but failed to initialise: {e:#}");
                std::process::exit(1);
            }
        },
        None => None,
    };

    // Mirror broadcast channel — sessions send events; mirror task forwards them to peer.
    let mirror_tx = mirror::new_channel();

    // Live mirror-connection status for the web UI (1 while the peer session is up).
    let mirror_connected = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mirror_configured = file_cfg.mirror.is_some();

    // Start mirror task if configured.
    if let Some(mirror_cfg) = file_cfg.mirror.clone() {
        mirror::spawn(
            mirror_cfg.host,
            mirror_cfg.password_hash,
            mirror_cfg.tls,
            mirror_cfg.fingerprint,
            Arc::clone(&db),
            Arc::clone(&registry),
            mirror_tx.clone(),
            Arc::clone(&data_dir),
            Arc::clone(&mirror_connected),
        );
    }

    // Prune tombstones older than 30 days at startup, then every 24 hours.
    {
        let db = Arc::clone(&db);
        tokio::spawn(async move {
            const THIRTY_DAYS_MS: i64 = 30 * 24 * 60 * 60 * 1_000;
            loop {
                match db.prune_tombstones(THIRTY_DAYS_MS) {
                    Ok(0) => info!("tombstone GC: nothing to prune"),
                    Ok(n) => info!("tombstone GC: pruned {n} tombstone(s) older than 30 days"),
                    Err(e) => warn!("tombstone GC error: {e}"),
                }
                tokio::time::sleep(tokio::time::Duration::from_secs(24 * 60 * 60)).await;
            }
        });
    }

    let scheme = if tls.is_some() { "wss" } else { "ws" };
    info!("stash-server listening on {scheme}://{bind}  (data: {data_dir}, max_file: {max_file_size_mb}MB)");
    if let Some(t) = &tls {
        info!(
            "TLS enabled — certificate SHA-256 fingerprint:\n    {}",
            t.fingerprint
        );
    }
    info!("web UI listening on {web_bind}  (set web_bind=\"0.0.0.0:9877\" in conf.toml for LAN access)");

    // Shared mutable password hash: RwLock lets the web UI update it live.
    let password_hash: Arc<RwLock<String>> = Arc::new(RwLock::new(password_hash));

    // Shared rate limiter for both WS and web login.
    let rate_limiter = Arc::new(RateLimiter::new());

    // Spawn web UI server
    {
        let web_state = Arc::new(web::WebState {
            db: Arc::clone(&db),
            password_hash: Arc::clone(&password_hash),
            config_path: config_path.to_string(),
            data_dir: Arc::clone(&data_dir),
            registry: Arc::clone(&registry),
            mirror_tx: mirror_tx.clone(),
            mirror_connected: Arc::clone(&mirror_connected),
            mirror_configured,
            tls_fingerprint: tls.as_ref().map(|t| t.fingerprint.clone()),
            rate_limiter: Arc::clone(&rate_limiter),
        });
        let web_addr: std::net::SocketAddr = web_bind.parse().unwrap_or_else(|_| {
            warn!("Invalid web_bind '{web_bind}', using 127.0.0.1:9877");
            "127.0.0.1:9877".parse().unwrap()
        });
        let app = web::router(web_state);
        tokio::spawn(async move {
            let listener = tokio::net::TcpListener::bind(web_addr)
                .await
                .expect("cannot bind web UI listener");
            axum::serve(listener, app)
                .await
                .expect("web UI server error");
        });
    }

    loop {
        let (stream, addr) = match listener.accept().await {
            Ok(v) => v,
            Err(e) => {
                error!("accept error: {e}");
                continue;
            }
        };
        let (db, registry, upload_registry, password_hash, data_dir, mirror_tx, tls, rate_limiter) = (
            Arc::clone(&db),
            Arc::clone(&registry),
            Arc::clone(&upload_registry),
            Arc::clone(&password_hash),
            Arc::clone(&data_dir),
            mirror_tx.clone(),
            tls.clone(),
            Arc::clone(&rate_limiter),
        );
        tokio::spawn(async move {
            let res = match tls {
                Some(tls) => match tls.acceptor.accept(stream).await {
                    Ok(tls_stream) => {
                        handle_connection(
                            tls_stream,
                            addr,
                            db,
                            registry,
                            upload_registry,
                            password_hash,
                            data_dir,
                            max_file_size_mb,
                            mirror_tx,
                            rate_limiter,
                        )
                        .await
                    }
                    Err(e) => {
                        error!("[{addr}] TLS handshake error: {e}");
                        return;
                    }
                },
                None => {
                    handle_connection(
                        stream,
                        addr,
                        db,
                        registry,
                        upload_registry,
                        password_hash,
                        data_dir,
                        max_file_size_mb,
                        mirror_tx,
                        rate_limiter,
                    )
                    .await
                }
            };
            if let Err(e) = res {
                error!("[{addr}] connection error: {e}");
            }
        });
    }
}

#[allow(clippy::too_many_arguments)]
async fn handle_connection<S>(
    stream: S,
    addr: SocketAddr,
    db: Arc<Database>,
    registry: Arc<ClientRegistry>,
    upload_registry: Arc<UploadRegistry>,
    password_hash: Arc<RwLock<String>>,
    data_dir: Arc<String>,
    max_file_size_mb: u64,
    mirror_tx: mirror::MirrorTx,
    rate_limiter: Arc<RateLimiter>,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let ws = accept_async(stream).await?;
    info!("[{addr}] connected");
    session::run(
        ws,
        addr,
        db,
        registry,
        upload_registry,
        password_hash,
        data_dir,
        max_file_size_mb,
        mirror_tx,
        rate_limiter,
    )
    .await;
    info!("[{addr}] disconnected");
    Ok(())
}

// ── Mirror file-sync integration tests ────────────────────────────────────────
//
// These spin up real loopback WebSocket servers wired exactly like production
// (session::run per connection, mirror::spawn for the outbound peer link) plus the
// real web router, then exercise web uploads and assert the file BYTES — not just the
// metadata — propagate between a mirror and its main server in both directions.
#[cfg(test)]
mod sync_tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{header, Request, StatusCode};
    use std::path::PathBuf;
    use std::sync::atomic::AtomicUsize;
    use std::time::Duration;
    use tower::ServiceExt; // oneshot

    /// One running server node: a WS server (session::run) + the web router, all sharing one DB,
    /// registry, upload registry, mirror channel and data dir — same wiring as main().
    struct Node {
        db: Arc<Database>,
        data_dir: Arc<String>,
        mirror_tx: mirror::MirrorTx,
        web: Arc<web::WebState>,
        ws_addr: std::net::SocketAddr,
        password_hash: String,
    }

    async fn start_node(tag: &str) -> Node {
        let base = std::env::temp_dir().join(format!(
            "stash_sync_{tag}_{}_{}",
            std::process::id(),
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(base.join("files")).unwrap();
        std::fs::create_dir_all(base.join("uploads")).unwrap();
        let data_dir = Arc::new(base.to_string_lossy().to_string());

        let db = Arc::new(Database::open(&format!("{}/stash.db", data_dir)).unwrap());
        let registry = Arc::new(ClientRegistry::new());
        let upload_registry = Arc::new(UploadRegistry::new(&data_dir));
        // pw_lock holds the Argon2id hash (what the server stores and verifies against).
        // password_hash in the Node is the SHA-256 hex (what mirrors send during auth).
        let pw_lock: Arc<RwLock<String>> = Arc::new(RwLock::new(hash_password("secret")));
        let password_hash = config::sha256_hex("secret");
        let mirror_tx = mirror::new_channel();
        let mirror_connected = Arc::new(AtomicUsize::new(0));

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ws_addr = listener.local_addr().unwrap();

        let rate_limiter = Arc::new(RateLimiter::new());

        // Accept loop running real sessions.
        {
            let (db, registry, upload_registry, pw_lock, data_dir, mirror_tx, rate_limiter) = (
                Arc::clone(&db),
                Arc::clone(&registry),
                Arc::clone(&upload_registry),
                Arc::clone(&pw_lock),
                Arc::clone(&data_dir),
                mirror_tx.clone(),
                Arc::clone(&rate_limiter),
            );
            tokio::spawn(async move {
                loop {
                    let Ok((stream, addr)) = listener.accept().await else {
                        break;
                    };
                    let (db, registry, upload_registry, pw_lock, data_dir, mirror_tx, rate_limiter) = (
                        Arc::clone(&db),
                        Arc::clone(&registry),
                        Arc::clone(&upload_registry),
                        Arc::clone(&pw_lock),
                        Arc::clone(&data_dir),
                        mirror_tx.clone(),
                        Arc::clone(&rate_limiter),
                    );
                    tokio::spawn(async move {
                        let _ = handle_connection(
                            stream,
                            addr,
                            db,
                            registry,
                            upload_registry,
                            pw_lock,
                            data_dir,
                            2048,
                            mirror_tx,
                            rate_limiter,
                        )
                        .await;
                    });
                }
            });
        }

        let web = Arc::new(web::WebState {
            db: Arc::clone(&db),
            password_hash: pw_lock,
            config_path: base.join("conf.toml").to_string_lossy().to_string(),
            data_dir: Arc::clone(&data_dir),
            registry,
            mirror_tx: mirror_tx.clone(),
            mirror_connected,
            mirror_configured: false,
            tls_fingerprint: None,
            rate_limiter,
        });

        Node {
            db,
            data_dir,
            mirror_tx,
            web,
            ws_addr,
            password_hash,
        }
    }

    /// Make this node mirror `peer` (outbound mirror::spawn connection, like a mirror→main link).
    fn link_mirror(node: &Node, peer: &Node) {
        mirror::spawn(
            peer.ws_addr.to_string(),
            peer.password_hash.clone(),
            false,
            None,
            Arc::clone(&node.db),
            Arc::clone(&node.web.registry),
            node.mirror_tx.clone(),
            Arc::clone(&node.data_dir),
            Arc::new(AtomicUsize::new(0)),
        );
    }

    async fn login_cookie(node: &Node) -> String {
        let req = Request::builder()
            .method("POST")
            .uri("/login")
            .header("content-type", "application/x-www-form-urlencoded")
            .body(Body::from("password=secret"))
            .unwrap();
        let resp = web::router(Arc::clone(&node.web))
            .oneshot(req)
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        resp.headers()
            .get(header::SET_COOKIE)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string()
    }

    /// Upload a file through the real web /api/upload multipart handler. Returns the new item id.
    async fn web_upload(
        node: &Node,
        cookie: &str,
        filename: &str,
        mime: &str,
        bytes: &[u8],
    ) -> String {
        let boundary = "X-STASH-TEST-BOUNDARY";
        let mut body: Vec<u8> = Vec::new();
        body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        body.extend_from_slice(
            format!("Content-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n")
                .as_bytes(),
        );
        body.extend_from_slice(format!("Content-Type: {mime}\r\n\r\n").as_bytes());
        body.extend_from_slice(bytes);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let req = Request::builder()
            .method("POST")
            .uri("/api/upload")
            .header("cookie", cookie)
            .header(
                "content-type",
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .unwrap();
        let resp = web::router(Arc::clone(&node.web))
            .oneshot(req)
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK, "upload must succeed");
        let body = axum::body::to_bytes(resp.into_body(), usize::MAX)
            .await
            .unwrap();
        let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
        v["id"].as_str().unwrap().to_string()
    }

    fn file_path(node: &Node, id: &str) -> PathBuf {
        PathBuf::from(node.data_dir.as_str()).join("files").join(id)
    }

    /// Poll until `cond` is true or the timeout elapses.
    async fn wait_until<F: Fn() -> bool>(cond: F, label: &str) {
        for _ in 0..100 {
            if cond() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        panic!("timed out waiting for: {label}");
    }

    /// Fix 2 (mirror → main): a file uploaded via the web UI on a MIRROR must have its bytes
    /// pushed to the main server, not just its metadata.
    #[tokio::test]
    async fn web_upload_on_mirror_pushes_file_to_main() {
        let main = start_node("main_a").await;
        let mirror = start_node("mirror_a").await;
        link_mirror(&mirror, &main);
        // Give the mirror time to connect + finish the initial handshake.
        tokio::time::sleep(Duration::from_millis(500)).await;

        let cookie = login_cookie(&mirror).await;
        let bytes = vec![0xABu8; 700 * 1024]; // > CHUNK_SIZE so multiple chunks are exercised
        let id = web_upload(
            &mirror,
            &cookie,
            "photo.bin",
            "application/octet-stream",
            &bytes,
        )
        .await;

        // Bytes must land on main and match.
        let main_path = file_path(&main, &id);
        wait_until(|| main_path.exists(), "file present on main").await;
        let got = std::fs::read(&main_path).unwrap();
        assert_eq!(got, bytes, "main server's file bytes must match the upload");
        // And main must mark has_file=true once verified.
        wait_until(
            || matches!(main.db.get_item(&id), Ok(Some(it)) if it.has_file),
            "main marks has_file=true",
        )
        .await;
    }

    /// Fix 2 (main → mirror): a file uploaded via the web UI on the MAIN server must sync to mirrors.
    #[tokio::test]
    async fn web_upload_on_main_syncs_to_mirror() {
        let main = start_node("main_b").await;
        let mirror = start_node("mirror_b").await;
        link_mirror(&mirror, &main);
        tokio::time::sleep(Duration::from_millis(500)).await;

        let cookie = login_cookie(&main).await;
        let bytes = vec![0x5Au8; 500 * 1024];
        let id = web_upload(
            &main,
            &cookie,
            "doc.bin",
            "application/octet-stream",
            &bytes,
        )
        .await;

        let mirror_path = file_path(&mirror, &id);
        wait_until(|| mirror_path.exists(), "file present on mirror").await;
        let got = std::fs::read(&mirror_path).unwrap();
        assert_eq!(got, bytes, "mirror's file bytes must match the upload");
    }

    /// Fix 3: a small .txt upload becomes an inline text item (content = the text, no file on disk),
    /// and that text syncs to the peer.
    #[tokio::test]
    async fn web_upload_small_text_is_inline_and_syncs() {
        let main = start_node("main_c").await;
        let mirror = start_node("mirror_c").await;
        link_mirror(&mirror, &main);
        tokio::time::sleep(Duration::from_millis(500)).await;

        let cookie = login_cookie(&mirror).await;
        let text = "First line is the title\nsecond line\nthird";
        let id = web_upload(&mirror, &cookie, "note.txt", "text/plain", text.as_bytes()).await;

        // On the mirror: text item, content holds the text, title is the first line, no file stored.
        let item = mirror.db.get_item(&id).unwrap().unwrap();
        assert_eq!(item.item_type, "text");
        assert_eq!(item.content, text);
        assert_eq!(item.title, "First line is the title");
        assert!(!item.has_file, "small text item must not store a file");
        assert!(
            !file_path(&mirror, &id).exists(),
            "no file should be written for inline text"
        );

        // The text item must sync to main with its content intact.
        wait_until(
            || matches!(main.db.get_item(&id), Ok(Some(it)) if it.content == text && it.item_type == "text"),
            "text item synced to main",
        ).await;
    }

    /// Fix 3 (boundary): a .txt larger than 64 KB is stored as a document file, not inline.
    #[tokio::test]
    async fn web_upload_large_text_is_document_with_file() {
        let main = start_node("main_d").await;
        let cookie = login_cookie(&main).await;
        let big = "x".repeat(70 * 1024); // > 64 KB
        let id = web_upload(&main, &cookie, "big.txt", "text/plain", big.as_bytes()).await;

        let item = main.db.get_item(&id).unwrap().unwrap();
        assert_ne!(
            item.item_type, "text",
            "large text file must not be an inline text item"
        );
        assert!(item.has_file, "large text file must be stored on disk");
        assert!(
            file_path(&main, &id).exists(),
            "large text file must exist on disk"
        );
    }
}
