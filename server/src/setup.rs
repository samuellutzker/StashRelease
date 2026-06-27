use axum::{
    extract::State,
    http::StatusCode,
    response::Html,
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use std::sync::Arc;
use tokio::sync::{Mutex, Notify};
use tracing::info;

use crate::config::{hash_password, Config, TlsConfig};

struct SetupState {
    config_path: String,
    done: Notify,
    saved_info: Mutex<Option<ConnInfo>>,
}

struct ConnInfo {
    port: u16,
    tls: bool,
}

#[derive(Deserialize)]
struct SetupRequest {
    password: String,
    port: u16,
    data_dir: String,
    max_file_size_mb: u64,
    tls: bool,
}

/// Noop if a valid config with a password hash already exists.
/// Otherwise starts a local HTTP server, opens the user's browser,
/// and blocks until the user clicks "Start Server" on the /ready page.
pub async fn run_if_needed(config_path: &str) {
    if needs_setup(config_path) {
        run_wizard(config_path).await;
    }
}

fn needs_setup(config_path: &str) -> bool {
    if !std::path::Path::new(config_path).exists() {
        return true;
    }
    Config::load(config_path).map_or(true, |cfg| cfg.password_hash.is_none())
}

async fn run_wizard(config_path: &str) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("setup wizard: cannot bind");
    let port = listener.local_addr().unwrap().port();
    let url = format!("http://127.0.0.1:{port}");

    info!("First run — complete setup at {url}");
    open_browser(&url);

    let state = Arc::new(SetupState {
        config_path: config_path.to_string(),
        done: Notify::new(),
        saved_info: Mutex::new(None),
    });

    let app = Router::new()
        .route("/", get(get_setup_page))
        .route("/setup", post(post_setup))
        .route("/ready", get(get_ready_page))
        .route("/done", post(post_done))
        .with_state(Arc::clone(&state));

    let shutdown_state = Arc::clone(&state);
    axum::serve(listener, app)
        .with_graceful_shutdown(async move { shutdown_state.done.notified().await })
        .await
        .expect("setup wizard: server error");

    info!("Setup complete — starting server…");
}

fn open_browser(url: &str) {
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(url).spawn();
    }
    #[cfg(target_os = "linux")]
    {
        let _ = std::process::Command::new("xdg-open").arg(url).spawn();
    }
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", url])
            .spawn();
    }
}

async fn get_setup_page() -> Html<&'static str> {
    Html(SETUP_HTML)
}

async fn post_setup(
    State(state): State<Arc<SetupState>>,
    Json(req): Json<SetupRequest>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    if req.password.len() < 6 {
        return Err((
            StatusCode::BAD_REQUEST,
            "Password must be at least 6 characters.".into(),
        ));
    }
    if req.port == 0 {
        return Err((StatusCode::BAD_REQUEST, "Invalid port number.".into()));
    }

    let data_dir = req.data_dir.trim().to_string();
    let data_dir = if data_dir.is_empty() {
        "./data".to_string()
    } else {
        data_dir
    };

    let cfg = Config {
        bind: Some(format!("0.0.0.0:{}", req.port)),
        password_hash: Some(hash_password(&req.password)),
        data_dir: Some(data_dir),
        max_file_size_mb: Some(req.max_file_size_mb.max(1)),
        web_bind: None,
        mirror: None,
        tls: if req.tls {
            Some(TlsConfig {
                enabled: true,
                cert_path: None,
                key_path: None,
            })
        } else {
            None
        },
    };

    Config::save(&state.config_path, &cfg)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    *state.saved_info.lock().await = Some(ConnInfo {
        port: req.port,
        tls: req.tls,
    });

    Ok(Json(serde_json::json!({"ok": true})))
}

async fn get_ready_page(State(state): State<Arc<SetupState>>) -> Html<String> {
    let (port, tls) = {
        let guard = state.saved_info.lock().await;
        let Some(info) = &*guard else {
            return Html("<p style='font-family:sans-serif;padding:20px'>Setup not yet complete. <a href='/'>Go back</a></p>".into());
        };
        (info.port, info.tls)
    };

    let lan_ip = detect_lan_ip().unwrap_or_else(|| "127.0.0.1".to_string());
    let mut url = format!("stash://{}:{}", lan_ip, port);
    if tls {
        url.push_str("?tls=1");
    }
    let qr = qr_svg_inline(&url);

    let html = READY_HTML_TEMPLATE
        .replace("__QR_SVG__", &qr)
        .replace("__URL__", &url)
        .replace("__PORT__", &port.to_string())
        .replace("__TLS__", if tls { "Yes" } else { "No" });
    Html(html)
}

async fn post_done(State(state): State<Arc<SetupState>>) -> Json<serde_json::Value> {
    state.done.notify_one();
    Json(serde_json::json!({"ok": true}))
}

/// Detect the primary LAN IP by connecting a UDP socket (no packet sent).
pub(crate) fn detect_lan_ip() -> Option<String> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    Some(sock.local_addr().ok()?.ip().to_string())
}

/// Render a QR code as an inline SVG (XML declaration stripped).
pub(crate) fn qr_svg_inline(data: &str) -> String {
    use qrcode::{render::svg, QrCode};
    let code = QrCode::new(data).unwrap_or_else(|_| QrCode::new(b"?").unwrap());
    let svg = code.render::<svg::Color>().min_dimensions(200, 200).build();
    match svg.find("<svg") {
        Some(i) => svg[i..].to_string(),
        None => svg,
    }
}

const SETUP_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Stash Server — Setup</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'><defs><linearGradient id='g' x1='0' y1='26' x2='0' y2='67' gradientUnits='userSpaceOnUse'><stop offset='0' stop-color='%23FFC246'/><stop offset='1' stop-color='%23FF8F00'/></linearGradient></defs><rect width='108' height='108' rx='16' fill='%231A237E'/><path fill='url(%23g)' d='M47,26 L61,26 L61,46 L69,46 L54,67 L39,46 L47,46 Z'/><path fill='none' stroke='%23FFFFFF' stroke-width='6' stroke-linecap='round' stroke-linejoin='round' d='M38,69 L44,81 L64,81 L70,69'/></svg>">
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{--bg:#0f0f17;--surface:#1a1a2e;--surface2:#252540;--border:#2e2e50;--text:#e0e0f0;--dim:#7878a0;--accent:#FF8F00;--accent-h:#FFB300;--danger:#e05060}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:var(--bg);color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}
.card{background:var(--surface);border-radius:16px;padding:36px;width:100%;max-width:440px;box-shadow:0 8px 40px rgba(0,0,0,.5)}
h1{font-size:22px;font-weight:700;margin-bottom:4px}
.sub{color:var(--dim);font-size:13px;margin-bottom:28px;line-height:1.5}
.field{margin-bottom:14px}
label{display:block;font-size:13px;color:var(--dim);margin-bottom:5px}
input[type=password],input[type=text],input[type=number]{width:100%;padding:11px 14px;background:var(--bg);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:15px;outline:none;transition:border-color .2s}
input:focus{border-color:var(--accent)}
.row{display:flex;gap:12px}
.row .field{flex:1}
.check-row{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--dim);cursor:pointer;padding:6px 0;margin-bottom:8px}
.check-row input[type=checkbox]{width:16px;height:16px;accent-color:var(--accent);cursor:pointer;flex-shrink:0}
.btn{width:100%;padding:12px;background:var(--accent);color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:600;cursor:pointer;margin-top:6px;transition:background .2s}
.btn:hover{background:var(--accent-h)}
.btn:disabled{opacity:.5;cursor:default}
.err{color:var(--danger);font-size:13px;margin-top:10px;min-height:18px}
</style>
</head>
<body>
<div class="card">
  <h1>🗂  Stash Server Setup</h1>
  <p class="sub">First-run configuration. Settings are saved to stash-server.toml alongside the server binary.</p>

  <div class="field"><label>Password</label>
    <input type="password" id="pw" placeholder="Choose a password" autofocus></div>
  <div class="field"><label>Confirm password</label>
    <input type="password" id="pw2" placeholder="Repeat password"></div>

  <div class="row">
    <div class="field"><label>Sync port</label>
      <input type="number" id="port" value="9876" min="1" max="65535"></div>
    <div class="field"><label>Max file size (MB)</label>
      <input type="number" id="maxmb" value="2048" min="1"></div>
  </div>

  <div class="field"><label>Data directory</label>
    <input type="text" id="datadir" value="./data"></div>

  <label class="check-row">
    <input type="checkbox" id="tls" checked>
    Enable TLS (self-signed cert — required for iOS &amp; Android)
  </label>

  <button class="btn" id="btn" onclick="go()">Continue →</button>
  <div class="err" id="err"></div>
</div>
<script>
async function go() {
  const pw  = document.getElementById('pw').value;
  const pw2 = document.getElementById('pw2').value;
  const err = document.getElementById('err');
  err.textContent = '';
  if (!pw)           { err.textContent = 'Password is required.'; return; }
  if (pw.length < 6) { err.textContent = 'Password must be at least 6 characters.'; return; }
  if (pw !== pw2)    { err.textContent = 'Passwords do not match.'; return; }
  const port = parseInt(document.getElementById('port').value);
  if (!port || port < 1 || port > 65535) { err.textContent = 'Invalid port.'; return; }
  const btn = document.getElementById('btn');
  btn.disabled = true; btn.textContent = 'Saving…';
  try {
    const r = await fetch('/setup', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        password: pw, port,
        data_dir: document.getElementById('datadir').value.trim() || './data',
        max_file_size_mb: parseInt(document.getElementById('maxmb').value) || 2048,
        tls: document.getElementById('tls').checked,
      })
    });
    if (r.ok) {
      window.location.href = '/ready';
    } else {
      err.textContent = await r.text();
      btn.disabled = false; btn.textContent = 'Continue →';
    }
  } catch(e) {
    err.textContent = 'Request failed: ' + e.message;
    btn.disabled = false; btn.textContent = 'Continue →';
  }
}
</script>
</body>
</html>"#;

const READY_HTML_TEMPLATE: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Stash Server — Ready</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'><defs><linearGradient id='g' x1='0' y1='26' x2='0' y2='67' gradientUnits='userSpaceOnUse'><stop offset='0' stop-color='%23FFC246'/><stop offset='1' stop-color='%23FF8F00'/></linearGradient></defs><rect width='108' height='108' rx='16' fill='%231A237E'/><path fill='url(%23g)' d='M47,26 L61,26 L61,46 L69,46 L54,67 L39,46 L47,46 Z'/><path fill='none' stroke='%23FFFFFF' stroke-width='6' stroke-linecap='round' stroke-linejoin='round' d='M38,69 L44,81 L64,81 L70,69'/></svg>">
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{--bg:#0f0f17;--surface:#1a1a2e;--surface2:#252540;--border:#2e2e50;--text:#e0e0f0;--dim:#7878a0;--accent:#FF8F00;--accent-h:#FFB300;--green:#4caf50}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:var(--bg);color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}
.card{background:var(--surface);border-radius:16px;padding:36px;width:100%;max-width:480px;box-shadow:0 8px 40px rgba(0,0,0,.5);text-align:center}
h1{font-size:22px;font-weight:700;margin-bottom:8px}
.sub{color:var(--dim);font-size:14px;margin-bottom:24px;line-height:1.6}
.qr-wrap{background:#fff;border-radius:12px;padding:12px;display:inline-block;margin:0 auto 20px}
.qr-wrap svg{display:block;width:200px;height:200px}
.url-box{background:var(--surface2);border:1px solid var(--border);border-radius:8px;padding:10px 14px;font-size:12px;word-break:break-all;margin-bottom:20px;color:var(--dim);text-align:left;font-family:monospace}
.info{font-size:13px;color:var(--dim);margin-bottom:24px;line-height:1.7}
.info strong{color:var(--text)}
.btn{display:inline-block;padding:12px 36px;background:var(--accent);color:#fff;border:none;border-radius:8px;font-size:15px;font-weight:600;cursor:pointer;transition:background .2s}
.btn:hover{background:var(--accent-h)}
.btn:disabled{opacity:.5;cursor:default}
.done{display:none;color:var(--green);font-size:14px;margin-top:14px}
</style>
</head>
<body>
<div class="card">
  <h1>✅ Setup Complete</h1>
  <p class="sub">Scan the QR code with the Stash app to configure it automatically,<br>then click Start Server.</p>

  <div class="qr-wrap">__QR_SVG__</div>

  <div class="url-box">__URL__</div>

  <p class="info">
    Port: <strong>__PORT__</strong> &nbsp;·&nbsp; TLS: <strong>__TLS__</strong><br>
    Open the Stash app → Settings → Scan QR
  </p>

  <button class="btn" id="startBtn" onclick="startServer()">Start Server →</button>
  <div class="done" id="doneMsg">Server is starting… you can close this tab.</div>
</div>
<script>
async function startServer() {
  const btn = document.getElementById('startBtn');
  btn.disabled = true; btn.textContent = 'Starting…';
  try {
    await fetch('/done', {method:'POST'});
    btn.style.display = 'none';
    document.getElementById('doneMsg').style.display = 'block';
  } catch(e) {
    btn.disabled = false; btn.textContent = 'Start Server →';
  }
}
</script>
</body>
</html>"#;
