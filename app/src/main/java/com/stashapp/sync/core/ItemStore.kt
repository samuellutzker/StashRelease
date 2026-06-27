package com.stashapp.sync.core

import com.stashapp.data.StashItem

/**
 * The item-store operations the platform-independent sync logic needs. Backed by Room in the
 * app (see StashSyncClient), and by an in-memory fake in JVM unit tests — so the exact same
 * sync decision logic runs in both places without an emulator.
 */
interface ItemStore {
    suspend fun getBySyncId(syncId: String): StashItem?
    suspend fun getByFileHash(hash: String): StashItem?
    suspend fun insert(item: StashItem): Long
    suspend fun updateSyncId(localId: Long, syncId: String)
    suspend fun applyRemoteEdit(syncId: String, title: String, content: String, isPinned: Boolean, updatedAt: Long)
    suspend fun hasDeleteTombstone(syncId: String): Boolean
}
