package com.stashapp.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stashapp.data.StashDatabase
import com.stashapp.data.StashItem
import com.stashapp.data.StashRepository
import com.stashapp.utils.FileStorage
import com.stashapp.sync.core.ItemStore
import com.stashapp.sync.core.SyncReducer
import com.stashapp.sync.core.shouldAutoDownload
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "StashSyncClient"
// 256 KiB — must match the server's CHUNK_SIZE. Larger chunks cut per-byte message,
// JSON and base64 overhead for big media transfers.
private const val CHUNK_SIZE = 262144
// How long a delete tombstone suppresses resurrection before it is pruned. Comfortably longer
// than any reconnect/sync_meta round-trip, short enough not to block re-creating the same id.
private const val TOMBSTONE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
// Max simultaneous download streams. Kept small so many auto-downloads can't saturate the
// single WebSocket; the rest queue and are pumped as slots free up.
private const val MAX_CONCURRENT_DOWNLOADS = 3
// How many times to auto-retry a download the server reports as not-yet-available (e.g. a mirror
// still pulling the file) before surfacing "Download failed".
private const val MAX_DOWNLOAD_RETRIES = 6
private const val DOWNLOAD_RETRY_DELAY_MS = 4000L
// Max simultaneous upload streams. Parallel for throughput, but bounded so they can't overflow
// OkHttp's outgoing queue and tear the socket down. Per-id dedup still prevents the same file
// from being uploaded twice at once (which caused the progress "skips and jumps").
private const val MAX_CONCURRENT_UPLOADS = 3

// Progress sentinels (otherwise a 0..100 percentage). Shared with StashAdapter rendering.
const val PROGRESS_FAILED = -1
const val PROGRESS_QUEUED = -2

class StashSyncClient(private val ctx: Context) {

    private val _state = MutableLiveData(SyncState.DISABLED)
    val state: LiveData<SyncState> = _state

    /** syncId -> upload progress 0-100 */
    private val _uploadProgress = MutableLiveData<Map<String, Int>>(emptyMap())
    val uploadProgress: LiveData<Map<String, Int>> = _uploadProgress

    /** syncId -> download progress 0-100 */
    private val _downloadProgress = MutableLiveData<Map<String, Int>>(emptyMap())
    val downloadProgress: LiveData<Map<String, Int>> = _downloadProgress

    // Authoritative progress maps. With several uploads/downloads running at once, a read-modify-
    // write on the LiveData's value loses updates (postValue is async, .value lags) — that left
    // finished uploads' bars stuck on screen. These guarded maps are the source of truth; the
    // LiveData just receives snapshots.
    private val progressLock = Any()
    private val uploadProgressMap = HashMap<String, Int>()
    private val downloadProgressMap = HashMap<String, Int>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Single-threaded dispatcher for incoming file-transfer messages. OkHttp delivers onMessage
    // in order, so FIFO dispatch here guarantees a file's chunks are all written before its
    // FileTransferComplete is processed — otherwise concurrent coroutines could finalize a file
    // before its last chunks land, producing a short/corrupt file (caught by the hash check as a
    // spurious "download failed", especially when auto-downloading many files at once).
    private val transferDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val repo by lazy {
        val db = StashDatabase.getDatabase(ctx)
        StashRepository(db.stashDao(), db.pendingOpDao())
    }

    // Rebuilt per connect so a TLS settings change takes effect on reconnect.
    private fun buildHttpClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
        if (SyncSettings.isUseTls(ctx)) TlsTrust.apply(b, ctx)
        return b.build()
    }

    @Volatile private var httpClient: OkHttpClient = buildHttpClient()

    @Volatile private var ws: WebSocket? = null
    private var reconnectJob: Job? = null

    // In-progress downloads: syncId -> (chunksReceived, total)
    // Chunk data is written directly to disk; no in-memory buffer to avoid OOM on large files.
    private val incomingChunks = ConcurrentHashMap<String, Pair<Int, Int>>()

    // Downloads the user/auto-sync wants; re-issued after every reconnect. A want stays here
    // until the file lands or the user gives up — it is NOT the same as "currently streaming".
    private val activeDownloadRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Download admission control. The server begins streaming the moment it receives request_file,
    // so issuing request_file for every wanted item at once made N server streams fight over one
    // socket — the "progress jumping back and forth" + "download failed" the user reported. We
    // stream at most MAX_CONCURRENT_DOWNLOADS at a time and queue the rest, pumping the next when
    // one finishes. All three structures are guarded by [downloadGate].
    private val downloadGate = Any()
    private val downloadsInFlight: MutableSet<String> = HashSet()
    private val downloadWaiting: ArrayDeque<String> = ArrayDeque()
    // Per-id retry count for transient "file not on server yet" (file_unavailable) responses.
    private val downloadRetries = ConcurrentHashMap<String, Int>()

    // Uploads currently streaming or queued (dedup): the same file must never be uploaded twice
    // concurrently from the several triggers (stash, reconnect flush, sync_meta reconcile,
    // verify-retry) — that doubled progress reporting and produced the "skips and jumps".
    // uploadSemaphore bounds how many DISTINCT files stream at once so they stay fast without
    // overflowing the socket.
    private val activeUploads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val uploadSemaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_UPLOADS)

    // Upload state queries waiting for server response: syncId -> deferred resume chunk index
    private val pendingUploadStateQueries = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    // syncIds for which we've already retried once after a server hash-verify rejection.
    private val verifyRetries: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Platform-independent apply logic (dedup, last-writer-wins edits, tombstones). Unit-tested
    // on the JVM via SyncReducerTest; the app drives it through the same code path here.
    private val reducer: SyncReducer by lazy {
        SyncReducer(
            store = object : ItemStore {
                override suspend fun getBySyncId(syncId: String) = repo.getBySyncId(syncId)
                override suspend fun getByFileHash(hash: String) = repo.getByFileHash(hash)
                override suspend fun insert(item: StashItem) = repo.insert(item)
                override suspend fun updateSyncId(localId: Long, syncId: String) = repo.updateSyncId(localId, syncId)
                override suspend fun applyRemoteEdit(
                    syncId: String, title: String, content: String, isPinned: Boolean, updatedAt: Long
                ) = repo.applyRemoteEdit(syncId, title, content, isPinned, updatedAt)
                override suspend fun hasDeleteTombstone(syncId: String) = repo.hasDeleteTombstone(syncId)
            },
            sendDelete = { id -> ws?.send(ClientMsg.deleteItem(id)) },
        )
    }

    // SyncIds the client already has — sent in Auth so server skips thumbnails for them
    @Volatile private var knownSyncIds: List<String> = emptyList()

    private val activeTransferCount = AtomicInteger(0)

    private fun beginTransfer() { activeTransferCount.getAndIncrement() }
    private fun endTransfer() { if (activeTransferCount.decrementAndGet() < 0) activeTransferCount.set(0) }

    @Volatile private var connected = false

    /** True while authenticated and connected. Safe to read from any thread. */
    val isConnected: Boolean get() = connected

    /** Number of uploads/downloads currently in flight. Safe to read from any thread. */
    val activeTransfers: Int get() = activeTransferCount.get()

    private val downloadsDir: File by lazy {
        ctx.filesDir.resolve("stash_downloads").also { it.mkdirs() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (!SyncSettings.isConfigured(ctx)) { _state.postValue(SyncState.DISABLED); return }
        // Idempotent: if a connection is already up or being established, don't open a second one.
        // SyncWorker and the app share this single client and both call start(); without this guard
        // a share/foreground while already connected spun up a new socket and dropped the live one
        // — the "disconnects for a few seconds then continues" the user saw mid-upload.
        if (connected || ws != null) { TransferLog.log("start() ignored — already connected/connecting"); return }
        TransferLog.init(ctx)
        loadPartialDownloadsFromDisk()
        // Load known syncIds so we can send them in the Auth message to skip redundant thumbnails
        scope.launch {
            knownSyncIds = repo.getAllSync().map { it.syncId }.filter { it.isNotBlank() }
            scheduleConnect(0)
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        ws?.close(1000, "stopped")
        ws = null
        connected = false
        _state.postValue(SyncState.DISABLED)
    }

    fun close() {
        stop()
        scope.cancel()
    }

    fun restart() { stop(); start() }

    private fun scheduleConnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            connect()
        }
    }

    private fun connect() {
        if (!SyncSettings.isConfigured(ctx)) return
        if (ws != null) { Log.d(TAG, "connect() skipped — socket already exists"); return }
        _state.postValue(SyncState.CONNECTING)
        val scheme = if (SyncSettings.isUseTls(ctx)) "wss" else "ws"
        val url = "$scheme://${SyncSettings.getHost(ctx)}:${SyncSettings.getPort(ctx)}"
        Log.d(TAG, "Connecting to $url")
        httpClient = buildHttpClient()
        ws = httpClient.newWebSocket(Request.Builder().url(url).build(), makeListener())
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private fun makeListener() = object : WebSocketListener() {
        private var authenticated = false

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val msg = ServerMsg.parse(text)) {
                is ServerMsg.Challenge -> {
                    webSocket.send(ClientMsg.auth(SyncSettings.getPasswordHash(ctx), knownSyncIds))
                }
                is ServerMsg.AuthOk -> {
                    authenticated = true
                    connected = true
                    _state.postValue(SyncState.CONNECTED)
                    TransferLog.log("connected + authenticated")
                    scope.launch {
                        // Flush durable offline ops first so deletes land before the initial
                        // sync_meta round-trip could otherwise resurrect a deleted item.
                        flushPendingOps(webSocket)
                        performInitialSync(webSocket)
                        resumeActiveDownloads()
                    }
                }
                is ServerMsg.AuthFail -> {
                    Log.w(TAG, "Auth failed — wrong password?")
                    connected = false
                    webSocket.close(1000, "auth failed")
                    _state.postValue(SyncState.DISCONNECTED)
                }
                is ServerMsg.SyncMeta -> {
                    if (authenticated) scope.launch { mergeServerItems(msg.items, webSocket) }
                }
                is ServerMsg.ItemAdded -> scope.launch { handleRemoteItemAdded(msg.item) }
                is ServerMsg.ItemDeleted -> scope.launch { handleRemoteItemDeleted(msg.id) }
                // Serialized on transferDispatcher so chunks are written in arrival order and a
                // file is never finalized before all its chunks have landed.
                is ServerMsg.FileChunk -> scope.launch(transferDispatcher) { handleFileChunk(msg) }
                is ServerMsg.FileTransferComplete -> scope.launch(transferDispatcher) { handleFileTransferComplete(msg.id) }
                is ServerMsg.FileVerifyFailed -> scope.launch { handleFileVerifyFailed(msg.id) }
                is ServerMsg.UploadState -> {
                    pendingUploadStateQueries[msg.id]?.complete(msg.received)
                }
                is ServerMsg.FileUnavailable -> scope.launch { handleFileUnavailable(msg.id) }
                is ServerMsg.Error -> {
                    // Generic errors (invalid id, file too large, bad chunk) are not tied to a
                    // specific download — don't touch download state. File-not-ready is reported
                    // separately via FileUnavailable.
                    Log.w(TAG, "Server error: ${msg.message}")
                    TransferLog.log("server error: ${msg.message}")
                }
                else -> {}
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            TransferLog.log("socket FAILURE: ${t.message} (resp=${response?.code})")
            ws = null
            connected = false
            _state.postValue(if (SyncSettings.isConfigured(ctx)) SyncState.DISCONNECTED else SyncState.DISABLED)
            if (SyncSettings.isConfigured(ctx)) scheduleConnect(5000)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $reason")
            TransferLog.log("socket closed: code=$code reason=$reason")
            ws = null
            connected = false
            _state.postValue(if (SyncSettings.isConfigured(ctx)) SyncState.DISCONNECTED else SyncState.DISABLED)
            if (SyncSettings.isConfigured(ctx) && code != 1000) scheduleConnect(5000)
        }
    }

    // ── Sync logic ────────────────────────────────────────────────────────────

    /**
     * Flush the durable pending-ops queue in insertion order. Upserts re-push item metadata
     * (and the file, for newly-stashed items); deletes send delete_item. Each op is removed
     * only after it has been handed to the socket. Delete tombstones are kept past the flush
     * (see [handleRemoteItemDeleted]) so a concurrent sync_meta can't resurrect the item; the
     * tombstone is cleared when the server confirms the deletion or after a bounded age.
     */
    private suspend fun flushPendingOps(webSocket: WebSocket) {
        val ops = repo.getPendingOps()
        if (ops.isEmpty()) return
        Log.d(TAG, "Flushing ${ops.size} pending ops")
        for (op in ops) {
            when (op.opType) {
                com.stashapp.data.PendingOp.OP_DELETE -> {
                    // Send the delete but KEEP the row: it doubles as a tombstone that
                    // suppresses resurrection from a later sync_meta until the server
                    // confirms the deletion (handleRemoteItemDeleted) or it ages out.
                    // Re-sending delete_item on each reconnect is idempotent server-side.
                    webSocket.send(ClientMsg.deleteItem(op.syncId))
                }
                com.stashapp.data.PendingOp.OP_UPSERT -> {
                    val item = repo.getBySyncId(op.syncId)
                    if (item == null) { repo.deletePendingOp(op.id); continue }
                    webSocket.send(ClientMsg.pushItem(item.toSyncItem(sendHasFile = false)))
                    // Concurrent (bounded by uploadSemaphore); don't block the flush on the upload.
                    if (needsUpload(item)) scope.launch { uploadFile(webSocket, item.syncId, item.content) }
                    repo.deletePendingOp(op.id)
                }
            }
        }
        // Drop delete tombstones older than the bound — at this point a clean delete has
        // round-tripped, so keeping them forever would block legitimately re-created ids.
        pruneStaleTombstones()
    }

    private suspend fun pruneStaleTombstones() {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_MAX_AGE_MS
        repo.getPendingOps()
            .filter { it.opType == com.stashapp.data.PendingOp.OP_DELETE && it.timestamp < cutoff }
            .forEach { repo.deletePendingOp(it.id) }
    }

    private suspend fun performInitialSync(webSocket: WebSocket) {
        repo.getItemsWithoutSyncId().forEach { item ->
            repo.updateSyncId(item.id, UUID.randomUUID().toString())
        }
        val allLocal = repo.getAllSync()
        // Always send hasFile=false — server is the source of truth for file presence.
        webSocket.send(ClientMsg.syncMeta(allLocal.map { it.toSyncItem(sendHasFile = false) }))
    }

    /**
     * Re-admit every wanted download after a reconnect. Streams from the previous socket are
     * dead, so clear the in-flight set, re-queue all wanted ids, and let [pumpDownloads] restart
     * them under the concurrency cap (resuming from the chunks already on disk).
     */
    private fun resumeActiveDownloads() {
        val toResume = activeDownloadRequests.toSet()
        if (toResume.isEmpty()) return
        synchronized(downloadGate) {
            downloadsInFlight.clear()
            downloadWaiting.clear()
            toResume.forEach { if (it !in downloadWaiting) downloadWaiting.addLast(it) }
        }
        TransferLog.log("DL resume ${toResume.size} after reconnect")
        pumpDownloads()
    }

    /** Returns the number of consecutive chunks already on disk for this download. */
    private fun consecutiveChunksOnDisk(syncId: String): Int {
        return runCatching {
            val parts = downloadsDir.resolve("$syncId.meta").readText().trim().split(":")
            parts[1].toInt()
        }.getOrDefault(0)
    }

    private suspend fun mergeServerItems(serverItems: List<SyncItem>, webSocket: WebSocket) {
        val serverHasFile = serverItems.filter { it.hasFile }.map { it.id }.toSet()

        serverItems.forEach { serverItem -> applyServerItem(serverItem) }

        // Bidirectional serverHasFile reconciliation: this server's sync_meta is authoritative.
        // Set true for items the server reports as having; clear for file items it doesn't — this
        // handles switching to a new server that hasn't received the files yet, where the old
        // server's confirmed flag would otherwise permanently block re-upload.
        val allLocal = repo.getAllSync()
        allLocal.filter { it.type !in listOf("link", "text") && it.syncId.isNotBlank() }.forEach { item ->
            val serverReports = serverHasFile.contains(item.syncId)
            if (item.serverHasFile != serverReports) repo.setServerHasFile(item.syncId, serverReports)
        }

        // Re-fetch so needsUpload sees the corrected serverHasFile values.
        val reconciledLocal = repo.getAllSync()
        // Launch concurrently — uploadSemaphore bounds it to MAX_CONCURRENT_UPLOADS. Awaiting each
        // here instead would make uploads sequential (which defeated the parallelism).
        reconciledLocal.filter { item -> needsUpload(item) }.forEach { item ->
            scope.launch { uploadFile(webSocket, item.syncId, item.content) }
        }

        if (SyncSettings.isDownloadAllOnSync(ctx)) {
            reconciledLocal
                .filter { shouldAutoDownload(it, serverHasFile.contains(it.syncId)) }
                .forEach { requestFile(it.syncId) }
        }
    }

    /** Single source of truth for "this local file still needs to be uploaded to the server". */
    private fun needsUpload(item: StashItem): Boolean =
        item.fileLocal &&
        !item.serverHasFile &&
        !item.removedLocally &&
        item.syncId.isNotBlank() &&
        item.type !in listOf("link", "text") &&
        item.content.isNotBlank() &&
        File(item.content).exists()

    private suspend fun handleRemoteItemAdded(serverItem: SyncItem) {
        applyServerItem(serverItem)
        // The server broadcasts item_added once it actually holds the file — record that so our
        // local copy isn't re-uploaded and shows as synced.
        if (serverItem.hasFile) {
            repo.setServerHasFile(serverItem.id, true)
            // Auto-Download: if the user wants all files local, pull it now (reactive, same as iOS).
            if (SyncSettings.isDownloadAllOnSync(ctx)) {
                val local = repo.getBySyncId(serverItem.id)
                if (local != null && shouldAutoDownload(local, true)) requestFile(serverItem.id)
            }
        }
    }

    /**
     * Apply one item received from the server via the shared [reducer] (dedup, last-writer-wins,
     * tombstones), then — only for a brand-new insert — decode and store its thumbnail (the one
     * step that needs Android image APIs and so stays here rather than in the pure core).
     */
    private suspend fun applyServerItem(serverItem: SyncItem) {
        val result = reducer.apply(serverItem)
        if (result is SyncReducer.Result.Inserted && serverItem.thumbnailB64.isNotBlank()) {
            val path = decodeThumbnail(result.localId, serverItem.thumbnailB64)
            if (path.isNotBlank()) repo.updateThumbnailPath(result.localId, path)
        }
    }

    private suspend fun handleRemoteItemDeleted(syncId: String) {
        // Server confirmed the deletion — the tombstone has done its job, drop it.
        repo.clearTombstone(syncId)
        val item = repo.getBySyncId(syncId) ?: return
        if (item.fileLocal && item.type !in listOf("link", "text") && item.content.isNotBlank()) {
            runCatching { File(item.content).delete() }
        }
        repo.deleteBySyncId(syncId)
    }

    private fun handleFileChunk(msg: ServerMsg.FileChunk) {
        val existing = incomingChunks[msg.id]
        // Reset disk state if the server's total changed (file re-uploaded with different size).
        if (existing != null && existing.second != msg.total) {
            runCatching { downloadsDir.resolve("${msg.id}.part").delete() }
            runCatching { downloadsDir.resolve("${msg.id}.meta").delete() }
            incomingChunks.remove(msg.id)
        }

        val chunkBytes = Base64.decode(msg.chunk, Base64.DEFAULT)

        // Write chunk directly to the .part file — no in-memory buffer.
        runCatching {
            RandomAccessFile(downloadsDir.resolve("${msg.id}.part"), "rw").use { raf ->
                raf.seek(msg.index.toLong() * CHUNK_SIZE)
                raf.write(chunkBytes)
            }
        }

        val prevReceived = incomingChunks[msg.id]?.first ?: 0
        val received = prevReceived + 1
        incomingChunks[msg.id] = Pair(received, msg.total)

        // Consecutive count: since chunks arrive in order, consecutive == received while uninterrupted.
        val consecutive = msg.index + 1
        runCatching { downloadsDir.resolve("${msg.id}.meta").writeText("${msg.total}:$consecutive") }

        // Progress from the chunk's own (in-order) index rather than a running message count,
        // and clamped — robust even if a stray duplicate chunk slips through.
        val pct = if (msg.total > 0) ((msg.index + 1) * 100 / msg.total).coerceAtMost(100) else 100
        postProgress(_downloadProgress, msg.id, pct)
    }

    private suspend fun handleFileTransferComplete(syncId: String) {
        // Also fired for our own uploads (no local .part) — clear any retry guard on success.
        verifyRetries.remove(syncId)
        incomingChunks.remove(syncId)
        val wasTracked = synchronized(downloadGate) {
            syncId in downloadsInFlight || syncId in activeDownloadRequests
        }
        // Free the download slot (and admit the next) as soon as the stream is in; the local
        // finalize below is not a network transfer.
        if (wasTracked) {
            endTransfer()
            finishDownload(syncId, alsoForget = true)
        }

        val partFile = downloadsDir.resolve("$syncId.part")
        val metaFile = downloadsDir.resolve("$syncId.meta")

        if (!partFile.exists()) {
            // No local part: this is the completion ack for one of OUR uploads, not a download.
            if (wasTracked) {
                Log.w(TAG, "No data for completed download $syncId")
                TransferLog.log("DL no-data $syncId")
            } else {
                // Our upload was stored + verified by the server — mark it synced so no path
                // re-uploads it.
                repo.setServerHasFile(syncId, true)
                TransferLog.log("UP server-confirmed complete $syncId")
            }
            metaFile.delete()
            postProgress(_downloadProgress, syncId, null)
            return
        }
        metaFile.delete()

        val dir = ctx.filesDir.resolve("stash_files").also { it.mkdirs() }
        val file = dir.resolve(syncId)
        runCatching {
            java.nio.file.Files.move(
                partFile.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to move part file for $syncId: $e")
            TransferLog.log("DL finalize-move FAILED $syncId: $e")
            postProgress(_downloadProgress, syncId, null)
            return
        }

        val item = repo.getBySyncId(syncId)

        // Integrity check: a downloaded file whose hash doesn't match what the item
        // recorded is discarded and flagged, rather than being silently kept.
        if (item != null && item.fileHash.isNotBlank()) {
            // Hash off the serialized transfer dispatcher so it doesn't stall other downloads.
            val actual = withContext(Dispatchers.Default) { computeFileHash(file.absolutePath) }
            if (actual != item.fileHash) {
                Log.e(TAG, "Downloaded file hash mismatch for $syncId — discarding")
                TransferLog.log("DL HASH MISMATCH $syncId (want=${item.fileHash.take(12)} got=${actual.take(12)})")
                runCatching { file.delete() }
                postProgress(_downloadProgress, syncId, -1)
                return
            }
        }

        var thumbnailPath = item?.thumbnailPath ?: ""

        if (item != null) {
            when (item.type) {
                "image" -> {
                    // Decode downsampled to a small thumbnail — NOT the full image. Saving the
                    // full-resolution bitmap here is what produced oversized "thumbnails" that
                    // later crashed the list with "Canvas: trying to draw too large bitmap".
                    val bmp = com.stashapp.utils.BitmapUtils.decodeBounded(file.absolutePath, 512)
                    if (bmp != null) {
                        thumbnailPath = runCatching {
                            FileStorage.saveThumbnail(ctx, bmp, item.id).absolutePath
                        }.getOrDefault(thumbnailPath)
                    }
                }
                "video" -> runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    if (frame != null) {
                        val thumb = android.graphics.Bitmap.createScaledBitmap(frame, 200, 200, true)
                        thumbnailPath = FileStorage.saveThumbnail(ctx, thumb, item.id).absolutePath
                        frame.recycle()
                    }
                }
            }
        }

        repo.updateFileLocalBySyncId(syncId, fileLocal = true, content = file.absolutePath, thumbnailPath = thumbnailPath, fileSize = file.length())
        postProgress(_downloadProgress, syncId, null)
        Log.d(TAG, "Downloaded $syncId (${file.length()} bytes)")
        TransferLog.log("DL ok $syncId (${file.length()} bytes)")
    }

    /** Load in-progress downloads from disk so they can resume after app restart. */
    private fun loadPartialDownloadsFromDisk() {
        downloadsDir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { metaFile ->
            val syncId = metaFile.nameWithoutExtension
            if (incomingChunks.containsKey(syncId)) return@forEach
            val partFile = downloadsDir.resolve("$syncId.part")
            if (!partFile.exists()) { metaFile.delete(); return@forEach }
            runCatching {
                val parts = metaFile.readText().trim().split(":")
                val total = parts[0].toInt()
                val received = parts[1].toInt()
                incomingChunks[syncId] = Pair(received, total)
                activeDownloadRequests.add(syncId)
                Log.d(TAG, "Restored partial download $syncId ($received/$total chunks)")
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Push item metadata to the server (and, for new items, upload the file).
     * @param withFile upload the file too — true for newly stashed items, false for
     *   metadata-only edits (rename/text edit/pin) where the file is unchanged.
     */
    fun pushItem(item: StashItem, withFile: Boolean = true) {
        // Durable backstop: record the upsert so it survives being offline and is flushed
        // on (re)connect. The live send below is the fast-path when already connected.
        scope.launch { repo.enqueueUpsert(item.syncId) }
        val socket = ws ?: return
        socket.send(ClientMsg.pushItem(item.toSyncItem(sendHasFile = false)))
        if (withFile && needsUpload(item)) {
            scope.launch { uploadFile(socket, item.syncId, item.content) }
        }
    }

    /** Server rejected an upload because the received file's hash didn't match. Retry once. */
    private suspend fun handleFileVerifyFailed(syncId: String) {
        Log.w(TAG, "Server rejected upload (hash mismatch): $syncId")
        postProgress(_uploadProgress, syncId, -1)
        val socket = ws ?: return
        val item = repo.getBySyncId(syncId) ?: return
        if (!item.fileLocal || item.content.isBlank() || !File(item.content).exists()) return
        if (verifyRetries.add(syncId)) {
            Log.d(TAG, "Retrying upload after verify failure: $syncId")
            uploadFile(socket, syncId, item.content)
        } else {
            Log.e(TAG, "Upload verify failed again for $syncId — giving up until next sync")
            verifyRetries.remove(syncId)
        }
    }

    /**
     * Stop and forget any in-flight or queued download for [syncId] and remove its partial files.
     * Called when an item is deleted so a download still streaming can't recreate the file after
     * the delete, and so the (possibly large) .part is reclaimed immediately.
     */
    fun cancelDownload(syncId: String) {
        val wasInFlight = synchronized(downloadGate) {
            val inf = syncId in downloadsInFlight
            downloadsInFlight.remove(syncId)
            downloadWaiting.remove(syncId)
            inf
        }
        activeDownloadRequests.remove(syncId)
        downloadRetries.remove(syncId)
        if (wasInFlight) endTransfer()
        incomingChunks.remove(syncId)
        runCatching { downloadsDir.resolve("$syncId.part").delete() }
        runCatching { downloadsDir.resolve("$syncId.meta").delete() }
        postProgress(_downloadProgress, syncId, null)
        pumpDownloads()
    }

    /**
     * Delete every managed file that is no longer referenced by a surviving DB item (and isn't an
     * active download's partial). Guarantees "remove everywhere" actually frees the disk even when
     * files were orphaned by earlier interruptions (stray .part/.meta, files left by a delete that
     * raced a download, etc.). Returns the number of bytes reclaimed.
     */
    suspend fun reclaimOrphanFiles(): Long = withContext(Dispatchers.IO) {
        val keep = HashSet<String>()
        repo.getAllSync().forEach { item ->
            if (item.content.isNotBlank()) runCatching { keep.add(File(item.content).canonicalPath) }
            if (item.thumbnailPath.isNotBlank()) runCatching { keep.add(File(item.thumbnailPath).canonicalPath) }
        }
        var freed = 0L
        var removed = 0
        listOf(File(ctx.filesDir, "stash"), File(ctx.filesDir, "stash_files")).forEach { root ->
            if (!root.exists()) return@forEach
            root.walkBottomUp().forEach { f ->
                when {
                    f.isFile -> {
                        val cp = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                        if (cp !in keep) {
                            val len = f.length()
                            if (f.delete()) { freed += len; removed++ }
                        }
                    }
                    f.isDirectory && f != root && f.listFiles().isNullOrEmpty() -> { f.delete() }
                }
            }
        }
        // Partial downloads not tied to a still-wanted download.
        downloadsDir.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension !in activeDownloadRequests) {
                val len = f.length()
                if (f.delete()) { freed += len; removed++ }
            }
        }
        // Cached external-open copies whose item no longer exists (named by sharable filename).
        val shareDir = com.stashapp.utils.FileOpener.shareCacheDir(ctx)
        if (shareDir.exists()) {
            val keepShareNames = repo.getAllSync()
                .map { com.stashapp.utils.FileOpener.shareCacheName(it) }.toHashSet()
            shareDir.listFiles()?.forEach { f ->
                if (f.name !in keepShareNames) {
                    val len = f.length()
                    if (f.delete()) { freed += len; removed++ }
                }
            }
        }
        TransferLog.log("orphan sweep: removed $removed files, freed ${freed / 1024}KB")
        freed
    }

    /** Total bytes of all managed on-disk files (stash/, stash_files/, stash_downloads/, the
     *  external-open cache). Used to report accurately how much a delete actually freed (before − after). */
    suspend fun managedStorageBytes(): Long = withContext(Dispatchers.IO) {
        listOf(File(ctx.filesDir, "stash"), File(ctx.filesDir, "stash_files"), downloadsDir,
               com.stashapp.utils.FileOpener.shareCacheDir(ctx))
            .filter { it.exists() }
            .sumOf { root -> root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    fun deleteItem(syncId: String) {
        // Record a durable delete tombstone first, then fire the live delete. The tombstone
        // both flushes on reconnect and blocks resurrection from a stale sync_meta.
        scope.launch { repo.enqueueDelete(syncId) }
        ws?.send(ClientMsg.deleteItem(syncId))
    }

    fun requestFile(syncId: String) {
        // Mark wanted (idempotent), then queue iff it isn't already streaming or queued. Dedup is
        // based on the real queue/in-flight state — NOT on membership of activeDownloadRequests —
        // so a retry of a previously-failed download (which stays "wanted" but holds no slot) is
        // actually re-queued instead of being silently dropped (the broken-retry bug).
        activeDownloadRequests.add(syncId)
        downloadRetries.remove(syncId)   // a fresh user/auto request resets the transient backoff
        val newlyQueued = synchronized(downloadGate) {
            if (syncId in downloadsInFlight || syncId in downloadWaiting) false
            else { downloadWaiting.addLast(syncId); true }
        }
        if (!newlyQueued) return
        // Keep the connection alive across backgrounding while downloading, the same way uploads do:
        // the foreground SyncWorker stays up until activeTransfers drains to zero.
        SyncWorker.enqueue(ctx)
        TransferLog.log("DL queued $syncId (wanted=${activeDownloadRequests.size})")
        pumpDownloads()
    }

    /** Admit queued downloads up to the concurrency cap. Safe to call from any thread. */
    private fun pumpDownloads() {
        val toStart = ArrayList<String>()
        synchronized(downloadGate) {
            while (downloadsInFlight.size < MAX_CONCURRENT_DOWNLOADS && downloadWaiting.isNotEmpty()) {
                val id = downloadWaiting.removeFirst()
                if (id !in activeDownloadRequests) continue   // want was cancelled meanwhile
                if (!downloadsInFlight.add(id)) continue       // already streaming
                toStart.add(id)
            }
        }
        toStart.forEach { startDownloadStream(it) }
    }

    /** Issue request_file for one admitted download. Frees its slot again if the send fails. */
    private fun startDownloadStream(syncId: String) {
        val fromChunk = consecutiveChunksOnDisk(syncId)
        val existing = incomingChunks[syncId]
        val initialPct = if (existing != null && existing.second > 0)
            (existing.first * 100 / existing.second).coerceAtMost(100) else 0
        postProgress(_downloadProgress, syncId, initialPct)

        if (ws?.send(ClientMsg.requestFile(syncId, fromChunk)) == true) {
            beginTransfer()
            TransferLog.log("DL start $syncId from chunk $fromChunk (inFlight=${downloadsInFlight.size})")
        } else {
            TransferLog.log("DL start failed (no socket) $syncId")
            synchronized(downloadGate) { downloadsInFlight.remove(syncId) }
            postProgress(_downloadProgress, syncId, null)
            // Leave it in activeDownloadRequests so resumeActiveDownloads retries after reconnect.
        }
    }

    /**
     * A download reached a terminal state (completed, failed, or abandoned). Drop it from the
     * wanted + in-flight sets and admit the next queued download.
     */
    private fun finishDownload(syncId: String, alsoForget: Boolean) {
        synchronized(downloadGate) {
            downloadsInFlight.remove(syncId)
            downloadWaiting.remove(syncId)
        }
        downloadRetries.remove(syncId)
        if (alsoForget) activeDownloadRequests.remove(syncId)
        pumpDownloads()
    }

    /**
     * The server reports the requested file isn't on it yet (e.g. a mirror still pulling it). Free
     * the slot and auto-retry just THIS download a few times before surfacing a failure — without
     * disturbing other in-flight downloads (the old unkeyed error marked them all failed).
     */
    private suspend fun handleFileUnavailable(syncId: String) {
        val wasInFlight = synchronized(downloadGate) { downloadsInFlight.remove(syncId) }
        if (wasInFlight) endTransfer()
        if (syncId !in activeDownloadRequests) { pumpDownloads(); return }   // no longer wanted

        val n = (downloadRetries[syncId] ?: 0) + 1
        if (n > MAX_DOWNLOAD_RETRIES) {
            downloadRetries.remove(syncId)
            postProgress(_downloadProgress, syncId, PROGRESS_FAILED)
            TransferLog.log("DL not-ready $syncId — gave up after $MAX_DOWNLOAD_RETRIES retries")
            pumpDownloads()
            return
        }
        downloadRetries[syncId] = n
        postProgress(_downloadProgress, syncId, 0)   // keep a pending look, not a failure
        TransferLog.log("DL not-ready $syncId — retry $n/$MAX_DOWNLOAD_RETRIES in ${DOWNLOAD_RETRY_DELAY_MS}ms")
        pumpDownloads()                               // free slot for other downloads meanwhile
        delay(DOWNLOAD_RETRY_DELAY_MS)
        if (syncId in activeDownloadRequests) {
            synchronized(downloadGate) {
                if (syncId !in downloadsInFlight && syncId !in downloadWaiting) downloadWaiting.addLast(syncId)
            }
            pumpDownloads()
        }
    }

    // ── File transfer ─────────────────────────────────────────────────────────

    /**
     * Upload a file. [activeUploads] dedups concurrent triggers for the same id (no double stream →
     * no progress skips/jumps); [uploadSemaphore] bounds how many distinct files stream at once so
     * they're parallel-fast without overflowing the socket. The item shows a "queued" indicator from
     * the moment it's accepted until a permit frees up. Crash-proof: a file deleted while queued or
     * mid-stream aborts this upload gracefully instead of throwing.
     */
    private suspend fun uploadFile(webSocket: WebSocket, syncId: String, path: String) {
        if (!activeUploads.add(syncId)) {
            TransferLog.log("UP skip (already in flight) $syncId")
            return
        }
        // Show "queued" immediately so the progress UI never appears out of nowhere mid-transfer.
        postProgress(_uploadProgress, syncId, PROGRESS_QUEUED)
        try {
            uploadSemaphore.withPermit {
                if (ws !== webSocket) return  // socket was replaced while we waited for a permit
                uploadFileStreaming(webSocket, syncId, path)
            }
        } catch (e: java.io.IOException) {
            // e.g. the file was deleted while this upload was queued or streaming.
            Log.w(TAG, "Upload aborted ($syncId): ${e.message}")
            TransferLog.log("UP aborted $syncId: ${e.message}")
        } finally {
            activeUploads.remove(syncId)
            postProgress(_uploadProgress, syncId, null)
        }
    }

    private suspend fun uploadFileStreaming(webSocket: WebSocket, syncId: String, path: String) {
        val file = File(path)
        val fileSize = file.length()
        if (fileSize == 0L) { Log.w(TAG, "Empty file for upload: $path"); return }

        val total = maxOf(1, ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt())

        // beginTransfer() lives inside the try so every exit path (incl. the "already complete"
        // shortcut below) hits the matching endTransfer() in finally — otherwise the transfer
        // counter leaks and the SyncWorker never sees the queue drain.
        beginTransfer()
        try {
            val startChunk = queryUploadState(webSocket, syncId, total)
            if (startChunk >= total) {
                Log.d(TAG, "Upload already complete on server: $syncId")
                TransferLog.log("UP already-complete $syncId ($total chunks)")
                return
            }
            if (startChunk > 0) Log.d(TAG, "Resuming upload $syncId from chunk $startChunk/$total")
            TransferLog.log("UP start $syncId from chunk $startChunk/$total ($fileSize bytes)")

            val fis = withContext(Dispatchers.IO) {
                java.io.FileInputStream(file).also {
                    if (startChunk > 0) {
                        var remaining = startChunk.toLong() * CHUNK_SIZE
                        while (remaining > 0) {
                            val skipped = it.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }
                }
            }
            try {
                val buf = ByteArray(CHUNK_SIZE)
                var index = startChunk
                var aborted = false
                while (true) {
                    val n = withContext(Dispatchers.IO) { fis.read(buf) }
                    if (n <= 0) break

                    val encoded = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    val msg = ClientMsg.pushFileChunk(syncId, encoded, index, total)

                    // Backpressure: don't let OkHttp's outgoing queue overflow.
                    // When queued bytes exceed 4 chunks, wait for the network to catch up.
                    while (webSocket.queueSize() > CHUNK_SIZE * 4L && ws === webSocket) {
                        delay(50)
                    }

                    var sent = webSocket.send(msg)
                    var retries = 0
                    // Stop retrying immediately if the socket was replaced by a reconnect.
                    while (!sent && retries < 20 && ws === webSocket) {
                        delay(200)
                        sent = webSocket.send(msg)
                        retries++
                    }
                    if (!sent) {
                        Log.w(TAG, "Upload aborted at chunk $index/$total for $syncId")
                        TransferLog.log("UP ABORT $syncId at chunk $index/$total (socket replaced=${ws !== webSocket})")
                        aborted = true
                        break
                    }
                    postProgress(_uploadProgress, syncId, (index + 1) * 100 / total)
                    index++
                }
                if (!aborted) {
                    Log.d(TAG, "Uploaded $syncId ($fileSize bytes, $total chunks)")
                    TransferLog.log("UP done $syncId ($total chunks)")
                }
            } finally {
                withContext(Dispatchers.IO) { fis.close() }
            }
        } finally {
            postProgress(_uploadProgress, syncId, null)
            endTransfer()
        }
    }

    private suspend fun queryUploadState(webSocket: WebSocket, syncId: String, total: Int): Int {
        val deferred = CompletableDeferred<Int>()
        pendingUploadStateQueries[syncId] = deferred
        webSocket.send(ClientMsg.queryUploadState(syncId, total))
        return try {
            withTimeout(5_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            0
        } finally {
            pendingUploadStateQueries.remove(syncId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun postProgress(ld: MutableLiveData<Map<String, Int>>, id: String, pct: Int?) {
        synchronized(progressLock) {
            val map = if (ld === _uploadProgress) uploadProgressMap else downloadProgressMap
            if (pct == null) map.remove(id) else map[id] = pct
            ld.postValue(HashMap(map))
        }
    }

    private fun StashItem.toSyncItem(syncFiles: Boolean = true, sendHasFile: Boolean = syncFiles) = SyncItem(
        id = syncId,
        title = title,
        content = if (type in listOf("link", "text")) content else originalFilename,
        type = type,
        mimeType = mimeType,
        isPinned = isPinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
        hasFile = sendHasFile && fileLocal && type !in listOf("link", "text"),
        fileHash = fileHash,
        thumbnailB64 = if (thumbnailPath.isNotBlank() && fileLocal) encodeThumbnail(thumbnailPath) else ""
    )

    private fun encodeThumbnail(path: String): String = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return ""
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 80, 80, true)
        val buf = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 15, buf)
        Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

    private suspend fun decodeThumbnail(itemId: Long, b64: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext ""
                FileStorage.saveThumbnail(ctx, bmp, itemId).absolutePath
            }.getOrDefault("")
        }

}

fun computeFileHash(path: String): String = runCatching {
    val md = MessageDigest.getInstance("SHA-256")
    File(path).inputStream().buffered().use { input ->
        val buf = ByteArray(8192)
        var n: Int
        while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
    }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrDefault("")
