use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMsg {
    Challenge {
        nonce: String,
    },
    AuthOk,
    AuthFail,
    SyncMeta {
        items: Vec<Item>,
    },
    ItemAdded {
        item: Item,
    },
    ItemDeleted {
        id: String,
    },
    FileChunk {
        id: String,
        chunk: String,
        index: u32,
        total: u32,
    },
    FileTransferComplete {
        id: String,
    },
    /// Sent when a fully-received file's SHA-256 does not match the item's recorded
    /// file_hash. The server discards the file; the client should re-upload.
    FileVerifyFailed {
        id: String,
    },
    /// How many consecutive chunks (from index 0) the server already has for an upload.
    UploadState {
        id: String,
        received: u32,
    },
    /// The item exists but its file isn't on this server yet (e.g. a mirror hasn't pulled it).
    /// Transient + keyed to the id so the client can retry just that download.
    FileUnavailable {
        id: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMsg {
    /// hash: SHA-256 hex of the password. known_ids: item syncIds the client already has
    /// (server omits thumbnail_b64 for these in the SyncMeta response).
    Auth {
        hash: String,
        #[serde(default)]
        known_ids: Vec<String>,
    },
    SyncMeta {
        items: Vec<Item>,
    },
    PushItem {
        item: Item,
    },
    DeleteItem {
        id: String,
    },
    /// from_chunk: resume download from this chunk index (0 = full download).
    RequestFile {
        id: String,
        #[serde(default)]
        from_chunk: u32,
    },
    PushFileChunk {
        id: String,
        chunk: String,
        index: u32,
        total: u32,
    },
    /// Ask how many consecutive chunks the server already has for a pending upload.
    /// `total` is the uploader's chunk count; if it doesn't match the server's saved
    /// partial (e.g. the chunk size changed between versions), the server restarts from 0.
    QueryUploadState {
        id: String,
        #[serde(default)]
        total: u32,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Item {
    pub id: String,
    pub title: String,
    pub content: String,
    #[serde(rename = "type")]
    pub item_type: String,
    pub mime_type: String,
    pub is_pinned: bool,
    pub created_at: i64,
    /// Last-modification timestamp (epoch millis). Drives last-writer-wins on edits.
    /// Defaults to 0 for older clients; treated as created_at in that case.
    #[serde(default)]
    pub updated_at: i64,
    pub has_file: bool,
    #[serde(default)]
    pub file_hash: String,
    #[serde(default)]
    pub thumbnail_b64: String,
}

impl Item {
    /// Effective modification time — falls back to created_at for clients that
    /// don't send updated_at yet.
    pub fn effective_updated_at(&self) -> i64 {
        self.updated_at.max(self.created_at)
    }
}
