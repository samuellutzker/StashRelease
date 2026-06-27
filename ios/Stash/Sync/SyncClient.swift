import Foundation
import Combine

/// WebSocket sync client for iOS, mirroring the Android `StashSyncClient` against the same
/// Rust server protocol: challenge/response auth (SHA-256 hash over Argon2id), sync_meta
/// reconciliation, push_item, and chunked file transfer with resume + SHA-256 integrity verification.
///
/// Concurrency: the class is `@MainActor`. The receive loop and each upload run as separate
/// `Task`s; because every step suspends at `await`, they interleave cooperatively on the main
/// actor (e.g. an upload can await the `upload_state` reply that the receive loop delivers).
///
/// NOTE (first version / TODO): chunk disk I/O runs on the main actor for simplicity. For very
/// large transfers this should move to a background executor to avoid UI hitches.
@MainActor
final class SyncClient: ObservableObject {
    @Published private(set) var isConnected = false
    /// syncId → progress in 0...1, or a negative value to signal failure.
    @Published private(set) var progress: [String: Double] = [:]

    private let store: StashStore
    private let chunkSize = 262_144  // 256 KiB — must match server CHUNK_SIZE
    private let maxConcurrentUploads = 3
    private let maxConcurrentDownloads = 3

    // Session carries the TLS pinning delegate so wss:// connections pin the server's self-signed
    // cert. For ws:// the delegate's server-trust handler is simply never invoked.
    private let pinningDelegate = TLSPinningDelegate()
    private lazy var session = URLSession(configuration: .default,
                                          delegate: pinningDelegate, delegateQueue: nil)
    private var task: URLSessionWebSocketTask?
    private var receiveLoop: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var authenticated = false

    private var uploadStateWaiters: [String: (cont: CheckedContinuation<Int, Never>, timeoutTask: Task<Void, Never>)] = [:]
    private var activeDownloads: Set<String> = []
    private var activeUploads: Set<String> = []   // single-flight per id (no double upload)
    private var downloadTotals: [String: Int] = [:]
    private var verifyRetries: Set<String> = []

    // Upload semaphore: at most maxConcurrentUploads files stream chunks at once. Without this,
    // N files launch N simultaneous Task-based uploads whose chunks interleave on the socket,
    // causing progress bars to jump backwards (same fix as Android's uploadSemaphore).
    private var uploadPermits: Int = 3
    private var uploadWaiters: [CheckedContinuation<Void, Never>] = []

    // Download queue: at most maxConcurrentDownloads request_file messages outstanding.
    // Without this, all downloads start simultaneously and fight over the socket.
    private var downloadsInFlight: Set<String> = []
    private var downloadQueue: [String] = []

    init(store: StashStore) {
        self.store = store
        store.onDuplicateSyncId = { [weak self] duplicateId in
            self?.task.map { t in
                Task { [weak self] in await self?.send(.deleteItem(id: duplicateId), on: t) }
            }
        }
    }

    // MARK: - Lifecycle

    func start() {
        guard SyncSettings.isConfigured, SyncSettings.syncEnabled else { return }
        connect()
    }

    func stop() {
        reconnectTask?.cancel()
        receiveLoop?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        authenticated = false
        isConnected = false
        downloadsInFlight.removeAll()
        downloadQueue.removeAll()
        // Wake any tasks blocked on the upload semaphore so they can exit cleanly.
        while !uploadWaiters.isEmpty {
            uploadWaiters.removeFirst().resume()
        }
        uploadPermits = maxConcurrentUploads
    }

    func restart() { stop(); start() }

    private func connect() {
        guard let url = SyncSettings.webSocketURL else { return }
        authenticated = false
        let t = session.webSocketTask(with: url)
        task = t
        t.resume()
        receiveLoop = Task { [weak self] in await self?.receiveMessages(t) }
    }

    private func scheduleReconnect() {
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard let self, !Task.isCancelled, SyncSettings.isConfigured else { return }
            self.connect()
        }
    }

    // MARK: - Receive loop

    private func receiveMessages(_ t: URLSessionWebSocketTask) async {
        while !Task.isCancelled {
            do {
                let message = try await t.receive()
                switch message {
                case .string(let s): await handle(text: s, t: t)
                case .data(let d): if let s = String(data: d, encoding: .utf8) { await handle(text: s, t: t) }
                @unknown default: break
                }
            } catch {
                isConnected = false
                authenticated = false
                if SyncSettings.isConfigured { scheduleReconnect() }
                return
            }
        }
    }

    @discardableResult
    private func send(_ msg: ClientMessage, on t: URLSessionWebSocketTask) async -> Bool {
        do { try await t.send(.string(msg.json())); return true }
        catch { return false }
    }

    // MARK: - Message handling

    private func handle(text: String, t: URLSessionWebSocketTask) async {
        switch ServerMessage.parse(text) {
        case .challenge:
            let knownIds = store.items.map { $0.id }.filter { !$0.isEmpty }
            await send(.auth(hash: SyncSettings.passwordHash, knownIds: knownIds), on: t)

        case .authOk:
            authenticated = true
            isConnected = true
            await performInitialSync(t)

        case .authFail:
            isConnected = false

        case .syncMeta(let items):
            if authenticated { await mergeServerItems(items, t) }

        case .itemAdded(let item):
            store.mergeRemote(item)
            if item.hasFile && !store.hasLocalFile(item) && SyncSettings.autoDownload {
                requestFile(item.id)
            }

        case .itemDeleted(let id):
            store.deleteRemote(syncId: id)
            activeDownloads.remove(id)

        case .fileChunk(let id, let chunk, let index, let total):
            handleFileChunk(id: id, chunkB64: chunk, index: index, total: total)

        case .fileTransferComplete(let id):
            await handleTransferComplete(id, t)

        case .fileVerifyFailed(let id):
            await handleVerifyFailed(id, t)

        case .uploadState(let id, let received):
            if let entry = uploadStateWaiters.removeValue(forKey: id) {
                entry.timeoutTask.cancel()
                entry.cont.resume(returning: received)
            }

        case .fileUnavailable(let id):
            // File not ready on server yet (e.g. mirror still pulling it). Free the slot and
            // mark failed — user can retry manually. Without this the slot stayed occupied forever.
            activeDownloads.remove(id)
            downloadsInFlight.remove(id)
            progress[id] = -1
            pumpDownloads()

        case .error(let message):
            print("[sync] server error: \(message)")

        case .unknown:
            break
        }
    }

    private func performInitialSync(_ t: URLSessionWebSocketTask) async {
        // Always advertise has_file=false: the server is the source of truth for file presence.
        let metas = store.items.map { item -> SyncItem in
            var m = item
            m.hasFile = false
            return m
        }
        await send(.syncMeta(items: metas), on: t)
        resumeDownloads()
    }

    private func mergeServerItems(_ serverItems: [SyncItem], _ t: URLSessionWebSocketTask) async {
        let serverHasFile = Set(serverItems.filter { $0.hasFile }.map { $0.id })
        for s in serverItems { store.mergeRemote(s) }

        // Upload any local file the server doesn't have yet (each in its own task so the
        // receive loop stays free to deliver upload_state replies).
        for item in store.items
        where !item.isLinkOrText && store.hasLocalFile(item) && !serverHasFile.contains(item.id) {
            let captured = item
            Task { [weak self] in await self?.uploadFile(item: captured, t: t) }
        }

        // Bulk auto-download: pull files the server has that we don't, when the user opted in.
        if SyncSettings.autoDownload {
            for item in store.items
            where !item.isLinkOrText && !store.hasLocalFile(item) && serverHasFile.contains(item.id) {
                requestFile(item.id)
            }
        }
    }

    private func resumeDownloads() {
        // Dead socket's streams are gone — clear in-flight state and re-queue everything wanted.
        downloadsInFlight.removeAll()
        downloadQueue.removeAll()
        for id in activeDownloads { downloadQueue.append(id) }
        pumpDownloads()
    }

    // MARK: - Public API (UI)

    /// Push a locally created/edited item. Uploads its file too unless this is a metadata-only edit.
    func pushItem(_ item: SyncItem, uploadFile uploadFileToo: Bool = true) {
        guard let t = task else { return }
        Task { [weak self] in
            guard let self else { return }
            var meta = item
            meta.hasFile = false
            await self.send(.pushItem(item: meta), on: t)
            if uploadFileToo, !item.isLinkOrText, self.store.hasLocalFile(item) {
                await self.uploadFile(item: item, t: t)
            }
        }
    }

    func deleteItem(syncId: String) {
        store.deleteLocal(syncId: syncId)
        guard let t = task else { return }
        Task { [weak self] in await self?.send(.deleteItem(id: syncId), on: t) }
    }

    func download(_ item: SyncItem) {
        guard authenticated else { return }
        requestFile(item.id)
    }

    // MARK: - Upload semaphore

    private func acquireUpload() async {
        if uploadPermits > 0 { uploadPermits -= 1; return }
        await withCheckedContinuation { uploadWaiters.append($0) }
    }

    private func releaseUpload() {
        if let w = uploadWaiters.first {
            uploadWaiters.removeFirst()
            w.resume()   // passes the permit directly to the next waiter
        } else {
            uploadPermits += 1
        }
    }

    // MARK: - Downloads

    /// Idempotent: a second request for the same id is dropped. The download is queued and
    /// admitted by pumpDownloads() up to maxConcurrentDownloads at a time.
    private func requestFile(_ id: String) {
        guard activeDownloads.insert(id).inserted else { return }
        progress[id] = 0
        downloadQueue.append(id)
        pumpDownloads()
    }

    /// Admit queued downloads up to the concurrency cap. Safe to call from any point on the actor.
    private func pumpDownloads() {
        guard let t = task else { return }
        while downloadsInFlight.count < maxConcurrentDownloads, !downloadQueue.isEmpty {
            let id = downloadQueue.removeFirst()
            guard activeDownloads.contains(id) else { continue }  // cancelled meanwhile
            downloadsInFlight.insert(id)
            let fromChunk = consecutiveChunksOnDisk(id)
            Task { [weak self] in await self?.send(.requestFile(id: id, fromChunk: fromChunk), on: t) }
        }
    }

    private func handleFileChunk(id: String, chunkB64: String, index: Int, total: Int) {
        guard let data = Data(base64Encoded: chunkB64) else { return }
        let part = store.partURL(for: id)

        // Reset stale on-disk data if the server's total changed (file re-uploaded at new size).
        if let prev = downloadTotals[id], prev != total {
            try? FileManager.default.removeItem(at: part)
            removeMeta(id)
        }
        downloadTotals[id] = total

        writeChunk(to: part, offset: Int64(index) * Int64(chunkSize), data: data)
        writeMeta(id, total: total, consecutive: index + 1)
        progress[id] = total > 0 ? min(1.0, Double(index + 1) / Double(total)) : 1.0
    }

    private func handleTransferComplete(_ id: String, _ t: URLSessionWebSocketTask) async {
        verifyRetries.remove(id)            // also fired for our own (verified) uploads
        activeDownloads.remove(id)
        downloadsInFlight.remove(id)
        downloadTotals[id] = nil
        defer { pumpDownloads() }

        let part = store.partURL(for: id)
        guard FileManager.default.fileExists(atPath: part.path) else {
            // No local part → this is the server confirming our upload. Mark it as held by the
            // server so it isn't uploaded again (matches Android's serverHasFile gate).
            store.setHasFileFlag(syncId: id, hasFile: true)
            progress[id] = nil
            return
        }

        // Integrity: discard a downloaded file whose hash doesn't match the item's recorded hash.
        if let item = store.item(syncId: id), !item.fileHash.isEmpty {
            if StashCrypto.fileSHA256(at: part) != item.fileHash {
                try? FileManager.default.removeItem(at: part)
                removeMeta(id)
                progress[id] = -1
                return
            }
        }

        let dest = store.fileURL(for: id)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: part, to: dest)
            removeMeta(id)
            store.setHasFileFlag(syncId: id, hasFile: true)
            progress[id] = nil
        } catch {
            progress[id] = -1
        }
    }

    // MARK: - Uploads

    private func uploadFile(item: SyncItem, t: URLSessionWebSocketTask) async {
        // Single-flight: never upload the same file twice at once.
        guard activeUploads.insert(item.id).inserted else { return }
        defer { activeUploads.remove(item.id) }

        // Bound concurrent chunk streams so they don't interleave excessively on the socket
        // (same fix as Android's uploadSemaphore — without this, N files launch N simultaneous
        // streams whose chunks race each other, causing progress bars to jump backwards).
        await acquireUpload()
        defer { releaseUpload() }

        let url = store.fileURL(for: item.id)
        guard let size = fileSize(url), size > 0 else { return }
        let total = max(1, Int((size + Int64(chunkSize) - 1) / Int64(chunkSize)))

        let startChunk = await queryUploadState(item.id, total: total, t: t)
        if startChunk >= total { progress[item.id] = nil; return }

        guard let handle = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? handle.close() }
        if startChunk > 0 { try? handle.seek(toOffset: UInt64(startChunk) * UInt64(chunkSize)) }

        var index = startChunk
        while true {
            let data = (try? handle.read(upToCount: chunkSize)) ?? nil
            guard let data, !data.isEmpty else { break }
            // Awaiting each send gives natural backpressure (completes when buffered to the socket).
            let ok = await send(.pushFileChunk(id: item.id, chunk: data.base64EncodedString(),
                                               index: index, total: total), on: t)
            if !ok { break }
            progress[item.id] = Double(index + 1) / Double(total)
            index += 1
        }
        progress[item.id] = nil
    }

    /// Ask the server how many consecutive chunks it already has. Resolves on the upload_state
    /// reply or a 5s timeout (→ 0, full restart). `total` lets the server reset if the chunk
    /// layout changed between versions.
    private func queryUploadState(_ id: String, total: Int, t: URLSessionWebSocketTask) async -> Int {
        await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            let timeoutTask = Task { [weak self] in
                guard let self else { return }
                await self.send(.queryUploadState(id: id, total: total), on: t)
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if let entry = self.uploadStateWaiters.removeValue(forKey: id) {
                    entry.cont.resume(returning: 0)
                }
            }
            uploadStateWaiters[id] = (cont: cont, timeoutTask: timeoutTask)
        }
    }

    private func handleVerifyFailed(_ id: String, _ t: URLSessionWebSocketTask) async {
        progress[id] = -1
        guard let item = store.item(syncId: id), store.hasLocalFile(item) else { return }
        if verifyRetries.insert(id).inserted {
            Task { [weak self] in await self?.uploadFile(item: item, t: t) }
        } else {
            verifyRetries.remove(id)
        }
    }

    // MARK: - On-disk transfer bookkeeping

    private func writeChunk(to url: URL, offset: Int64, data: Data) {
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        try? handle.seek(toOffset: UInt64(offset))
        try? handle.write(contentsOf: data)
    }

    private func metaURL(_ id: String) -> URL { store.partsDir.appendingPathComponent(id + ".meta") }

    private func writeMeta(_ id: String, total: Int, consecutive: Int) {
        try? "\(total):\(consecutive)".write(to: metaURL(id), atomically: true, encoding: .utf8)
    }

    private func removeMeta(_ id: String) { try? FileManager.default.removeItem(at: metaURL(id)) }

    private func consecutiveChunksOnDisk(_ id: String) -> Int {
        guard let s = try? String(contentsOf: metaURL(id), encoding: .utf8) else { return 0 }
        let parts = s.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
        return parts.count == 2 ? Int(parts[1]) ?? 0 : 0
    }

    private func fileSize(_ url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? nil
    }
}
