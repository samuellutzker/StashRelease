import Foundation

/// Messages received from the server. Mirrors Rust `protocol::ServerMsg`
/// (serde tagged enum, `type` discriminator, snake_case).
enum ServerMessage {
    case challenge(nonce: String)
    case authOk
    case authFail
    case syncMeta(items: [SyncItem])
    case itemAdded(item: SyncItem)
    case itemDeleted(id: String)
    case fileChunk(id: String, chunk: String, index: Int, total: Int)
    case fileTransferComplete(id: String)
    case fileVerifyFailed(id: String)
    case uploadState(id: String, received: Int)
    case fileUnavailable(id: String)
    case error(message: String)
    case unknown

    static func parse(_ text: String) -> ServerMessage {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String
        else { return .unknown }

        func str(_ k: String) -> String { obj[k] as? String ?? "" }
        func int(_ k: String) -> Int { (obj[k] as? NSNumber)?.intValue ?? 0 }

        switch type {
        case "challenge": return .challenge(nonce: str("nonce"))
        case "auth_ok": return .authOk
        case "auth_fail": return .authFail
        case "sync_meta":
            let items = decodeItems(obj["items"])
            return .syncMeta(items: items)
        case "item_added":
            guard let item = decodeItem(obj["item"]) else { return .unknown }
            return .itemAdded(item: item)
        case "item_deleted": return .itemDeleted(id: str("id"))
        case "file_chunk":
            return .fileChunk(id: str("id"), chunk: str("chunk"), index: int("index"), total: int("total"))
        case "file_transfer_complete": return .fileTransferComplete(id: str("id"))
        case "file_verify_failed": return .fileVerifyFailed(id: str("id"))
        case "upload_state": return .uploadState(id: str("id"), received: int("received"))
        case "file_unavailable": return .fileUnavailable(id: str("id"))
        case "error": return .error(message: str("message"))
        default: return .unknown
        }
    }

    private static func decodeItem(_ any: Any?) -> SyncItem? {
        guard let dict = any,
              let data = try? JSONSerialization.data(withJSONObject: dict) else { return nil }
        return try? JSONDecoder().decode(SyncItem.self, from: data)
    }

    private static func decodeItems(_ any: Any?) -> [SyncItem] {
        guard let arr = any,
              let data = try? JSONSerialization.data(withJSONObject: arr) else { return [] }
        return (try? JSONDecoder().decode([SyncItem].self, from: data)) ?? []
    }
}

/// Messages sent to the server. Mirrors Rust `protocol::ClientMsg`.
enum ClientMessage {
    case auth(hash: String, knownIds: [String])
    case syncMeta(items: [SyncItem])
    case pushItem(item: SyncItem)
    case deleteItem(id: String)
    case requestFile(id: String, fromChunk: Int)
    case pushFileChunk(id: String, chunk: String, index: Int, total: Int)
    case queryUploadState(id: String, total: Int)

    /// Serialize to the JSON text frame the server expects.
    func json() -> String {
        var obj: [String: Any]
        switch self {
        case let .auth(hash, knownIds):
            obj = ["type": "auth", "hash": hash]
            if !knownIds.isEmpty { obj["known_ids"] = knownIds }
        case let .syncMeta(items):
            obj = ["type": "sync_meta", "items": items.map { $0.asDictionary() }]
        case let .pushItem(item):
            obj = ["type": "push_item", "item": item.asDictionary()]
        case let .deleteItem(id):
            obj = ["type": "delete_item", "id": id]
        case let .requestFile(id, fromChunk):
            obj = ["type": "request_file", "id": id]
            if fromChunk > 0 { obj["from_chunk"] = fromChunk }
        case let .pushFileChunk(id, chunk, index, total):
            obj = ["type": "push_file_chunk", "id": id, "chunk": chunk, "index": index, "total": total]
        case let .queryUploadState(id, total):
            obj = ["type": "query_upload_state", "id": id, "total": total]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }
}

private extension SyncItem {
    /// Encode to a `[String: Any]` with the exact snake_case keys the server reads.
    func asDictionary() -> [String: Any] {
        var d: [String: Any] = [
            "id": id,
            "title": title,
            "content": content,
            "type": type,
            "mime_type": mimeType,
            "is_pinned": isPinned,
            "created_at": createdAt,
            "updated_at": updatedAt,
            "has_file": hasFile,
            "file_hash": fileHash,
        ]
        if !thumbnailB64.isEmpty { d["thumbnail_b64"] = thumbnailB64 }
        return d
    }
}
