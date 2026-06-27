package com.stashapp.sync.core

import com.stashapp.data.StashItem
import com.stashapp.sync.SyncItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ItemStore] so the real [SyncReducer] logic runs on the JVM with no Android. */
private class FakeStore : ItemStore {
    val items = mutableListOf<StashItem>()
    val tombstones = mutableSetOf<String>()
    val syncIdUpdates = mutableListOf<Pair<Long, String>>()
    private var nextId = 1L

    fun seed(item: StashItem): StashItem {
        val withId = item.copy(id = nextId++)
        items.add(withId)
        return withId
    }

    override suspend fun getBySyncId(syncId: String) = items.firstOrNull { it.syncId == syncId }
    override suspend fun getByFileHash(hash: String) =
        if (hash.isEmpty()) null else items.firstOrNull { it.fileHash == hash }

    override suspend fun insert(item: StashItem): Long {
        val id = nextId++
        items.add(item.copy(id = id))
        return id
    }

    override suspend fun updateSyncId(localId: Long, syncId: String) {
        syncIdUpdates.add(localId to syncId)
        val i = items.indexOfFirst { it.id == localId }
        if (i >= 0) items[i] = items[i].copy(syncId = syncId)
    }

    override suspend fun applyRemoteEdit(
        syncId: String, title: String, content: String, isPinned: Boolean, updatedAt: Long
    ) {
        val i = items.indexOfFirst { it.syncId == syncId }
        if (i >= 0) items[i] = items[i].copy(
            title = title, content = content, isPinned = isPinned, updatedAt = updatedAt
        )
    }

    override suspend fun hasDeleteTombstone(syncId: String) = syncId in tombstones
}

private fun wire(
    id: String, type: String = "link", content: String = "https://example.com", title: String = "t",
    updatedAt: Long = 0, createdAt: Long = 1000, fileHash: String = ""
) = SyncItem(
    id = id, title = title, content = content, type = type, mimeType = "",
    isPinned = false, createdAt = createdAt, updatedAt = updatedAt,
    hasFile = false, fileHash = fileHash, thumbnailB64 = ""
)

private fun localItem(
    syncId: String, type: String, content: String, title: String = "t",
    updatedAt: Long, fileHash: String = "", fileLocal: Boolean = type in listOf("link", "text"),
    removedLocally: Boolean = false
) = StashItem(
    title = title, content = content, type = type, mimeType = "", originalFilename = "",
    sourceDomain = "", createdAt = 1000, updatedAt = updatedAt, syncId = syncId,
    source = if (type in listOf("link", "text")) "local" else "cloud",
    fileLocal = fileLocal, fileHash = fileHash, removedLocally = removedLocally
)

class AutoDownloadTest {
    @Test fun cloudItemServerHasFile_downloads() {
        val item = localItem("a", "image", "", updatedAt = 1, fileLocal = false)
        assertTrue(shouldAutoDownload(item, serverHasFile = true))
    }

    @Test fun ghostedItemNotRedownloaded() {
        val ghost = localItem("a", "image", "", updatedAt = 1, fileLocal = false, removedLocally = true)
        assertEquals(false, shouldAutoDownload(ghost, serverHasFile = true)) // Test 6 fix
    }

    @Test fun localFileNotDownloaded() {
        val local = localItem("a", "image", "/p", updatedAt = 1, fileLocal = true)
        assertEquals(false, shouldAutoDownload(local, serverHasFile = true))
    }

    @Test fun serverLacksFile_notDownloaded() {
        val item = localItem("a", "image", "", updatedAt = 1, fileLocal = false)
        assertEquals(false, shouldAutoDownload(item, serverHasFile = false))
    }

    @Test fun linkNotDownloaded() {
        val link = localItem("a", "link", "https://x", updatedAt = 1)
        assertEquals(false, shouldAutoDownload(link, serverHasFile = true))
    }
}

class SyncReducerTest {

    private fun reducer(store: FakeStore, deleted: MutableList<String> = mutableListOf()) =
        SyncReducer(store) { id -> deleted.add(id) }

    @Test fun newItemInserted() = runTest {
        val store = FakeStore()
        val r = reducer(store).apply(wire("a"))
        assertTrue(r is SyncReducer.Result.Inserted)
        assertEquals(1, store.items.size)
    }

    @Test fun duplicateApplyDoesNotInsertTwice() = runTest {
        val store = FakeStore()
        val red = reducer(store)
        red.apply(wire("a", updatedAt = 5))
        val second = red.apply(wire("a", updatedAt = 5))
        assertTrue(second is SyncReducer.Result.Skipped)
        assertEquals(1, store.items.size)  // the duplicate-item bug would make this 2
    }

    @Test fun tombstonedItemNotResurrected() = runTest {
        val store = FakeStore().apply { tombstones.add("gone") }
        val deleted = mutableListOf<String>()
        val r = reducer(store, deleted).apply(wire("gone"))
        assertTrue(r is SyncReducer.Result.Skipped)
        assertEquals(0, store.items.size)
        assertEquals(listOf("gone"), deleted)   // re-asserted the delete
    }

    @Test fun newerEditApplied() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "text", "old", title = "old", updatedAt = 100))
        val r = reducer(store).apply(wire("a", type = "text", content = "new", title = "new", updatedAt = 200))
        assertTrue(r is SyncReducer.Result.Updated)
        assertEquals("new", store.items.first().content)
        assertEquals(200, store.items.first().updatedAt)
    }

    @Test fun olderEditIgnored() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "text", "current", title = "current", updatedAt = 200))
        val r = reducer(store).apply(wire("a", type = "text", content = "stale", updatedAt = 100))
        assertTrue(r is SyncReducer.Result.Skipped)
        assertEquals("current", store.items.first().content)
    }

    @Test fun fileItemEditKeepsLocalContentPath() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "image", "/data/files/a", title = "old", updatedAt = 100))
        reducer(store).apply(wire("a", type = "image", content = "renamed.jpg", title = "new", updatedAt = 200))
        // Title updates, but the local file path must NOT be overwritten with the server filename.
        assertEquals("new", store.items.first().title)
        assertEquals("/data/files/a", store.items.first().content)
    }

    @Test fun fileHashDedupKeepsExistingIdAndDeletesDuplicate() = runTest {
        val deleted = mutableListOf<String>()
        val store = FakeStore()
        store.seed(localItem("oldid", "image", "/data/files/x", updatedAt = 100, fileHash = "HASH"))
        val r = reducer(store, deleted).apply(wire("newid", type = "image", fileHash = "HASH"))
        assertTrue(r is SyncReducer.Result.Skipped)
        assertEquals(1, store.items.size)
        // Local item keeps its existing syncId; the server's duplicate is deleted instead.
        assertEquals("oldid", store.items.first().syncId)
        assertTrue(store.syncIdUpdates.isEmpty())
        assertEquals(listOf("newid"), deleted)
    }

    @Test fun equalUpdatedAtNotApplied() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "text", "current", title = "current", updatedAt = 100))
        val r = reducer(store).apply(wire("a", type = "text", content = "same-time", updatedAt = 100))
        assertTrue(r is SyncReducer.Result.Skipped)
        assertEquals("current", store.items.first().content)
    }

    @Test fun tombstoneBeatsExistingItem() = runTest {
        // A tombstone must win even if the item still exists locally — re-assert delete, don't edit.
        val store = FakeStore().apply { tombstones.add("a") }
        store.seed(localItem("a", "text", "current", updatedAt = 100))
        val deleted = mutableListOf<String>()
        val r = reducer(store, deleted).apply(wire("a", type = "text", content = "edit", updatedAt = 999))
        assertTrue(r is SyncReducer.Result.Skipped)
        assertEquals("current", store.items.first().content)
        assertEquals(listOf("a"), deleted)
    }

    @Test fun emptyHashDoesNotDedup() = runTest {
        val store = FakeStore()
        val red = reducer(store)
        red.apply(wire("a", type = "image", fileHash = ""))
        red.apply(wire("b", type = "image", fileHash = ""))
        assertEquals(2, store.items.size)   // blank hashes must never collapse distinct items
        assertTrue(store.syncIdUpdates.isEmpty())
    }

    @Test fun pinChangePropagatesAsEdit() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "text", "c", updatedAt = 100).copy(isPinned = false))
        reducer(store).apply(wire("a", type = "text", content = "c", updatedAt = 200).copy(isPinned = true))
        assertTrue(store.items.first().isPinned)
    }

    @Test fun concurrentApplyInsertsOnce() = runTest {
        // Five coroutines applying the same brand-new item must yield exactly one row (the
        // mutex-guarded check-then-insert). Without the guard this is the duplicate-item bug.
        val store = FakeStore()
        val red = reducer(store)
        val item = wire("dup", type = "image", updatedAt = 1)
        coroutineScope { repeat(5) { launch { red.apply(item) } } }
        assertEquals(1, store.items.size)
    }
}

class ToStashItemTest {
    @Test fun linkMapsToLocalTextItem() {
        val s = wire("a", type = "link", content = "https://x", title = "X").toStashItem()
        assertEquals("https://x", s.content)
        assertEquals("", s.originalFilename)
        assertTrue(s.fileLocal)             // link/text are always "local"
        assertEquals("cloud", s.source)
        assertEquals("a", s.syncId)
    }

    @Test fun fileMapsWithEmptyContentAndOriginalFilename() {
        val s = wire("a", type = "image", content = "photo.jpg").toStashItem()
        assertEquals("", s.content)               // file path filled in only after download
        assertEquals("photo.jpg", s.originalFilename)
        assertEquals(false, s.fileLocal)
    }

    @Test fun updatedAtFallsBackToCreatedAt() {
        val s = wire("a", createdAt = 555, updatedAt = 0).toStashItem()
        assertEquals(555, s.updatedAt)
    }
}

// ── H3: updatedAt=0 in SyncReducer — createdAt used as tiebreaker ─────────────
//
// Bug: when both items have updatedAt=0, `serverItem.updatedAt > existing.updatedAt`
// is `0 > 0 == false`, so the edit is silently dropped even if the server item has a
// larger createdAt.
// Fix: use `maxOf(updatedAt, createdAt)` on both sides so older clients that don't
// send updatedAt still participate in last-writer-wins ordering via createdAt.
class H3UpdatedAtZeroTest {
    private fun reducer(store: FakeStore) = SyncReducer(store) { }

    @Test
    fun bothUpdatedAtZero_newerCreatedAt_applies() = runTest {
        // Existing item: updatedAt=0, createdAt=1000
        val store = FakeStore()
        store.seed(localItem("a", "text", "old", title = "old",
            updatedAt = 0).copy(createdAt = 1000))

        // Server item: updatedAt=0 too, but createdAt=2000 (newer)
        val serverItem = wire("a", type = "text", content = "new", title = "new",
            updatedAt = 0, createdAt = 2000)
        val r = reducer(store).apply(serverItem)

        // With the fix, maxOf(0,2000)=2000 > maxOf(0,1000)=1000 → update applied.
        assertTrue(
            "H3: server item with newer createdAt (both updatedAt=0) must be applied; got $r",
            r is SyncReducer.Result.Updated
        )
        assertEquals("new", store.items.first().content)
    }

    @Test
    fun bothUpdatedAtZero_olderCreatedAt_skipped() = runTest {
        // Existing item: updatedAt=0, createdAt=2000
        val store = FakeStore()
        store.seed(localItem("a", "text", "current", title = "current",
            updatedAt = 0).copy(createdAt = 2000))

        // Server item: updatedAt=0, createdAt=1000 (older)
        val serverItem = wire("a", type = "text", content = "stale", title = "stale",
            updatedAt = 0, createdAt = 1000)
        val r = reducer(store).apply(serverItem)

        // maxOf(0,1000)=1000 < maxOf(0,2000)=2000 → must be skipped.
        assertTrue(
            "H3: server item with older createdAt must be skipped; got $r",
            r is SyncReducer.Result.Skipped
        )
        assertEquals("current", store.items.first().content)
    }

    @Test
    fun serverHasUpdatedAt_localHasZero_applies() = runTest {
        // Existing: updatedAt=0, createdAt=1000.  Effective = 1000.
        val store = FakeStore()
        store.seed(localItem("a", "text", "old", title = "old",
            updatedAt = 0).copy(createdAt = 1000))

        // Server: updatedAt=500 (explicit, > 0), createdAt=1000. Effective = 500.
        // 500 < 1000 → should NOT be applied.
        val serverItemOlder = wire("a", type = "text", content = "nope", updatedAt = 500, createdAt = 1000)
        assertTrue(reducer(store).apply(serverItemOlder) is SyncReducer.Result.Skipped)

        // Server: updatedAt=1500 (explicit, > effective 1000 of existing) → should apply.
        val serverItemNewer = wire("a", type = "text", content = "yes", title = "yes",
            updatedAt = 1500, createdAt = 1000)
        val r = reducer(store).apply(serverItemNewer)
        assertTrue(
            "H3: server item with explicit newer updatedAt must apply over local updatedAt=0; got $r",
            r is SyncReducer.Result.Updated
        )
        assertEquals("yes", store.items.first().content)
    }
}

// ── L3: URL scheme validation on link edit ─────────────────────────────────────
//
// Bug: a remote edit for a link item with content="javascript:alert(1)" was written
// directly into the local DB, letting an XSS payload appear in any web-view that
// renders the content as a clickable link.
// Fix: for link-type items, only accept http:// or https:// content; otherwise keep
// the existing local content.
class L3UrlSchemeTest {
    private fun reducer(store: FakeStore) = SyncReducer(store) { }

    @Test
    fun javascriptUrlIsRejectedForLinkItem() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "link", "https://safe.example.com", title = "t", updatedAt = 100))

        val r = reducer(store).apply(
            wire("a", type = "link", content = "javascript:alert(1)", title = "t", updatedAt = 200)
        )

        // The reducer should still apply the metadata change (title, updatedAt, isPinned)
        // but MUST NOT write the javascript: URL into content.
        // Result.Updated is acceptable (title may change) — what matters is the content.
        assertNull("L3: content must not be javascript: URI",
            store.items.firstOrNull { it.content == "javascript:alert(1)" })
        assertEquals(
            "L3: existing safe URL must be preserved when remote sends javascript: URI",
            "https://safe.example.com",
            store.items.first().content
        )
    }

    @Test
    fun httpsUrlIsAcceptedForLinkItem() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "link", "https://old.example.com", title = "t", updatedAt = 100))

        reducer(store).apply(
            wire("a", type = "link", content = "https://new.example.com", title = "t", updatedAt = 200)
        )

        assertEquals(
            "L3: https:// URL must be applied normally",
            "https://new.example.com",
            store.items.first().content
        )
    }

    @Test
    fun httpUrlIsAcceptedForLinkItem() = runTest {
        val store = FakeStore()
        store.seed(localItem("a", "link", "https://old.example.com", title = "t", updatedAt = 100))

        reducer(store).apply(
            wire("a", type = "link", content = "http://new.example.com", title = "t", updatedAt = 200)
        )

        assertEquals(
            "L3: http:// URL must be accepted",
            "http://new.example.com",
            store.items.first().content
        )
    }

    @Test
    fun javascriptUrlForTextItemIsAccepted() = runTest {
        // text items are not link items — scheme check only applies to type=="link".
        // A text item's content is free-form and must not be filtered by URL scheme.
        val store = FakeStore()
        store.seed(localItem("a", "text", "old text", title = "t", updatedAt = 100))

        reducer(store).apply(
            wire("a", type = "text", content = "javascript:alert(1)", title = "t", updatedAt = 200)
        )

        // For text items the scheme check must NOT strip the content.
        assertEquals(
            "L3: scheme check must only apply to link items, not text items",
            "javascript:alert(1)",
            store.items.first().content
        )
    }

    // L3 — insert path: brand-new link items with non-http(s) URLs must also be rejected.
    // The earlier L3 fix only guarded the edit path (existing item); the insert path
    // (no existing item with this syncId) was unguarded and could store a javascript: URL.

    @Test
    fun newLinkItemWithJavascriptUrlIsRejected() = runTest {
        val store = FakeStore()
        val r = reducer(store).apply(
            wire("brand-new", type = "link", content = "javascript:alert(1)", updatedAt = 0, createdAt = 1000)
        )
        assertTrue(
            "L3 insert path: brand-new link with javascript: URL must be Skipped, not Inserted; got $r",
            r is SyncReducer.Result.Skipped
        )
        assertTrue(
            "L3 insert path: rejected link must not appear in the store",
            store.items.none { it.content == "javascript:alert(1)" }
        )
    }

    @Test
    fun newLinkItemWithHttpsUrlIsInserted() = runTest {
        val store = FakeStore()
        val r = reducer(store).apply(
            wire("brand-new", type = "link", content = "https://example.com", updatedAt = 0, createdAt = 1000)
        )
        assertTrue(
            "L3 insert path: brand-new link with https:// URL must be Inserted; got $r",
            r is SyncReducer.Result.Inserted
        )
        assertEquals(1, store.items.size)
        assertEquals("https://example.com", store.items.first().content)
    }

    @Test
    fun newLinkItemWithDataUrlIsRejected() = runTest {
        val store = FakeStore()
        val r = reducer(store).apply(
            wire("brand-new-data", type = "link", content = "data:text/html,<script>alert(1)</script>",
                updatedAt = 0, createdAt = 1000)
        )
        assertTrue(
            "L3 insert path: data: URL must be Skipped; got $r",
            r is SyncReducer.Result.Skipped
        )
        assertTrue(store.items.isEmpty())
    }
}

// ── M9: Room DB migration — fallbackToDestructiveMigration removed ─────────────
//
// M9 is a build-time configuration change: `fallbackToDestructiveMigration()` was
// removed from the Room DB builder so a missing migration throws instead of silently
// wiping user data.  This cannot be tested with a JVM unit test (Room requires an
// Android context and the actual DB file).  The test is documented here as a
// specification; it must be run on a device or emulator.
//
// Expected behaviour after the fix:
//   1. Opening the DB on a version that has all required migrations → succeeds.
//   2. Opening the DB skipping a migration version → throws
//      `IllegalStateException: A migration from X to Y was required but not found`.
//
// To verify manually:
//   - Build a release with the old version number, install, add data.
//   - Build a release that bumps the Room schema version WITHOUT adding a Migration.
//   - Install over the existing app.
//   - Confirm the app crashes with the migration exception instead of silently
//     deleting the database and starting fresh.
class M9MigrationDocumentationTest {

    // Source-sniffer: verifies that .fallbackToDestructiveMigration() was not re-introduced.
    // This test fails on old code (method present) and passes on new code (method absent).
    // A device/emulator test of the actual migration exception is documented in the class
    // comment above; the behaviour cannot be triggered from the JVM unit-test environment.
    @Test
    fun fallbackToDestructiveMigrationIsAbsent() {
        val workDir = System.getProperty("user.dir") ?: return
        val dbFile = java.io.File(workDir,
            "src/main/java/com/stashapp/data/StashDatabase.kt")
        if (!dbFile.exists()) return  // graceful skip if path differs in CI
        val source = dbFile.readText()
        assertFalse(
            "M9: .fallbackToDestructiveMigration() must not be present in StashDatabase — " +
            "it silently wipes user data when a Room schema migration is missing",
            source.contains("fallbackToDestructiveMigration")
        )
    }
}
