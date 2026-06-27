import Foundation
import Combine

/// Local persistence: item metadata in a JSON file, file blobs on disk keyed by syncId.
///
/// First-version storage is deliberately simple (Codable + FileManager, no external deps).
/// A future iteration could move to GRDB/Core Data; the public API here is the seam for that.
@MainActor
final class StashStore: ObservableObject {
    @Published private(set) var items: [SyncItem] = []

    /// Called when mergeRemote deduplicates a server item whose content already exists locally.
    /// The argument is the server's duplicate syncId that should be deleted from the server.
    var onDuplicateSyncId: ((String) -> Void)?

    private let baseDir: URL
    let filesDir: URL
    let partsDir: URL
    private let linksDir: URL
    private let itemsFile: URL

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        baseDir = support.appendingPathComponent("Stash", isDirectory: true)
        filesDir = baseDir.appendingPathComponent("files", isDirectory: true)
        partsDir = baseDir.appendingPathComponent("parts", isDirectory: true)
        linksDir = baseDir.appendingPathComponent("links", isDirectory: true)
        itemsFile = baseDir.appendingPathComponent("items.json")
        for dir in [baseDir, filesDir, partsDir, linksDir] {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        load()
    }

    // MARK: - File locations

    func fileURL(for id: String) -> URL { filesDir.appendingPathComponent(id) }
    func partURL(for id: String) -> URL { partsDir.appendingPathComponent(id + ".part") }

    /// The stored blob exposed under its real filename + extension. Blobs are saved as `files/<id>`
    /// with no extension, which makes AVFoundation show a black player and Share/QuickLook use the
    /// raw syncId as the name. A cheap hard link (per-item folder so names can't collide) gives us a
    /// URL with the correct name + extension for playback and sharing.
    func namedFileURL(for item: SyncItem) -> URL? {
        let blob = fileURL(for: item.id)
        guard FileManager.default.fileExists(atPath: blob.path) else { return nil }
        let rawName = item.content.isEmpty ? item.id : item.content   // content = original filename
        let name = rawName.replacingOccurrences(of: "/", with: "_")
        let dir = linksDir.appendingPathComponent(item.id, isDirectory: true)
        let dest = dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: dest.path) { return dest }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        do { try FileManager.default.linkItem(at: blob, to: dest) }
        catch { try? FileManager.default.copyItem(at: blob, to: dest) }
        return FileManager.default.fileExists(atPath: dest.path) ? dest : blob
    }

    /// Whether this item's file is present on this device. (link/text never have a file.)
    func hasLocalFile(_ item: SyncItem) -> Bool {
        if item.isLinkOrText { return false }
        return FileManager.default.fileExists(atPath: fileURL(for: item.id).path)
    }

    // MARK: - Queries

    func item(syncId: String) -> SyncItem? { items.first { $0.id == syncId } }
    func item(fileHash: String) -> SyncItem? {
        guard !fileHash.isEmpty else { return nil }
        return items.first { $0.fileHash == fileHash }
    }

    // MARK: - Local mutations (user-initiated)

    /// Insert or replace an item created/edited locally and persist. Caller sets updatedAt.
    func localUpsert(_ item: SyncItem) {
        if let idx = items.firstIndex(where: { $0.id == item.id }) {
            items[idx] = item
        } else {
            items.insert(item, at: 0)
        }
        persist()
    }

    func deleteLocal(syncId: String) {
        items.removeAll { $0.id == syncId }
        try? FileManager.default.removeItem(at: fileURL(for: syncId))
        try? FileManager.default.removeItem(at: linksDir.appendingPathComponent(syncId, isDirectory: true))
        persist()
    }

    // MARK: - Remote merges (from the server)

    /// Apply a server item. Inserts if new; updates mutable metadata only if strictly newer
    /// (last-writer-wins, matching the server's updated_at logic). Never overwrites local
    /// file content for file items. Returns true if anything changed.
    @discardableResult
    func mergeRemote(_ remote: SyncItem) -> Bool {
        if let idx = items.firstIndex(where: { $0.id == remote.id }) {
            let local = items[idx]
            guard remote.effectiveUpdatedAt > local.effectiveUpdatedAt else { return false }
            var merged = local
            merged.title = remote.title
            merged.isPinned = remote.isPinned
            merged.updatedAt = remote.updatedAt
            if local.isLinkOrText { merged.content = remote.content }
            if !remote.thumbnailB64.isEmpty { merged.thumbnailB64 = remote.thumbnailB64 }
            items[idx] = merged
            persist()
            return true
        } else {
            // Dedup by file hash (an item we already have under a different syncId). Keep our
            // existing id and signal the caller to delete the server's duplicate — previously we
            // adopted the incoming id, but that caused reappearance after deletion (deleting id B
            // only removed B; the old id A was still on the server and resurfaced on next sync).
            if !remote.fileHash.isEmpty, let existingIdx = items.firstIndex(where: { $0.fileHash == remote.fileHash }) {
                let existingId = items[existingIdx].id
                if !existingId.isEmpty && existingId != remote.id {
                    onDuplicateSyncId?(remote.id)
                }
                persist()
                return true
            }
            items.insert(remote, at: 0)
            persist()
            return true
        }
    }

    func deleteRemote(syncId: String) {
        deleteLocal(syncId: syncId)
    }

    func setHasFileFlag(syncId: String, hasFile: Bool) {
        guard let idx = items.firstIndex(where: { $0.id == syncId }) else { return }
        items[idx].hasFile = hasFile
        persist()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: itemsFile),
              let decoded = try? JSONDecoder().decode([SyncItem].self, from: data) else { return }
        items = decoded
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: itemsFile, options: .atomic)
    }
}
