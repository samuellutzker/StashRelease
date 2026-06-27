package com.stashapp.sync.core

import com.stashapp.data.StashItem
import com.stashapp.sync.SyncItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val LINK_TEXT = listOf("link", "text")

/**
 * Whether an item should be auto-downloaded during a "download all on sync" pass. We download it
 * only when the server actually has the file, we don't have it locally, and the user did NOT
 * deliberately "remove locally" (ghost) it — otherwise a ghosted item would keep re-downloading
 * itself on every reconnect (Test 6).
 */
fun shouldAutoDownload(item: StashItem, serverHasFile: Boolean): Boolean =
    serverHasFile &&
        !item.fileLocal &&
        !item.removedLocally &&
        item.syncId.isNotEmpty() &&
        item.type !in LINK_TEXT

/** Map a wire item into a fresh local (cloud-sourced) row. Pure — safe to unit-test. */
fun SyncItem.toStashItem(): StashItem = StashItem(
    title = title,
    content = if (type in LINK_TEXT) content else "",
    type = type,
    mimeType = mimeType,
    originalFilename = if (type !in LINK_TEXT) content else "",
    sourceDomain = "",
    isPinned = isPinned,
    createdAt = createdAt,
    updatedAt = if (updatedAt > 0) updatedAt else createdAt,
    syncId = id,
    source = "cloud",
    fileLocal = type in LINK_TEXT,
    fileHash = fileHash,
)

/**
 * Platform-independent application of an item received from the server — the exact logic the app
 * runs for every item_added and every sync_meta entry, extracted so it can be unit-tested on the
 * JVM with an in-memory [ItemStore] (no Android, no emulator).
 *
 * The check-then-write is serialized by [mutex] so two concurrent receive coroutines can't both
 * insert the same syncId (the duplicate-item bug).
 */
class SyncReducer(
    private val store: ItemStore,
    /** Re-assert a delete to the server for a tombstoned item the server is still echoing. */
    private val sendDelete: suspend (String) -> Unit,
) {
    private val mutex = Mutex()

    sealed class Result {
        /** A brand-new item was inserted; [localId] is its Room row id (caller decodes thumbnail). */
        data class Inserted(val localId: Long) : Result()
        /** An existing item was updated (newer edit applied). */
        object Updated : Result()
        /** Nothing changed: tombstoned, not-newer, or deduped by hash. */
        object Skipped : Result()
    }

    suspend fun apply(serverItem: SyncItem): Result = mutex.withLock {
        // Deleted offline but the server still lists it → re-assert delete, never resurrect.
        if (store.hasDeleteTombstone(serverItem.id)) {
            sendDelete(serverItem.id)
            return@withLock Result.Skipped
        }
        val existing = store.getBySyncId(serverItem.id)
        if (existing != null) {
            // Last-writer-wins by updatedAt. For file items keep the local file path/content;
            // only link/text content comes from the server.
            val serverEffective = if (serverItem.updatedAt > 0) serverItem.updatedAt else serverItem.createdAt
            val existingEffective = if (existing.updatedAt > 0) existing.updatedAt else existing.createdAt
            if (serverEffective > existingEffective) {
                val newContent = if (existing.type in LINK_TEXT) {
                    val candidate = serverItem.content
                    if (existing.type == "link" &&
                        !candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                        existing.content
                    } else {
                        candidate
                    }
                } else existing.content
                store.applyRemoteEdit(
                    serverItem.id, serverItem.title, newContent, serverItem.isPinned, serverItem.updatedAt
                )
                return@withLock Result.Updated
            }
            return@withLock Result.Skipped
        }
        // Dedup: same file content already present under a different syncId. Keep the existing local
        // syncId and delete the server's duplicate — previously we adopted the incoming id, but that
        // caused the item to reappear after deletion (deleting id B only removed B; the old id A
        // was still on the server and would resurface on the next sync).
        if (serverItem.fileHash.isNotEmpty()) {
            val byHash = store.getByFileHash(serverItem.fileHash)
            if (byHash != null) {
                if (byHash.syncId.isNotBlank() && byHash.syncId != serverItem.id) {
                    sendDelete(serverItem.id)
                }
                return@withLock Result.Skipped
            }
        }
        // Reject new link items with non-http(s) URLs (mirrors the edit-path check above).
        if (serverItem.type == "link" &&
            !serverItem.content.startsWith("http://") && !serverItem.content.startsWith("https://")) {
            return@withLock Result.Skipped
        }
        Result.Inserted(store.insert(serverItem.toStashItem()))
    }
}
