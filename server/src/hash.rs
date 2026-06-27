use std::path::Path;

use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;

/// Compute the lowercase SHA-256 hex digest of a file's contents, streaming it so
/// large files don't blow up memory. Returns None on any I/O error.
pub async fn file_sha256(path: &Path) -> Option<String> {
    let mut file = tokio::fs::File::open(path).await.ok()?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).await.ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex::encode(hasher.finalize()))
}

/// Compute the lowercase SHA-256 hex digest of an in-memory byte buffer.
/// Used for files uploaded via the web UI, which are read fully into memory.
pub fn bytes_sha256(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}
