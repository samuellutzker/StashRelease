import Foundation

/// Wire representation of an item, matching the server's JSON exactly
/// (Rust `protocol::Item` / Android `SyncItem`). All keys are snake_case.
struct SyncItem: Codable, Identifiable, Hashable {
    let id: String          // syncId (UUID string)
    var title: String
    var content: String     // URL/text for link/text; originalFilename for files
    var type: String        // "link" | "text" | "image" | "video" | "audio" | "file" | ...
    var mimeType: String
    var isPinned: Bool
    var createdAt: Int64     // epoch millis
    var updatedAt: Int64     // epoch millis; last-writer-wins for edits
    var hasFile: Bool        // true only when the server actually stores the file
    var fileHash: String     // SHA-256 hex of file content (for dedup + integrity)
    var thumbnailB64: String // optional compressed JPEG thumbnail (base64)

    enum CodingKeys: String, CodingKey {
        case id, title, content, type
        case mimeType = "mime_type"
        case isPinned = "is_pinned"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case hasFile = "has_file"
        case fileHash = "file_hash"
        case thumbnailB64 = "thumbnail_b64"
    }

    init(
        id: String,
        title: String,
        content: String,
        type: String,
        mimeType: String = "",
        isPinned: Bool = false,
        createdAt: Int64,
        updatedAt: Int64,
        hasFile: Bool = false,
        fileHash: String = "",
        thumbnailB64: String = ""
    ) {
        self.id = id
        self.title = title
        self.content = content
        self.type = type
        self.mimeType = mimeType
        self.isPinned = isPinned
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.hasFile = hasFile
        self.fileHash = fileHash
        self.thumbnailB64 = thumbnailB64
    }

    // Tolerate older servers/clients that omit some fields (mirrors serde/optXxx defaults).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        content = (try? c.decode(String.self, forKey: .content)) ?? ""
        type = (try? c.decode(String.self, forKey: .type)) ?? "other"
        mimeType = (try? c.decode(String.self, forKey: .mimeType)) ?? ""
        isPinned = (try? c.decode(Bool.self, forKey: .isPinned)) ?? false
        createdAt = (try? c.decode(Int64.self, forKey: .createdAt)) ?? 0
        updatedAt = (try? c.decode(Int64.self, forKey: .updatedAt)) ?? 0
        hasFile = (try? c.decode(Bool.self, forKey: .hasFile)) ?? false
        fileHash = (try? c.decode(String.self, forKey: .fileHash)) ?? ""
        thumbnailB64 = (try? c.decode(String.self, forKey: .thumbnailB64)) ?? ""
    }

    /// Effective modification time; falls back to createdAt for items without updatedAt.
    var effectiveUpdatedAt: Int64 { max(updatedAt, createdAt) }

    var isLinkOrText: Bool { type == "link" || type == "text" }
}
