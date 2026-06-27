use crate::protocol::Item;
use rusqlite::{params, Connection, Result};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// Current Unix time in milliseconds. Used as the `deleted_at` timestamp for tombstones.
fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

/// Outcome of an upsert, so callers know whether to broadcast/forward the change.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpsertResult {
    /// Row did not exist and was created.
    Inserted,
    /// Row existed and was updated because the incoming item was newer.
    Updated,
    /// Row existed and was not newer — nothing changed (drop it; breaks mirror loops).
    Unchanged,
}

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    pub fn open(path: &str) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS items (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                item_type TEXT NOT NULL,
                mime_type TEXT NOT NULL DEFAULT '',
                is_pinned INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                has_file INTEGER NOT NULL DEFAULT 0,
                file_hash TEXT NOT NULL DEFAULT '',
                thumbnail_b64 TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS deleted_items (
                id TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL
            );
        ",
        )?;
        // Migrations for existing DBs — ignore "duplicate column name" errors only.
        fn ignore_dup_column(r: rusqlite::Result<usize>) -> rusqlite::Result<()> {
            match r {
                Ok(_) => Ok(()),
                Err(rusqlite::Error::SqliteFailure(e, Some(ref msg)))
                    if e.code == rusqlite::ErrorCode::Unknown
                        && msg.contains("duplicate column name") =>
                {
                    Ok(())
                }
                Err(e) => Err(e),
            }
        }
        ignore_dup_column(conn.execute(
            "ALTER TABLE items ADD COLUMN file_hash TEXT NOT NULL DEFAULT ''",
            [],
        ))?;
        ignore_dup_column(conn.execute(
            "ALTER TABLE items ADD COLUMN thumbnail_b64 TEXT NOT NULL DEFAULT ''",
            [],
        ))?;
        ignore_dup_column(conn.execute(
            "ALTER TABLE items ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            [],
        ))?;
        // Backfill updated_at for rows migrated from older schema versions.
        let _ = conn.execute(
            "UPDATE items SET updated_at = created_at WHERE updated_at = 0",
            [],
        );
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn list_items(&self) -> Result<Vec<Item>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(
            "SELECT id, title, content, item_type, mime_type, is_pinned, created_at, has_file, file_hash, thumbnail_b64, updated_at
             FROM items ORDER BY created_at DESC"
        )?;
        let items = stmt
            .query_map([], |row| {
                Ok(Item {
                    id: row.get(0)?,
                    title: row.get(1)?,
                    content: row.get(2)?,
                    item_type: row.get(3)?,
                    mime_type: row.get(4)?,
                    is_pinned: row.get::<_, i64>(5)? != 0,
                    created_at: row.get(6)?,
                    has_file: row.get::<_, i64>(7)? != 0,
                    file_hash: row.get(8)?,
                    thumbnail_b64: row.get(9)?,
                    updated_at: row.get(10)?,
                })
            })?
            .collect::<Result<Vec<_>>>()?;
        Ok(items)
    }

    /// Insert or update an item, all within a single mutex lock.
    ///
    /// Returns:
    /// - `Inserted` when the row did not exist and was created.
    /// - `Updated` when the row existed and the incoming item is newer (by updated_at),
    ///   so mutable metadata was overwritten.
    /// - `Unchanged` when the row existed and was not newer — nothing was written.
    ///
    /// Never touches `has_file` (file presence is server-authoritative) or `thumbnail_b64`
    /// (managed separately via `update_thumbnail`). Returning `Unchanged` for already-current
    /// items is what breaks mirror forwarding loops: a re-delivered edit carries an equal
    /// updated_at, so it terminates instead of bouncing back.
    pub fn upsert_item(&self, item: &Item) -> Result<UpsertResult> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let incoming_updated = item.effective_updated_at();
        conn.execute("BEGIN", [])?;
        let result = (|| -> Result<UpsertResult> {
            // Tombstone check: a prior delete must beat a stale re-sync of the same item.
            // This is what prevents the "resurrection" bug — an offline client that still
            // holds a deleted item re-sends it on reconnect, and without this guard the
            // INSERT OR IGNORE below would re-create it.
            let tombstone_at: Option<i64> = conn
                .query_row(
                    "SELECT deleted_at FROM deleted_items WHERE id = ?1",
                    params![item.id],
                    |r| r.get(0),
                )
                .ok();
            if let Some(deleted_at) = tombstone_at {
                if deleted_at >= incoming_updated {
                    // Deletion wins — the item was not re-created after the delete.
                    return Ok(UpsertResult::Unchanged);
                }
                // The incoming item is strictly newer than the deletion, so it was
                // genuinely re-created after the delete. Drop the tombstone and proceed.
                conn.execute("DELETE FROM deleted_items WHERE id = ?1", params![item.id])?;
            }
            let inserted = conn.execute(
                "INSERT OR IGNORE INTO items
                 (id, title, content, item_type, mime_type, is_pinned, created_at, has_file, file_hash, thumbnail_b64, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                params![
                    item.id, item.title, item.content, item.item_type,
                    item.mime_type, item.is_pinned as i64, item.created_at, item.has_file as i64,
                    item.file_hash, item.thumbnail_b64, incoming_updated
                ],
            )?;
            if inserted > 0 {
                return Ok(UpsertResult::Inserted);
            }
            // Row exists — apply the update only if the incoming item is strictly newer.
            let stored_updated: i64 = conn.query_row(
                "SELECT updated_at FROM items WHERE id = ?1",
                params![item.id],
                |r| r.get(0),
            )?;
            if incoming_updated <= stored_updated {
                return Ok(UpsertResult::Unchanged);
            }
            // created_at is immutable; has_file and thumbnail_b64 are left untouched.
            conn.execute(
                "UPDATE items SET title=?1, content=?2, item_type=?3, mime_type=?4,
                 is_pinned=?5, file_hash=?6, updated_at=?7 WHERE id=?8",
                params![
                    item.title,
                    item.content,
                    item.item_type,
                    item.mime_type,
                    item.is_pinned as i64,
                    item.file_hash,
                    incoming_updated,
                    item.id
                ],
            )?;
            Ok(UpsertResult::Updated)
        })();
        match result {
            Ok(r) => {
                conn.execute("COMMIT", [])?;
                Ok(r)
            }
            Err(e) => {
                let _ = conn.execute("ROLLBACK", []);
                Err(e)
            }
        }
    }

    pub fn delete_item(&self, id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        // Write (or refresh) a tombstone so a later stale re-sync of this id can't resurrect it,
        // then remove the live row. INSERT OR REPLACE advances deleted_at to "now" each time.
        conn.execute(
            "INSERT OR REPLACE INTO deleted_items (id, deleted_at) VALUES (?1, ?2)",
            params![id, now_ms()],
        )?;
        conn.execute("DELETE FROM items WHERE id = ?1", params![id])?;
        Ok(())
    }

    /// All tombstones as `(id, deleted_at)` pairs. The session replays these as
    /// `ItemDeleted` to a reconnecting client so it drops locally-held deleted items.
    pub fn list_tombstones(&self) -> Result<Vec<(String, i64)>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare("SELECT id, deleted_at FROM deleted_items")?;
        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })?
            .collect::<Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Delete tombstones older than `retention_ms` milliseconds. Returns the number removed.
    pub fn prune_tombstones(&self, retention_ms: i64) -> Result<usize> {
        let cutoff = now_ms() - retention_ms;
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let n = conn.execute(
            "DELETE FROM deleted_items WHERE deleted_at < ?1",
            params![cutoff],
        )?;
        Ok(n)
    }

    /// Whether an id currently has a tombstone. Convenience used in tests.
    pub fn is_tombstoned(&self, id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM deleted_items WHERE id = ?1",
            params![id],
            |r| r.get(0),
        )?;
        Ok(count > 0)
    }

    pub fn set_has_file(&self, id: &str, has_file: bool) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE items SET has_file = ?1 WHERE id = ?2",
            params![has_file as i64, id],
        )?;
        Ok(())
    }

    pub fn update_thumbnail(&self, id: &str, thumbnail_b64: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE items SET thumbnail_b64 = ?1 WHERE id = ?2",
            params![thumbnail_b64, id],
        )?;
        Ok(())
    }

    pub fn get_item(&self, id: &str) -> Result<Option<Item>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(
            "SELECT id, title, content, item_type, mime_type, is_pinned, created_at, has_file, file_hash, thumbnail_b64, updated_at
             FROM items WHERE id = ?1"
        )?;
        let mut rows = stmt.query_map(params![id], |row| {
            Ok(Item {
                id: row.get(0)?,
                title: row.get(1)?,
                content: row.get(2)?,
                item_type: row.get(3)?,
                mime_type: row.get(4)?,
                is_pinned: row.get::<_, i64>(5)? != 0,
                created_at: row.get(6)?,
                has_file: row.get::<_, i64>(7)? != 0,
                file_hash: row.get(8)?,
                thumbnail_b64: row.get(9)?,
                updated_at: row.get(10)?,
            })
        })?;
        rows.next().transpose()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, title: &str, created_at: i64, updated_at: i64) -> Item {
        Item {
            id: id.to_string(),
            title: title.to_string(),
            content: "content".to_string(),
            item_type: "text".to_string(),
            mime_type: String::new(),
            is_pinned: false,
            created_at,
            updated_at,
            has_file: false,
            file_hash: String::new(),
            thumbnail_b64: String::new(),
        }
    }

    fn mem_db() -> Database {
        Database::open(":memory:").expect("open in-memory db")
    }

    #[test]
    fn first_upsert_inserts() {
        let db = mem_db();
        let r = db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        assert_eq!(r, UpsertResult::Inserted);
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v1");
    }

    #[test]
    fn newer_updated_at_updates() {
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        let r = db.upsert_item(&item("a", "v2", 100, 200)).unwrap();
        assert_eq!(r, UpsertResult::Updated);
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v2");
    }

    #[test]
    fn older_updated_at_is_unchanged() {
        let db = mem_db();
        db.upsert_item(&item("a", "v2", 100, 200)).unwrap();
        let r = db.upsert_item(&item("a", "stale", 100, 150)).unwrap();
        assert_eq!(r, UpsertResult::Unchanged);
        // The stale edit must not overwrite the newer title (prevents silent edit loss).
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v2");
    }

    #[test]
    fn equal_updated_at_is_unchanged() {
        // A re-delivered edit carries an equal updated_at; returning Unchanged terminates
        // the mirror-forwarding loop instead of bouncing the change back.
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 200)).unwrap();
        let r = db.upsert_item(&item("a", "v1-again", 100, 200)).unwrap();
        assert_eq!(r, UpsertResult::Unchanged);
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v1");
    }

    #[test]
    fn missing_updated_at_falls_back_to_created_at() {
        // Older clients send updated_at = 0; effective_updated_at uses created_at instead.
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 300, 0)).unwrap();
        // A new item with a smaller created_at (and no updated_at) must lose.
        let r = db.upsert_item(&item("a", "older", 200, 0)).unwrap();
        assert_eq!(r, UpsertResult::Unchanged);
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v1");
    }

    #[test]
    fn delete_removes_item() {
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        db.delete_item("a").unwrap();
        assert!(db.get_item("a").unwrap().is_none());
    }

    // ── Tombstones: prevent deleted-item "resurrection" on offline-client reconnect ─

    #[test]
    fn test_delete_writes_tombstone() {
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        db.delete_item("a").unwrap();
        assert!(
            db.is_tombstoned("a").unwrap(),
            "delete must record a tombstone"
        );
    }

    #[test]
    fn test_upsert_blocked_by_tombstone() {
        // Item created at updated_at=100, then deleted (deleted_at ≈ now, far in the future
        // relative to 100). A stale re-sync of the same item must NOT resurrect it.
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        db.delete_item("a").unwrap();
        let r = db.upsert_item(&item("a", "resurrected", 100, 100)).unwrap();
        assert_eq!(
            r,
            UpsertResult::Unchanged,
            "tombstone must block the re-insert"
        );
        assert!(
            db.get_item("a").unwrap().is_none(),
            "item must stay deleted"
        );
        assert!(db.list_items().unwrap().iter().all(|i| i.id != "a"));
    }

    #[test]
    fn test_upsert_wins_over_stale_delete() {
        // An item legitimately re-created after a delete (updated_at strictly past the
        // tombstone time) must win and clear the tombstone.
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        db.delete_item("a").unwrap();
        let t = db
            .list_tombstones()
            .unwrap()
            .into_iter()
            .find(|(id, _)| id == "a")
            .unwrap()
            .1;
        // Recreate with updated_at well past the tombstone timestamp.
        let r = db
            .upsert_item(&item("a", "recreated", t + 10_000, t + 10_000))
            .unwrap();
        assert_eq!(
            r,
            UpsertResult::Inserted,
            "newer re-create must win over stale delete"
        );
        assert!(db
            .list_items()
            .unwrap()
            .iter()
            .any(|i| i.id == "a" && i.title == "recreated"));
        assert!(
            !db.is_tombstoned("a").unwrap(),
            "tombstone must be cleared after re-create"
        );
    }

    #[test]
    fn test_delete_during_disconnect_resurrection_prevented() {
        // Exact resurrection scenario: A exists (updated_at=50). While a client is offline,
        // A is deleted on the server. The client reconnects and re-sends A (same updated_at=50).
        // The tombstone (deleted_at ≈ now ≫ 50) must keep A deleted.
        let db = mem_db();
        db.upsert_item(&item("A", "original", 50, 50)).unwrap();
        db.delete_item("A").unwrap();
        let r = db.upsert_item(&item("A", "original", 50, 50)).unwrap();
        assert_eq!(
            r,
            UpsertResult::Unchanged,
            "offline re-sync must not resurrect A"
        );
        assert!(db.get_item("A").unwrap().is_none());
        assert!(db.list_items().unwrap().iter().all(|i| i.id != "A"));
    }

    #[test]
    fn test_list_tombstones_returns_deleted_ids() {
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 100, 100)).unwrap();
        db.upsert_item(&item("b", "v1", 100, 100)).unwrap();
        db.delete_item("a").unwrap();
        db.delete_item("b").unwrap();
        let ids: std::collections::HashSet<String> = db
            .list_tombstones()
            .unwrap()
            .into_iter()
            .map(|(id, _)| id)
            .collect();
        assert!(ids.contains("a"));
        assert!(ids.contains("b"));
        assert_eq!(ids.len(), 2);
    }

    #[test]
    fn test_delete_nonexistent_item_creates_tombstone() {
        // A delete for an id we never stored (e.g. a mirror peer deletes something we
        // don't hold locally) must still leave a tombstone, so a future SyncMeta carrying
        // that id can't resurrect it.
        let db = mem_db();
        db.delete_item("ghost").unwrap();
        assert!(db.is_tombstoned("ghost").unwrap());
        let r = db.upsert_item(&item("ghost", "back", 1, 1)).unwrap();
        assert_eq!(r, UpsertResult::Unchanged);
        assert!(db.get_item("ghost").unwrap().is_none());
    }

    #[test]
    fn test_tombstone_deleted_at_is_after_item_updated_at() {
        // The tombstone timestamp (wall-clock now) must advance past a tiny updated_at,
        // so the delete reliably beats the original item on conflict.
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 1, 1)).unwrap();
        db.delete_item("a").unwrap();
        let deleted_at = db
            .list_tombstones()
            .unwrap()
            .into_iter()
            .find(|(id, _)| id == "a")
            .unwrap()
            .1;
        assert!(
            deleted_at > 1,
            "deleted_at ({deleted_at}) must advance past updated_at=1"
        );
    }

    // ── M4: concurrent upserts always reach a consistent last-writer-wins state ─
    //
    // The Mutex<Connection> serialises all callers.  The BEGIN/COMMIT wrapping (M4's
    // fix) adds crash-atomicity: if the process dies between INSERT and UPDATE the
    // transaction rolls back cleanly.  That property requires killing the process
    // mid-statement and is not unit-testable.  This test verifies the correctness
    // invariant: whichever thread wins the lock last with the higher updated_at must
    // be the value stored — regardless of which thread schedules first.
    #[test]
    fn concurrent_upsert_is_consistent() {
        use std::sync::Arc;
        let db = Arc::new(mem_db());
        db.upsert_item(&item("a", "base", 100, 100)).unwrap();

        let db_older = Arc::clone(&db);
        let db_newer = Arc::clone(&db);

        let t_older = std::thread::spawn(move || {
            db_older.upsert_item(&item("a", "older", 100, 150)).unwrap()
        });
        let t_newer = std::thread::spawn(move || {
            db_newer.upsert_item(&item("a", "newer", 100, 200)).unwrap()
        });

        let _ = t_older.join().unwrap();
        let _ = t_newer.join().unwrap();

        let stored = db.get_item("a").unwrap().unwrap();
        assert_eq!(
            stored.title, "newer",
            "last-writer-wins must survive concurrent upserts; got {:?}",
            stored.title
        );
    }

    // ── M7: stored updated_at governs the conflict resolution decision ─────────
    //
    // M7 replaced `unwrap_or(0)` with `?` on the SELECT updated_at query, so DB
    // errors now propagate instead of silently defaulting to 0 (which would make
    // every incoming item appear "newer").  Forcing the SELECT to fail requires a
    // corrupt/dropped table — not achievable in a unit test without destroying the
    // DB.  This test verifies the correctness invariant: the decision is driven by
    // the value actually stored, not by a hardcoded default.
    #[test]
    fn stored_updated_at_governs_conflict_resolution() {
        let db = mem_db();
        // Insert with updated_at=500.
        db.upsert_item(&item("a", "v1", 100, 500)).unwrap();

        // An incoming item with updated_at=400 must lose (400 < 500 stored).
        let r = db.upsert_item(&item("a", "v2", 100, 400)).unwrap();
        assert_eq!(
            r,
            UpsertResult::Unchanged,
            "stored updated_at=500 must beat incoming updated_at=400"
        );
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v1");

        // An incoming item with updated_at=600 must win (600 > 500 stored).
        let r = db.upsert_item(&item("a", "v3", 100, 600)).unwrap();
        assert_eq!(
            r,
            UpsertResult::Updated,
            "incoming updated_at=600 must beat stored updated_at=500"
        );
        assert_eq!(db.get_item("a").unwrap().unwrap().title, "v3");
    }

    // ── L12: duplicate-column errors are silenced; other ALTER TABLE errors propagate ─
    //
    // Old code: `let _ = conn.execute(ALTER TABLE ...)` — silences ALL errors.
    // New code: `ignore_dup_column(...)? ` — only silences "duplicate column name".
    //
    // Positive case (idempotent open): opening the same real DB twice succeeds because
    // the "duplicate column name" error is the one that fires, and it is silenced.
    //
    // Negative case (non-dup error propagates): if `items` is a VIEW (not a TABLE),
    // ALTER TABLE returns "no such table: items" — not "duplicate column name" — and
    // the new code must propagate that error.  The old code swallowed it and returned Ok.
    #[test]
    fn open_twice_is_idempotent() {
        let path = format!("/tmp/stash_test_idempotent_{}.db", std::process::id());
        let _ = std::fs::remove_file(&path);

        let db1 = Database::open(&path).expect("first open");
        db1.upsert_item(&item("sentinel", "preserved", 1000, 1000))
            .unwrap();
        drop(db1);

        let db2 = Database::open(&path).expect("second open must not fail");
        let stored = db2.get_item("sentinel").unwrap();
        assert!(
            stored.is_some(),
            "data from first open must survive second open"
        );
        assert_eq!(stored.unwrap().title, "preserved");
        drop(db2);

        let _ = std::fs::remove_file(&path);
    }

    // ── Tombstone GC: prune_tombstones removes old entries but keeps recent ones ─
    #[test]
    fn test_prune_tombstones_removes_stale() {
        let db = mem_db();
        db.upsert_item(&item("a", "v1", 1, 1)).unwrap();
        db.upsert_item(&item("b", "v1", 1, 1)).unwrap();
        db.upsert_item(&item("c", "v1", 1, 1)).unwrap();
        db.delete_item("a").unwrap();
        db.delete_item("b").unwrap();
        db.delete_item("c").unwrap();
        // Sleep so the tombstone timestamps are measurably in the past.
        std::thread::sleep(std::time::Duration::from_millis(5));
        // Prune with 1 ms retention — cutoff is ~4 ms after tombstones were written.
        let pruned = db.prune_tombstones(1).unwrap();
        assert_eq!(pruned, 3, "all three stale tombstones must be removed");
        assert!(!db.is_tombstoned("a").unwrap());
        assert!(!db.is_tombstoned("b").unwrap());
        assert!(!db.is_tombstoned("c").unwrap());
    }

    #[test]
    fn test_prune_tombstones_keeps_recent() {
        let db = mem_db();
        db.upsert_item(&item("x", "v1", 1, 1)).unwrap();
        db.upsert_item(&item("y", "v1", 1, 1)).unwrap();
        db.delete_item("x").unwrap();
        db.delete_item("y").unwrap();
        // Prune with a 1-hour retention — tombstones created milliseconds ago must survive.
        let pruned = db.prune_tombstones(60 * 60 * 1000).unwrap();
        assert_eq!(pruned, 0, "recently-created tombstones must not be pruned");
        assert!(db.is_tombstoned("x").unwrap());
        assert!(db.is_tombstoned("y").unwrap());
    }

    #[test]
    fn alter_table_non_dup_error_propagates() {
        // Create a DB where `items` is a VIEW (not a TABLE).
        // `CREATE TABLE IF NOT EXISTS items` is a no-op (something named items exists),
        // but `ALTER TABLE items ADD COLUMN ...` fails with "no such table: items"
        // (SQLite cannot ALTER a view).  This is not a "duplicate column name" error,
        // so Database::open must return Err — proving ignore_dup_column is selective.
        // Old code (let _ = ...) would silently return Ok.
        let path = format!("/tmp/stash_test_l12_{}.db", std::process::id());
        let _ = std::fs::remove_file(&path);

        let setup = rusqlite::Connection::open(&path).unwrap();
        setup
            .execute_batch("CREATE VIEW items AS SELECT 1 AS id")
            .unwrap();
        drop(setup);

        let result = Database::open(&path);
        assert!(
            result.is_err(),
            "L12: Database::open must propagate non-dup-column ALTER TABLE errors; \
             old code swallowed all errors with 'let _ = ...'"
        );

        let _ = std::fs::remove_file(&path);
    }
}
