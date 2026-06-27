# Stash for iOS

A native SwiftUI client for the Stash sync server — the same WebSocket protocol the Android
app and Rust server use (`sync_meta` reconciliation, chunked file transfer with resume +
SHA-256 integrity verification, TLS with certificate pinning).

> **Status:** Working on device. The protocol layer, crypto/auth, local store, sync client, and
> SwiftUI UI are implemented and tested on a real iPhone. Build in Xcode.

## What's here

```
ios/Stash/
  StashApp.swift            @main app + AppModel (owns store + sync client)
  Model/
    SyncItem.swift          Codable item, exact server JSON keys (snake_case)
    Protocol.swift          ClientMessage / ServerMessage (tagged "type" enums)
    StashCrypto.swift       HMAC-SHA256 auth, SHA-256 file hashing (CryptoKit)
    StashStore.swift        Local persistence (Codable JSON + files on disk by syncId)
  Sync/
    SyncSettings.swift      host / port / password-hash / TLS (UserDefaults)
    SyncClient.swift        URLSessionWebSocketTask client — the core
    TLSPinningDelegate.swift  pins the server's self-signed cert (trust-on-first-use)
  Views/
    ContentView.swift       item list
    ItemDetailView.swift    detail, edit (text), pin, delete, download/share
    AddItemView.swift       add a note/link
    SettingsView.swift      server config
```

## Creating the Xcode project

1. Xcode → New → App. Interface **SwiftUI**, Language **Swift**, deployment target **iOS 16+**.
2. Delete the template's generated `ContentView.swift` and `<Name>App.swift` (this project provides
   its own `@main StashApp`). Add all files under `ios/Stash/` to the target (drag in, "Create
   groups").
3. Build settings: Swift 5 language mode is fine.

### Required Info.plist entries (the app will NOT connect without these)

App Transport Security and the Local Network privacy prompt both apply:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsArbitraryLoads</key>
    <true/>
</dict>
<key>NSLocalNetworkUsageDescription</key>
<string>Stash connects to your sync server on the local network.</string>
<key>NSCameraUsageDescription</key>
<string>Stash uses the camera to scan the server's QR code.</string>
```

`NSAllowsArbitraryLoads` is needed for both plain `ws://` and `wss://` with a self-signed
certificate (which isn't from a public CA, so standard ATS would reject it).

## How it maps to the server

- JSON keys and message `type` values match `server/src/protocol.rs` exactly.
- `CHUNK_SIZE` is **262144 (256 KiB)** and must stay equal to the server constant.
- Auth: the client sends `SHA-256(password)` as a hex string in the `Auth` message — no HMAC,
  no challenge nonce needed. The server verifies against its stored Argon2id hash.
- Integrity: uploads are verified server-side; the client also re-hashes downloads on arrival and
  discards a mismatch. A server `file_verify_failed` triggers one upload retry.
- Edits use `updated_at` last-writer-wins, same as the other clients.

## Known limitations / next steps (roughly in priority order)

1. **Share extension** — to stash from the iOS share sheet. Needs a separate extension target + an
   **App Group** so the extension and app share the store/files. (Android's share receiver is the
   equivalent.)
2. **Background upload** — iOS can't hold a WebSocket open in the background like Android. Use a
   **background `URLSession`** (HTTP upload endpoint) or **BGTaskScheduler** to finish uploads after
   the app is backgrounded. This likely wants a small HTTP upload route on the server.
3. **Thumbnails** — not generated yet (`thumbnail_b64` sent empty); the server keeps existing ones.
4. **Keychain** for the password hash instead of UserDefaults.
5. **GRDB/Core Data** instead of a JSON file once the library grows.
6. **Move chunk disk I/O off the main actor** for very large transfers (see note in `SyncClient`).
7. **UI parity with Android** — type filter chips, type colours/icons, "remove everywhere /
   remove locally" delete options, double-tap primary action.

## TLS (done)

`wss://` is supported. In Settings, toggle **Use TLS (wss://)**. The server must have
`[tls] enabled = true`. There's no CA for the self-signed cert, so the client pins it by SHA-256
fingerprint on first connect (`TLSPinningDelegate`); any later cert change is rejected. Verify the
pinned fingerprint (shown in Settings) against the one the server logs on startup. Reset the pin
from Settings if the server's cert legitimately changes.

> ATS note: `NSAllowsArbitraryLoads` covers both `ws://` and `wss://` to a LAN IP with a
> self-signed cert. Keep the Info.plist keys above regardless of TLS mode.
