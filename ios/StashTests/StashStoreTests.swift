import XCTest
@testable import Stash

// ── Helper: a StashStore backed by a temporary directory ──────────────────────
//
// StashStore's init() hardcodes the applicationSupportDirectory, which isn't
// suitable for tests.  To work around this without modifying production code,
// we drive the store through its public API and use an in-memory-style approach:
// create the store normally (it writes to the real app-support dir in test builds)
// and reset its items array between tests using `deleteLocal`.
//
// For CI-friendliness each test seeds only items it owns and cleans up after itself.

// ── Shared item factory ───────────────────────────────────────────────────────

private func makeSyncItem(
    id: String,
    fileHash: String = "",
    createdAt: Int64 = 0,
    updatedAt: Int64 = 1000,
    type: String = "image",
    content: String = "photo.jpg",
    title: String = "Test"
) -> SyncItem {
    SyncItem(
        id: id,
        title: title,
        content: content,
        type: type,
        mimeType: "",
        isPinned: false,
        createdAt: createdAt,
        updatedAt: updatedAt,
        hasFile: false,
        fileHash: fileHash,
        thumbnailB64: ""
    )
}

// ── H1: mergeRemote must not create a duplicate when deduping by fileHash ─────
//
// Bug: when a remote item matched an existing local item by fileHash, the code
// updated the existing item's metadata AND then also inserted the remote item,
// doubling the item count.
// Fix: when dedup matches, update the existing entry in-place and return early
// WITHOUT calling items.insert(remote, ...).
@MainActor
final class H1MergeRemoteNoDuplicateTests: XCTestCase {

    private var store: StashStore!
    private var initialCount: Int = 0

    override func setUp() async throws {
        store = StashStore()
        initialCount = store.items.count
    }

    override func tearDown() async throws {
        store.deleteLocal(syncId: "__h1_existing__")
        store.deleteLocal(syncId: "__h1_remote__")
    }

    /// H1: mergeRemote with a remote item whose fileHash matches an existing local item
    /// must NOT increase items.count.
    func testMergeRemoteByHashDoesNotDuplicate() throws {
        let sharedHash = "deadbeef1234567890abcdef"

        // Seed the local item under a different syncId.
        let local = makeSyncItem(id: "__h1_existing__", fileHash: sharedHash, updatedAt: 500)
        store.localUpsert(local)
        let countAfterSeed = store.items.count

        // Merge a remote item with the same fileHash but a different id.
        let remote = makeSyncItem(id: "__h1_remote__", fileHash: sharedHash, updatedAt: 600)
        store.mergeRemote(remote)

        XCTAssertEqual(
            store.items.count,
            countAfterSeed,
            "H1: mergeRemote with matching fileHash must NOT insert a duplicate; " +
            "count was \(countAfterSeed), is now \(store.items.count)"
        )
    }

    /// L4: after dedup by fileHash, the existing local item KEEPS its own syncId and the
    /// caller is signalled (via onDuplicateSyncId) to delete the server's duplicate.
    ///
    /// Note: this is the opposite of an earlier design where the local item adopted the
    /// remote's id. Adopting the incoming id caused deleted items to reappear on the next
    /// sync (deleting id B left the original id A on the server), so the store now keeps
    /// the existing id and reports the duplicate id instead. See StashStore.mergeRemote.
    func testMergeRemoteByHashKeepsExistingIdAndSignalsDuplicate() throws {
        let sharedHash = "cafebabe9876543210fedcba"

        var reportedDuplicate: String?
        store.onDuplicateSyncId = { reportedDuplicate = $0 }

        let local = makeSyncItem(id: "__h1_existing__", fileHash: sharedHash, updatedAt: 500)
        store.localUpsert(local)

        let remote = makeSyncItem(id: "__h1_remote__", fileHash: sharedHash, updatedAt: 600)
        store.mergeRemote(remote)

        // The existing item must still carry its original id.
        let existing = store.items.first { $0.fileHash == sharedHash }
        XCTAssertNotNil(existing,
            "L4: an item with the shared fileHash must still exist after mergeRemote")
        XCTAssertEqual(
            existing?.id,
            "__h1_existing__",
            "L4: after dedup, the existing item must keep its own syncId; got \(existing?.id ?? "nil")"
        )
        // The remote's id must NOT have been inserted as a separate/renamed item.
        XCTAssertNil(
            store.items.first { $0.id == "__h1_remote__" },
            "L4: the remote's duplicate syncId must not become a local item"
        )
        // The caller must have been told to delete the server-side duplicate.
        XCTAssertEqual(
            reportedDuplicate,
            "__h1_remote__",
            "L4: the duplicate remote syncId must be reported via onDuplicateSyncId"
        )
    }

    /// Sanity: mergeRemote for a brand-new item (no hash match, no id match) DOES insert.
    func testMergeRemoteNewItemIsInserted() throws {
        let countBefore = store.items.count
        let remote = makeSyncItem(id: "__h1_brand_new__", fileHash: "unique999", updatedAt: 100)
        store.mergeRemote(remote)
        XCTAssertEqual(
            store.items.count,
            countBefore + 1,
            "mergeRemote must insert a genuinely new item"
        )
        store.deleteLocal(syncId: "__h1_brand_new__")
    }
}

// ── MergeRemoteLWWTests: last-writer-wins merge logic ─────────────────────────

@MainActor
final class MergeRemoteLWWTests: XCTestCase {

    private var store: StashStore!

    override func setUp() async throws { store = StashStore() }

    override func tearDown() async throws {
        for id in ["__lw_item__", "__lw_file__", "__lw_link__", "__lw_thumb__"] {
            store.deleteLocal(syncId: id)
        }
    }

    func testMergeRemoteNewerUpdatesMetadata() {
        let local = makeSyncItem(id: "__lw_item__", updatedAt: 500, title: "Old")
        store.localUpsert(local)
        let remote = makeSyncItem(id: "__lw_item__", updatedAt: 600, title: "New")
        let changed = store.mergeRemote(remote)
        XCTAssertTrue(changed)
        XCTAssertEqual(store.item(syncId: "__lw_item__")?.title, "New")
    }

    func testMergeRemoteStaleRemoteIgnored() {
        let local = makeSyncItem(id: "__lw_item__", updatedAt: 600, title: "Current")
        store.localUpsert(local)
        let stale = makeSyncItem(id: "__lw_item__", updatedAt: 500, title: "Stale")
        let changed = store.mergeRemote(stale)
        XCTAssertFalse(changed)
        XCTAssertEqual(store.item(syncId: "__lw_item__")?.title, "Current")
    }

    func testMergeRemoteEqualTimestampIgnored() {
        let item = makeSyncItem(id: "__lw_item__", updatedAt: 600, title: "Original")
        store.localUpsert(item)
        let remote = makeSyncItem(id: "__lw_item__", updatedAt: 600, title: "EqualTime")
        let changed = store.mergeRemote(remote)
        XCTAssertFalse(changed)
        XCTAssertEqual(store.item(syncId: "__lw_item__")?.title, "Original")
    }

    func testMergeRemoteDoesNotOverwriteContentForFileItems() {
        let local = makeSyncItem(id: "__lw_file__", updatedAt: 500,
                                 type: "image", content: "local.jpg", title: "A")
        store.localUpsert(local)
        let remote = makeSyncItem(id: "__lw_file__", updatedAt: 600,
                                  type: "image", content: "remote.jpg", title: "B")
        store.mergeRemote(remote)
        let saved = store.item(syncId: "__lw_file__")
        XCTAssertEqual(saved?.content, "local.jpg", "file content (filename) must not be overwritten by merge")
        XCTAssertEqual(saved?.title, "B", "title (comment) should be updated")
    }

    func testMergeRemoteUpdatesContentForLinkItems() {
        let local = makeSyncItem(id: "__lw_link__", updatedAt: 500,
                                 type: "link", content: "https://old.com", title: "A")
        store.localUpsert(local)
        let remote = makeSyncItem(id: "__lw_link__", updatedAt: 600,
                                  type: "link", content: "https://new.com", title: "A")
        store.mergeRemote(remote)
        XCTAssertEqual(store.item(syncId: "__lw_link__")?.content, "https://new.com")
    }

    func testMergeRemoteThumbnailNotOverwrittenByEmpty() {
        var local = makeSyncItem(id: "__lw_thumb__", updatedAt: 500)
        local.thumbnailB64 = "existingThumb"
        store.localUpsert(local)
        var remote = makeSyncItem(id: "__lw_thumb__", updatedAt: 600)
        remote.thumbnailB64 = ""
        store.mergeRemote(remote)
        XCTAssertEqual(store.item(syncId: "__lw_thumb__")?.thumbnailB64, "existingThumb",
                       "empty remote thumbnail must not overwrite a stored local thumbnail")
    }

    func testMergeRemoteThumbnailOverwrittenWhenProvided() {
        var local = makeSyncItem(id: "__lw_thumb__", updatedAt: 500)
        local.thumbnailB64 = "oldThumb"
        store.localUpsert(local)
        var remote = makeSyncItem(id: "__lw_thumb__", updatedAt: 600)
        remote.thumbnailB64 = "newThumb"
        store.mergeRemote(remote)
        XCTAssertEqual(store.item(syncId: "__lw_thumb__")?.thumbnailB64, "newThumb")
    }
}

// ── StoreQueryTests: misc store API coverage ──────────────────────────────────

@MainActor
final class StoreQueryTests: XCTestCase {

    private var store: StashStore!

    override func setUp() async throws { store = StashStore() }

    override func tearDown() async throws {
        for id in ["__q_del__", "__q_fh__", "__q_flag__"] {
            store.deleteLocal(syncId: id)
        }
    }

    func testDeleteLocalRemovesItem() {
        store.localUpsert(makeSyncItem(id: "__q_del__"))
        store.deleteLocal(syncId: "__q_del__")
        XCTAssertNil(store.item(syncId: "__q_del__"))
    }

    func testItemByFileHashEmptyReturnsNil() {
        // An item with an empty fileHash must not be found via item(fileHash:),
        // since empty hash would otherwise match all such items.
        store.localUpsert(makeSyncItem(id: "__q_fh__", fileHash: ""))
        XCTAssertNil(store.item(fileHash: ""))
    }

    func testSetHasFileFlagUpdates() {
        store.localUpsert(makeSyncItem(id: "__q_flag__"))
        XCTAssertFalse(store.item(syncId: "__q_flag__")?.hasFile ?? true)
        store.setHasFileFlag(syncId: "__q_flag__", hasFile: true)
        XCTAssertTrue(store.item(syncId: "__q_flag__")?.hasFile ?? false)
    }

    func testSetHasFileFlagUnknownIdNoOp() {
        let before = store.items.count
        store.setHasFileFlag(syncId: "__nonexistent__", hasFile: true)
        XCTAssertEqual(store.items.count, before)
    }
}

// ── LocalUpsertTests ──────────────────────────────────────────────────────────

@MainActor final class LocalUpsertTests: XCTestCase {

    private var store: StashStore!

    override func setUp() async throws {
        store = StashStore()
    }

    override func tearDown() async throws {
        store.deleteLocal(syncId: "__upsert_test__")
    }

    func testLocalUpsertReplacesNotDuplicates() {
        let item = makeSyncItem(id: "__upsert_test__", title: "Original")
        store.localUpsert(item)
        let countAfterInsert = store.items.count

        var updated = item
        updated.title = "Updated"
        store.localUpsert(updated)

        XCTAssertEqual(store.items.count, countAfterInsert,
            "localUpsert with the same id must replace the existing item, not insert a new one")

        let found = store.items.first { $0.id == "__upsert_test__" }
        XCTAssertEqual(found?.title, "Updated",
            "localUpsert must update the item's fields in place")
    }
}

// ── HasLocalFileTests ─────────────────────────────────────────────────────────

@MainActor final class HasLocalFileTests: XCTestCase {

    private var store: StashStore!

    override func setUp() async throws { store = StashStore() }

    override func tearDown() async throws {
        for id in ["__hf_link__", "__hf_text__", "__hf_img__"] {
            store.deleteLocal(syncId: id)
            try? FileManager.default.removeItem(at: store.fileURL(for: id))
        }
    }

    func testHasLocalFileFalseForLinkEvenWithBlobOnDisk() throws {
        // Write a real blob at the link item's fileURL to prove the isLinkOrText guard
        // short-circuits before the FileManager check.
        let link = makeSyncItem(id: "__hf_link__", type: "link",
                                content: "https://example.com", title: "Example")
        store.localUpsert(link)
        try Data("blob".utf8).write(to: store.fileURL(for: link.id))
        XCTAssertFalse(store.hasLocalFile(link),
            "hasLocalFile must return false for link-type items even when a file exists on disk")
    }

    func testHasLocalFileFalseForTextEvenWithBlobOnDisk() throws {
        let text = makeSyncItem(id: "__hf_text__", type: "text",
                                content: "some text", title: "Note")
        store.localUpsert(text)
        try Data("blob".utf8).write(to: store.fileURL(for: text.id))
        XCTAssertFalse(store.hasLocalFile(text),
            "hasLocalFile must return false for text-type items even when a file exists on disk")
    }

    func testHasLocalFileTrueForImageWithBlob() throws {
        let img = makeSyncItem(id: "__hf_img__", type: "image", content: "photo.jpg")
        store.localUpsert(img)
        XCTAssertFalse(store.hasLocalFile(img), "must be false before file is written")
        try Data("blobdata".utf8).write(to: store.fileURL(for: img.id))
        XCTAssertTrue(store.hasLocalFile(img), "must be true after blob is written to disk")
    }
}
