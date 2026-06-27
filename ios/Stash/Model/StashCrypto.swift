import Foundation
import CryptoKit

/// Cryptographic helpers matching the server (Rust `auth.rs`) and Android client.
enum StashCrypto {

    /// SHA-256 hex of a password. This is what is stored locally and sent to the server
    /// during WS auth; the server verifies it against its stored Argon2id hash.
    static func passwordHash(_ password: String) -> String {
        let digest = SHA256.hash(data: Data(password.utf8))
        return hex(digest)
    }

    /// Streamed SHA-256 hex of a file's contents. Returns nil on I/O error.
    static func fileSHA256(at url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = (try? handle.read(upToCount: 1 << 18)) ?? nil  // 256 KiB
            guard let chunk, !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hex(hasher.finalize())
    }

    /// Uppercase colon-separated SHA-256 of a certificate's DER bytes — the value we pin, in the
    /// same format the server logs / the web UI shows.
    static func certFingerprint(_ der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    // MARK: - Hex helpers

    static func hex<D: Sequence>(_ bytes: D) -> String where D.Element == UInt8 {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func dataFromHex(_ s: String) -> Data? {
        let chars = Array(s)
        guard chars.count % 2 == 0 else { return nil }
        var data = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let byte = UInt8(String(chars[i ... i + 1]), radix: 16) else { return nil }
            data.append(byte)
            i += 2
        }
        return data
    }
}
