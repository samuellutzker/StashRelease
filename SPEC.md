# Stash — Technical Reference

## Overview

Stash is an Android app for saving and organizing content shared from any app: links, images,
videos, audio, documents, text, APKs, contacts, and archives. An optional self-hosted Rust sync
server keeps items and files in sync across multiple devices over a local network (WebSocket,
no cloud dependency). An iOS client (SwiftUI) speaks the same protocol.

---

## Android App

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | ViewBinding, Material Design, RecyclerView |
| Database | Room (SQLite), **version 8** |
| Background sync | WorkManager (guaranteed background execution) |
| Sync | OkHttp WebSocket |
| Crypto | MessageDigest SHA-256 (password hash for auth) |
| Build | Gradle, minSdk 26, targetSdk 34 |

### Data Model — `StashItem`

```kotlin
@Entity(tableName = "stash_items")
data class StashItem(
    val id: Long = 0,               // auto-generated primary key
    val title: String,              // display title; auto-derived on save
    val content: String,            // URL (link), text body, or original filename (file)
    val type: String,               // see Content Types below
    val mimeType: String,
    val originalFilename: String,
    val fileSize: Long = -1L,
    val sourceDomain: String,       // e.g. "youtube.com" for links
    val thumbnailPath: String = "", // absolute path to local JPEG thumbnail
    val isPinned: Boolean = false,
    val createdAt: Long,            // Unix epoch ms
    val updatedAt: Long,            // Unix epoch ms — last-writer-wins on edits
    val syncId: String = "",        // UUID assigned on first sync
    val source: String = "local",   // "local" or "cloud"
    val fileLocal: Boolean = true,  // true if file bytes are on this device
    val fileHash: String = "",      // SHA-256 hex for deduplication + integrity
    val removedLocally: Boolean = false,  // ghost state: metadata kept, local file gone
    val serverHasFile: Boolean = false    // server has confirmed receipt of the file
)
```

**Room migrations:**
- v1→v2: add `isPinned`
- v2→v3: add `syncId`, `source`, `fileLocal`
- v3→v4: add `fileHash`
- v4→v5: add `updatedAt`
- v5→v6: add `PendingOp` table (pending offline operations)
- v6→v7: add `removedLocally`
- v7→v8: add `serverHasFile`

### Content Types

| Type | Detection | Examples |
|---|---|---|
| `link` | MIME `text/plain` + URL pattern | web URLs |
| `image` | MIME `image/*` | JPEG, PNG, GIF |
| `video` | MIME `video/*` | MP4, MKV |
| `audio` | MIME `audio/*` | MP3, OGG |
| `document` | PDF, Office, CSV, RTF | |
| `text` | MIME `text/plain` (non-URL) | notes |
| `archive` | ZIP, RAR, 7z, TAR | |
| `apk` | `application/vnd.android.package-archive` | |
| `contact` | vCard MIME types | |
| `other` | everything else | |

Detection uses `ContentTypeDetector.detect(mimeType, text?)`.
When sharing multiple mixed-type files, per-URI MIME type is resolved via `contentResolver.getType(uri)`.

### Receiving Content — `ShareReceiverActivity`

- Declared as a share target in the manifest for all MIME types.
- Handles `ACTION_SEND` (single) and `ACTION_SEND_MULTIPLE`.
- For file URIs: copies file to `filesDir/stash/<type>/<filename>`, computes SHA-256 hash,
  checks for duplicate by hash before inserting (shows "Already in Stash" toast if duplicate),
  generates thumbnail for images (200×200 JPEG) and videos (first frame via `MediaMetadataRetriever`),
  inserts into Room, pushes to sync server if connected.
- For text: detects URL vs plain text, deduplicates URLs by content, inserts into Room, pushes to sync server.
- Assigns a UUID `syncId` on insert.

### Main List — `MainActivity`

- RecyclerView with `ListAdapter` + `DiffUtil`.
- Items grouped by date (Today / Yesterday / This Week / This Month / Older) when sorted by date.
- Pinned items shown in a separate section at the top.
- Filter chips: All, Links, Images, Videos, Audio, Documents, Text, Archives, Apps, Contacts, Other, + "📱 Local" (hides cloud-only items).
- Sort options: Date (newest first / oldest first), Title A→Z, Size (largest first).
- Search bar: filters title, content, and original filename live.
- Swipe right → delete (red, confirmation dialog). Swipe left → share (green).
- Long press → multi-select; select all, delete selected.
- Each card: type emoji or thumbnail, title/content preview, source domain or filename, date, file size, cloud badge (☁) for undownloaded cloud items, upload/download progress bar.
- Cloud-only items (not yet downloaded) appear at 50% alpha.
- Sync status indicator in toolbar (grey = disabled, amber = connecting, green = connected, red = disconnected).

### Item Detail — `ViewItemActivity`

- **Links**: URL, "Open Link", "Share".
- **Text**: inline editable (pencil in toolbar). Saving updates first-line heading (max 60 chars).
- **Images**: full preview. "Open File" + "Share".
- **Video**: centered video container. `VideoView` + `MediaController`. "Open Externally" + "Share".
- **Audio**: play/pause, seek bar, time display. `MediaPlayer`. "Open Externally".
- **Other files**: filename shown. "Open File" + "Share".
- **Cloud-only items**: "☁ Download File" button; thumbnail poster shown if available.
- FileProvider used for all sharing (authority: `<packageName>.fileprovider`).

### Delete Behavior

- **File items** when connected + have syncId:
  - "Remove everywhere": deletes local file + DB entry, sends `delete_item` to server.
  - "Remove locally only": deletes local file, sets `removedLocally=true`. Item stays visible as greyed-out ghost; re-downloading restores it.
  - "Cancel".
- **Link/Text items** when connected + have syncId:
  - "Remove": deletes DB entry, sends `delete_item` to server.
- **Any item** when disconnected / no syncId:
  - "Remove": deletes local file + DB entry.

### Sync Settings — `SyncSettingsActivity`

- Fields: hostname, port (default 9876), password (masked), TLS toggle, "Download all files" checkbox.
- Password stored as **SHA-256 hex** in SharedPreferences (never plaintext).
- QR code scanner: reads `stash://HOST:PORT?tls=1&fp=FINGERPRINT` — sets host, port, TLS, and cert fingerprint. Password is entered manually after scanning.
- "Test connection" button verifies connectivity and shows pinned TLS fingerprint.
- "Reset pinned certificate" clears the TLS fingerprint.

### Sync Client — `StashSyncClient`

Singleton attached to `StashApp`. Lifecycle: `start()` on app init, `restart()` on settings change.

**States:** `DISABLED`, `CONNECTING`, `CONNECTED`, `DISCONNECTED`.

Auto-reconnect: exponential backoff after failure.

**Initial sync on connect:**
1. Assign UUIDs to any items missing a `syncId`.
2. Send `auth` (SHA-256 hex of password + list of known `syncId`s).
3. Server sends `sync_meta` (server omits `thumbnail_b64` for items in `known_ids`).
4. Client sends its own `sync_meta`.
5. Client merges: deduplicates by file hash (keeps local syncId, deletes server duplicate);
   inserts new cloud items; applies last-writer-wins on edits by `updated_at`.
6. If file sync enabled: uploads any local files the server doesn't have (`serverHasFile=false`);
   enqueues auto-downloads for server-only files.

**Offline safety:**
- Deletes are tombstoned server-side; on reconnect the server replays all tombstones before
  the client sends its `sync_meta`, ensuring a deleted item can't be resurrected.
- Pending ops (push_item, delete_item) are stored in the `PendingOp` table and re-sent on reconnect.

**File upload (chunked):**
- Chunk size: **256 KiB** (262 144 bytes) — must match `server/src/constants.rs`.
- Bounded: **max 3 concurrent uploads** via a `kotlinx.coroutines.sync.Semaphore`.
- Before uploading, queries `query_upload_state` to resume from the last confirmed chunk.
- Chunks are base64-encoded JSON frames over the WebSocket.
- Server verifies SHA-256 on final chunk; sends `file_verify_failed` on mismatch → client re-uploads.
- Progress tracked per syncId via `LiveData<Map<String, Int>>`.

**File download (on demand + auto-sync):**
- **Max 3 concurrent downloads** via an in-flight set + `pumpDownloads()` queue.
- Sends `request_file { id, from_chunk }` to resume partial downloads.
- Server streams `file_chunk` messages; client writes chunks to a `.part` file via `RandomAccessFile`.
- `file_transfer_complete` → moves `.part` to final path, re-hashes, generates thumbnail.
- `file_unavailable` → retry later (up to 6 retries with 4 s delay; mirror may not have pulled it yet).

---

## Sync Server (Rust)

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Rust (2021 edition) |
| Runtime | Tokio (async, multi-thread) |
| WebSocket | tokio-tungstenite |
| Web UI | Axum |
| Database | SQLite via rusqlite (statically bundled) |
| Auth | **Argon2id** (stores `Argon2id(SHA-256(password))`); client sends `SHA-256(password)` |
| Rate limiting | 5 failures / 60 s per IP (shared between WS auth and web login) |
| TLS | tokio-rustls; self-signed cert via rcgen |
| Serialization | serde_json |
| Config | TOML file + CLI overrides |

### Deployment

**Docker (recommended):**
```bash
STASH_PASSWORD=yourpassword docker compose -f docker/compose.yml up -d
```
- Port 9876: sync WebSocket. Port 9877: web UI.
- Data persisted in Docker volume `stash-data`.
- `STASH_PASSWORD` auto-generates a config with Argon2id hash on first run.

**Systemd (bare metal / pre-built binary):**
```bash
stash-server set-password --password yourpassword
stash-server
```

### CLI

```
stash-server [--config stash-server.toml] [--bind 0.0.0.0:9876] [--data-dir ./data]
stash-server set-password --password <plaintext>
```

`set-password` writes `Argon2id(SHA-256(password))` to the config file.

### Auth Protocol

1. Server sends: `{"type":"challenge","nonce":"<uuid>"}` *(nonce included for forward compatibility)*
2. Client replies: `{"type":"auth","hash":"<SHA-256(password) hex>","known_ids":[...]}`
3. Server verifies `Argon2id(hash)` against stored hash; responds `auth_ok` or `auth_fail`.
   Timeout: 5 seconds. Rate limited: 5 failures / 60 s per IP.

Password is stored as an Argon2id PHC string. The SHA-256 of the plaintext is what's transmitted
(so the plaintext never crosses the wire); the server verifies against its stored Argon2id hash.

### Message Protocol (JSON over WebSocket)

**Server → Client:**

| Type | Fields | Description |
|---|---|---|
| `challenge` | `nonce` | Auth challenge |
| `auth_ok` | — | Authenticated |
| `auth_fail` | — | Wrong password / rate limited |
| `sync_meta` | `items: Item[]` | Full item list on connect |
| `item_added` | `item: Item` | Broadcast to other clients |
| `item_deleted` | `id` | Broadcast; also replayed on every connect (tombstones) |
| `file_chunk` | `id`, `chunk` (base64), `index`, `total` | File data |
| `file_transfer_complete` | `id` | All chunks delivered |
| `file_verify_failed` | `id` | SHA-256 mismatch after upload — client should re-upload |
| `upload_state` | `id`, `received` | Reply to `query_upload_state`; consecutive chunks already held |
| `file_unavailable` | `id` | Item exists but file not yet on this server |
| `error` | `message` | Error notification |

**Client → Server:**

| Type | Fields | Description |
|---|---|---|
| `auth` | `hash`, `known_ids` | SHA-256(password) hex + already-known syncIds |
| `sync_meta` | `items: Item[]` | Send local items on connect |
| `push_item` | `item: Item` | Add or update item |
| `delete_item` | `id` | Delete item + write tombstone |
| `request_file` | `id`, `from_chunk` | Request file (resume-aware) |
| `push_file_chunk` | `id`, `chunk`, `index`, `total` | Upload one 256 KiB chunk |
| `query_upload_state` | `id`, `total` | Ask how many consecutive chunks server already has |

**Item object:**

```json
{
  "id": "<syncId UUID>",
  "title": "...",
  "content": "<URL or original filename>",
  "type": "image",
  "mime_type": "image/jpeg",
  "is_pinned": false,
  "created_at": 1716840000000,
  "updated_at": 1716840000000,
  "has_file": true,
  "file_hash": "<sha256 hex>",
  "thumbnail_b64": "<base64 JPEG, optional — omitted for known_ids>"
}
```

`content` for file items carries the **original filename** (not a local path).
`updated_at` drives last-writer-wins on edits; treated as `created_at` if zero (legacy clients).

### Server Storage

- Config: `stash-server.toml` (default), overridable via `--config`.
- Data dir: `--data-dir` / config `data_dir` (default `./data`). Created on startup.
- SQLite DB: `<data_dir>/stash.db` — tables: `items`, `deleted_items` (tombstones).
- Files: `<data_dir>/files/<syncId>` (flat, no subdirectories).
- Partial uploads: `<data_dir>/uploads/<syncId>.part` + `<syncId>.count`.
- TLS certs (if auto-generated): `<data_dir>/tls/cert.pem` + `key.pem`.

### Tombstone System

Every delete goes through `db.delete_item(id)` — the single tombstone write point.

- Writes a row to `deleted_items(id TEXT PRIMARY KEY, deleted_at INTEGER)`.
- Deletes the row from `items`.
- `db.upsert_item()` checks tombstones before inserting — blocks resurrection if `deleted_at >= item.updated_at`.
- On every client connect, `session.rs` replays all tombstones as `ItemDeleted` messages **before** receiving the client's `sync_meta`. This means a deleted item can never be resurrected by a reconnecting offline client.
- Background GC: `db.prune_tombstones(30 days)` runs at startup then every 24 h.

### Server Session Handling

- Each client runs in its own Tokio task.
- Outbound messages go through an unbounded `mpsc` channel drained by a write task — session logic never blocks on WebSocket I/O.
- File uploads: chunks written to a `.part` file at the correct byte offset; re-hash on completion; `has_file` set in DB and broadcast to other clients.
- File downloads: streamed from disk; supports resume via `from_chunk`.
- Upload resume: `query_upload_state` → server counts consecutive chunks from index 0 (based on `.count` file).
- Broadcast: item add/delete/file events sent to all OTHER connected clients via `ClientRegistry`.

### Web UI

Served by Axum on a separate port (default `127.0.0.1:9877`, or `0.0.0.0:9877` in Docker).

- Browse and download all items.
- Live updates via SSE (Server-Sent Events).
- Settings editor: bind address, password, TLS config, mirror config.
- One-click server restart (applies startup-time settings).
- Displays TLS fingerprint and mirror connection status.
- Login protected by the same password (SHA-256 + Argon2id verify); session cookie signed with HMAC-SHA256 (7-day TTL, `SameSite=Lax`).

### TLS

Enabled via `[tls] enabled = true` in config or the web UI.

- Self-signed certificate auto-generated under `<data_dir>/tls/` if no `cert_path`/`key_path` given.
- SHA-256 fingerprint logged on startup, shown in web UI and `/api/status`.
- Clients pin the fingerprint trust-on-first-use; any cert change is refused (MITM protection).
- Mirror connections use a per-connection pinning TLS connector if a fingerprint is configured.

### Mirror (Server-to-Server Sync)

Optional peer sync configured in `[mirror]`:

```toml
[mirror]
host = "192.168.1.100:9876"
password_hash = "<SHA-256 hex of peer's password>"
tls = true
fingerprint = "AA:BB:..."  # optional — pin peer's cert
```

`password_hash` is the SHA-256 hex of the peer's password (same value that the Android/iOS client
stores; the mirror sends it directly as the `auth.hash`).

- Reconnects on disconnect with exponential backoff (1 s → 64 s cap).
- Bidirectional initial sync on connect; live events forwarded in both directions.
- Loop prevention: events received FROM the peer are stored directly, not re-forwarded through the mirror channel. Only events from local client sessions go through `mirror_tx`.
- Files propagate reactively: when `has_file` becomes true locally, the mirror uploads to the peer.

---

## iOS App

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Swift |
| UI | SwiftUI |
| Sync | URLSessionWebSocketTask |
| Storage | `Codable` JSON + flat files on disk |
| Crypto | CryptoKit (SHA-256) |
| TLS pinning | `URLSessionDelegate` (`TLSPinningDelegate`) |

### Auth

Same protocol as Android: sends `SHA-256(password)` as hex in the `auth` message.
No HMAC. `StashCrypto.passwordHash()` computes the SHA-256.

### Storage

- Items: `Documents/stash_items.json` (Codable array, re-written on each change).
- Files: `Documents/stash_files/<syncId>`.

---

## FileProvider Configuration (Android)

Paths exposed (`res/xml/file_paths.xml`):
- `filesDir/stash/` — locally saved files
- `filesDir/stash_files/` — files downloaded from sync server
- Cache dir (`.`) — for sharing temp files

---

## Key Design Decisions

- **No cloud**: server is self-hosted. Data never leaves the local network.
- **Ghost state**: "Remove locally only" keeps the item in the DB as a cloud reference (`removedLocally=true`, greyed out). Re-downloading restores it. Links and text items cannot be ghosted — they are always deleted everywhere.
- **File deduplication**: if the same file already exists in Stash (by SHA-256 hash), the incoming share is rejected with "Already in Stash" rather than duplicated.
- **Sync deduplication**: if server and client hold the same file under different syncIds (detected by `file_hash`), the client keeps its own syncId and sends `delete_item` for the server's duplicate. One canonical copy, clean history.
- **Thumbnail sync**: compressed 80×80 JPEG thumbnails (≈1–3 KB) are included in sync metadata so cloud-only items show a preview without downloading the full file. Omitted for `known_ids` to save bandwidth on reconnect.
- **Password security**: password is never stored or transmitted in plaintext. SHA-256(password) is what leaves the device; the server stores `Argon2id(SHA-256(password))`. Per-IP rate limiting (5 failures / 60 s) limits brute-force attempts.
- **Tombstones**: deletes are durable — a tombstone row is written before the item is removed. On every reconnect the server replays tombstones to the client before accepting any sync data, making it impossible for an offline device to resurrect a deleted item.
- **Chunked transfer with resume**: both uploads and downloads support resuming mid-transfer. The server tracks consecutive received chunks per upload; clients query before uploading. Downloads resume from the last delivered chunk index.
- **Bounded concurrency**: upload and download pipelines each cap at 3 concurrent transfers to keep the WebSocket from saturating and progress bars from jumping.
- **File sync is opt-in**: the user can sync metadata only (fast) or metadata + files (full backup).
