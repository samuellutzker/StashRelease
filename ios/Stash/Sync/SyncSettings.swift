import Foundation

/// Connection configuration. Stores the password HASH (never the password) so the raw
/// secret isn't persisted. For production, move `passwordHash` into the Keychain.
struct SyncSettings {
    private static let d = UserDefaults.standard

    static var host: String {
        get { d.string(forKey: "stash.host") ?? "" }
        set { d.set(newValue, forKey: "stash.host") }
    }
    static var port: Int {
        get { let p = d.integer(forKey: "stash.port"); return p == 0 ? 9876 : p }
        set { d.set(newValue, forKey: "stash.port") }
    }
    static var passwordHash: String {
        get { d.string(forKey: "stash.pwhash") ?? "" }
        set { d.set(newValue, forKey: "stash.pwhash") }
    }

    /// Connect over TLS (wss://). The server must have `[tls] enabled = true`.
    static var useTls: Bool {
        get { d.bool(forKey: "stash.tls") }
        set {
            d.set(newValue, forKey: "stash.tls")
            if !newValue { certFingerprint = "" }   // pin only meaningful while TLS is on
        }
    }

    /// Pinned server-certificate SHA-256 (uppercase colon-separated), or "" until trust-on-first-use.
    static var certFingerprint: String {
        get { d.string(forKey: "stash.certfp") ?? "" }
        set { d.set(newValue, forKey: "stash.certfp") }
    }

    static var autoDownload: Bool {
        get { d.bool(forKey: "stash.autodownload") }
        set { d.set(newValue, forKey: "stash.autodownload") }
    }

    /// Whether sync is active. Disabled by default (matches Android).
    static var syncEnabled: Bool {
        get { d.object(forKey: "stash.syncenabled") as? Bool ?? false }
        set { d.set(newValue, forKey: "stash.syncenabled") }
    }

    static func setPassword(_ password: String) {
        passwordHash = StashCrypto.passwordHash(password)
    }

    static var isConfigured: Bool {
        !host.isEmpty && !passwordHash.isEmpty
    }

    static var webSocketURL: URL? {
        guard !host.isEmpty else { return nil }
        let scheme = useTls ? "wss" : "ws"
        return URL(string: "\(scheme)://\(host):\(port)")
    }
}
