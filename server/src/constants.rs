/// Chunk size for file transfers (bytes). Must match the Android client's CHUNK_SIZE constant.
/// 256 KiB keeps per-byte message/JSON/base64 overhead low for large media transfers.
pub const CHUNK_SIZE: u64 = 262144;
