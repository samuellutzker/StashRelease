use std::collections::HashMap;
use std::io::{Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::Mutex;

use crate::constants::CHUNK_SIZE;

struct PartialUpload {
    received_bits: Vec<bool>,
    part_path: PathBuf,
    count_path: PathBuf,
}

impl PartialUpload {
    fn consecutive_received(&self) -> u32 {
        self.received_bits.iter().take_while(|&&b| b).count() as u32
    }

    fn is_complete(&self) -> bool {
        self.received_bits.iter().all(|&b| b)
    }
}

/// Shared upload state that persists partial uploads across client reconnects
/// and (via disk) across server restarts.
pub struct UploadRegistry {
    uploads_dir: PathBuf,
    uploads: Mutex<HashMap<String, PartialUpload>>,
}

impl UploadRegistry {
    pub fn new(data_dir: &str) -> Self {
        let uploads_dir = PathBuf::from(data_dir).join("uploads");
        std::fs::create_dir_all(&uploads_dir).ok();
        Self {
            uploads_dir,
            uploads: Mutex::new(HashMap::new()),
        }
    }

    /// How many consecutive chunks (from index 0) the server already has for this upload.
    /// Checked in-memory first, then falls back to the on-disk count file.
    ///
    /// `total` is the uploader's expected chunk count. When it doesn't match the saved
    /// partial's layout (chunk size changed between versions), 0 is returned so the upload
    /// restarts cleanly instead of resuming at a byte offset that no longer lines up.
    /// `total == 0` means "unspecified" and preserves the legacy resume-always behavior.
    pub fn query_state(&self, id: &str, total: u32) -> u32 {
        let uploads = self.uploads.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = uploads.get(id) {
            if total == 0 || entry.received_bits.len() as u32 == total {
                return entry.consecutive_received();
            }
            return 0;
        }
        drop(uploads);
        let (stored_total, consecutive) = self.read_count_from_disk(id);
        if total == 0 || stored_total == total {
            consecutive
        } else {
            0
        }
    }

    /// Record an incoming chunk. Returns `Some(part_path)` when all chunks for this
    /// upload are complete; `None` otherwise. The caller should rename `part_path`
    /// to its final destination and then call `cleanup_meta`.
    pub fn receive_chunk(
        &self,
        id: &str,
        data: Vec<u8>,
        index: u32,
        total: u32,
    ) -> Option<PathBuf> {
        let mut uploads = self.uploads.lock().unwrap_or_else(|e| e.into_inner());
        let uploads_dir = self.uploads_dir.clone();

        // Drop a stale in-memory partial whose chunk layout differs from this upload
        // (chunk size changed), and clear its now-misaligned on-disk data.
        if let Some(e) = uploads.get(id) {
            if e.received_bits.len() as u32 != total {
                uploads.remove(id);
                let _ = std::fs::remove_file(uploads_dir.join(format!("{}.part", id)));
                let _ = std::fs::remove_file(uploads_dir.join(format!("{}.count", id)));
            }
        }

        let entry = uploads.entry(id.to_string()).or_insert_with(|| {
            let part_path = uploads_dir.join(format!("{}.part", id));
            let count_path = uploads_dir.join(format!("{}.count", id));
            let (stored_total, stored_consecutive) = read_count(&count_path);
            // Only resume from disk if the saved layout matches this upload's chunk count.
            let existing = if stored_total == total {
                stored_consecutive.min(total)
            } else {
                0
            };
            let mut bits = vec![false; total as usize];
            bits[..existing as usize].fill(true);
            PartialUpload {
                received_bits: bits,
                part_path,
                count_path,
            }
        });

        let idx = index as usize;
        if idx >= entry.received_bits.len() {
            return None;
        }

        // Write chunk at the correct byte offset so the file can be assembled
        // regardless of whether this is a fresh or resumed upload.
        let offset = (index as u64) * CHUNK_SIZE;
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(&entry.part_path)
        {
            if f.seek(SeekFrom::Start(offset)).is_ok() {
                let _ = f.write_all(&data);
            }
        }

        entry.received_bits[idx] = true;

        // Persist "total consecutive" so the server can answer QueryUploadState after a
        // restart AND detect a chunk-size/layout change (total mismatch → restart).
        let consecutive = entry.consecutive_received();
        let total_chunks = entry.received_bits.len() as u32;
        let _ = std::fs::write(
            &entry.count_path,
            format!("{} {}", total_chunks, consecutive),
        );

        if entry.is_complete() {
            let part_path = entry.part_path.clone();
            uploads.remove(id);
            Some(part_path)
        } else {
            None
        }
    }

    /// Delete the count file after the caller has renamed the part file.
    pub fn cleanup_meta(&self, id: &str) {
        let _ = std::fs::remove_file(self.uploads_dir.join(format!("{}.count", id)));
    }

    /// Remove all on-disk and in-memory state for this upload (e.g., on error).
    pub fn cleanup(&self, id: &str) {
        let mut uploads = self.uploads.lock().unwrap_or_else(|e| e.into_inner());
        uploads.remove(id);
        let _ = std::fs::remove_file(self.uploads_dir.join(format!("{}.part", id)));
        let _ = std::fs::remove_file(self.uploads_dir.join(format!("{}.count", id)));
    }

    fn read_count_from_disk(&self, id: &str) -> (u32, u32) {
        let count_path = self.uploads_dir.join(format!("{}.count", id));
        read_count(&count_path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    /// A unique scratch directory that removes itself on drop (no extra dev-dependency).
    struct TempDir(PathBuf);
    impl TempDir {
        fn new() -> Self {
            static N: AtomicU64 = AtomicU64::new(0);
            let n = N.fetch_add(1, Ordering::Relaxed);
            let p = std::env::temp_dir().join(format!(
                "stash-upload-test-{}-{}-{}",
                std::process::id(),
                n,
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            std::fs::create_dir_all(&p).unwrap();
            TempDir(p)
        }
        fn path(&self) -> &str {
            self.0.to_str().unwrap()
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn chunk(n: usize) -> Vec<u8> {
        vec![0u8; n]
    }

    #[test]
    fn in_order_chunks_complete_and_return_path() {
        let tmp = TempDir::new();
        let reg = UploadRegistry::new(tmp.path());
        // total = 2 chunks; second chunk is a short tail.
        assert!(reg
            .receive_chunk("x", chunk(CHUNK_SIZE as usize), 0, 2)
            .is_none());
        let done = reg.receive_chunk("x", chunk(10), 1, 2);
        assert!(done.is_some(), "should complete after final chunk");
        assert!(done.unwrap().ends_with("x.part"));
    }

    #[test]
    fn out_of_order_chunks_complete() {
        let tmp = TempDir::new();
        let reg = UploadRegistry::new(tmp.path());
        // Receive the last chunk first, then the first — completion only on full coverage.
        assert!(reg.receive_chunk("x", chunk(10), 1, 2).is_none());
        // After only chunk 1, no consecutive chunks from index 0 yet.
        assert_eq!(reg.query_state("x", 2), 0);
        let done = reg.receive_chunk("x", chunk(CHUNK_SIZE as usize), 0, 2);
        assert!(done.is_some());
    }

    #[test]
    fn query_state_counts_consecutive_from_zero() {
        let tmp = TempDir::new();
        let reg = UploadRegistry::new(tmp.path());
        reg.receive_chunk("x", chunk(CHUNK_SIZE as usize), 0, 3);
        assert_eq!(reg.query_state("x", 3), 1);
        reg.receive_chunk("x", chunk(CHUNK_SIZE as usize), 1, 3);
        assert_eq!(reg.query_state("x", 3), 2);
        // A gap (chunk 1 missing) yields no resumable consecutive run beyond it.
        let tmp2 = TempDir::new();
        let reg2 = UploadRegistry::new(tmp2.path());
        reg2.receive_chunk("y", chunk(CHUNK_SIZE as usize), 0, 3);
        reg2.receive_chunk("y", chunk(CHUNK_SIZE as usize), 2, 3);
        assert_eq!(reg2.query_state("y", 3), 1);
    }

    #[test]
    fn query_state_total_mismatch_restarts() {
        let tmp = TempDir::new();
        let reg = UploadRegistry::new(tmp.path());
        reg.receive_chunk("x", chunk(CHUNK_SIZE as usize), 0, 3);
        // Matching total resumes; a different total (chunk-size change) restarts from 0.
        assert_eq!(reg.query_state("x", 3), 1);
        assert_eq!(reg.query_state("x", 5), 0);
        // total == 0 means "unspecified" and preserves legacy resume-always behavior.
        assert_eq!(reg.query_state("x", 0), 1);
    }

    #[test]
    fn query_state_resumes_from_disk_after_restart() {
        let tmp = TempDir::new();
        // First registry receives a chunk and persists the .count file...
        {
            let reg = UploadRegistry::new(tmp.path());
            reg.receive_chunk("x", chunk(CHUNK_SIZE as usize), 0, 2);
        }
        // ...a fresh registry (simulating a server restart) reads it back from disk.
        let reg2 = UploadRegistry::new(tmp.path());
        assert_eq!(reg2.query_state("x", 2), 1);
        // Layout mismatch still forces a restart even from disk state.
        assert_eq!(reg2.query_state("x", 9), 0);
    }

    #[test]
    fn unknown_id_query_state_is_zero() {
        let tmp = TempDir::new();
        let reg = UploadRegistry::new(tmp.path());
        assert_eq!(reg.query_state("nope", 4), 0);
    }
}

/// Parse a `.count` file into `(total_chunks, consecutive_received)`.
/// New format is "total consecutive"; a legacy single value is treated as an
/// unknown-layout consecutive count `(0, n)` so it won't be trusted across a chunk-size change.
fn read_count(path: &std::path::Path) -> (u32, u32) {
    let s = match std::fs::read_to_string(path) {
        Ok(s) => s,
        Err(_) => return (0, 0),
    };
    let mut it = s.split_whitespace();
    let first = it.next().and_then(|x| x.parse::<u32>().ok());
    let second = it.next().and_then(|x| x.parse::<u32>().ok());
    match (first, second) {
        (Some(total), Some(consecutive)) => (total, consecutive),
        (Some(consecutive), None) => (0, consecutive),
        _ => (0, 0),
    }
}
