# Stash

Save anything — links, files, images, text — and sync it across your devices over your home network. No cloud, no accounts. A self-hosted Rust server holds the canonical copy; Android and iOS clients sync to it directly.

```
/         Android app  (Kotlin, Room, OkHttp)
/server/  Sync server  (Rust, Tokio, SQLite) + web UI
/ios/     iOS client   (SwiftUI)
```

## Highlights

- **Your own cloud.** Run the server on your LAN, or expose it over the internet via a DynDNS address or VPN — no third-party cloud involved either way. Single self-contained binary or Docker image.
- **Chunked file transfer with resume.** Uploads and downloads survive interruptions and restart from the last confirmed chunk. SHA-256 integrity check on receipt.
- **Optional TLS** (`wss://`) with self-signed cert auto-generation and **fingerprint pinning** (trust-on-first-use, MITM-resistant).
- **Server-to-server mirror.** Two servers sync bidirectionally; files propagate reactively.
- **Embedded web UI.** Browse, download, and manage items in a browser. Live-updates as items change. Edit all server settings (TLS, mirror, password) and restart without touching the terminal.
- **Argon2id password hashing.** The server stores `Argon2id(SHA-256(password))`; clients send `SHA-256(password)` — plaintext never leaves the device. Per-IP rate limiting (5 failures / 60 s).

## Quick start — Docker

Edit `docker/compose.yml` and set your password (default is `changeme`), then:

```bash
docker compose -f docker/compose.yml up -d
```

- Port **9876** — WebSocket sync (point clients here)
- Port **9877** — web UI

Data persists in a Docker volume. To update: rebuild the image and restart the container.

## Sync server — bare metal

```bash
cd server
cargo build --release

./stash-server set-password --password yourpassword
./stash-server
```

Key config options in `stash-server.toml` (or via the web UI):

```toml
bind     = "0.0.0.0:9876"
web_bind = "0.0.0.0:9877"
data_dir = "./data"

[tls]
enabled = true          # auto-generates a self-signed cert; logs its fingerprint

[mirror]
host          = "192.168.1.x:9876"
password_hash = "<sha256-hex-of-peer-password>"
tls           = true
```

### systemd

```ini
[Service]
ExecStart=/opt/stash-server/stash-server --config /opt/stash-server/stash-server.toml
Restart=always
```

## Android app

Install the APK from Releases, or build:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## iOS app

SwiftUI client in [`ios/`](ios/README.md) — same protocol, same sync guarantees. See its README for build steps.
