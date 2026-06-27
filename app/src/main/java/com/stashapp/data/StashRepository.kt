package com.stashapp.data

import androidx.lifecycle.LiveData

class StashRepository(private val dao: StashDao, private val pendingDao: PendingOpDao? = null) {
    fun getAll(): LiveData<List<StashItem>> = dao.getAll()
    fun getByType(type: String): LiveData<List<StashItem>> = dao.getByType(type)
    fun search(query: String): LiveData<List<StashItem>> = dao.search(query)
    suspend fun getById(id: Long) = dao.getById(id)
    fun getLiveById(id: Long) = dao.getLiveById(id)
    suspend fun getBySyncId(syncId: String) = dao.getBySyncId(syncId)
    suspend fun getByFileHash(hash: String) = dao.getByFileHash(hash)
    suspend fun findByUrl(url: String) = dao.findByUrl(url)
    suspend fun getAllSync() = dao.getAllSync()
    suspend fun getItemsWithoutSyncId() = dao.getItemsWithoutSyncId()
    suspend fun insert(item: StashItem) = dao.insert(item)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun deleteBySyncId(syncId: String) = dao.deleteBySyncId(syncId)
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long) = dao.setPinned(id, pinned, updatedAt)
    suspend fun updateSyncId(id: Long, syncId: String) = dao.updateSyncId(id, syncId)
    suspend fun updateFileLocalBySyncId(syncId: String, fileLocal: Boolean, content: String, thumbnailPath: String = "", fileSize: Long = 0) =
        dao.updateFileLocalBySyncId(syncId, fileLocal, content, thumbnailPath, fileSize)
    suspend fun setServerHasFile(syncId: String, present: Boolean) = dao.setServerHasFile(syncId, present)

    suspend fun updateEdited(id: Long, content: String, title: String, updatedAt: Long) = dao.updateEdited(id, content, title, updatedAt)
    suspend fun applyRemoteEdit(syncId: String, title: String, content: String, isPinned: Boolean, updatedAt: Long) =
        dao.applyRemoteEdit(syncId, title, content, isPinned, updatedAt)
    suspend fun updateThumbnailPath(id: Long, path: String) = dao.updateThumbnailPath(id, path)
    suspend fun setCloudOnly(id: Long) = dao.setCloudOnly(id)
    fun getLocalOnly() = dao.getLocalOnly()
    fun getLocalOnlyByType(type: String) = dao.getLocalOnlyByType(type)
    fun searchLocalOnly(query: String) = dao.searchLocalOnly(query)

    // ── Pending-ops queue (durable offline backstop) ──────────────────────────

    /** Record an upsert op for a syncId, replacing any superseded upsert for the same id. */
    suspend fun enqueueUpsert(syncId: String) {
        val dao = pendingDao ?: return
        if (syncId.isBlank()) return
        dao.deleteBySyncIdAndType(syncId, PendingOp.OP_UPSERT)
        dao.insert(PendingOp(syncId = syncId, opType = PendingOp.OP_UPSERT))
    }

    /** Record a delete tombstone for a syncId, dropping any queued upserts for it. */
    suspend fun enqueueDelete(syncId: String) {
        val dao = pendingDao ?: return
        if (syncId.isBlank()) return
        dao.deleteBySyncIdAndType(syncId, PendingOp.OP_UPSERT)
        dao.deleteBySyncIdAndType(syncId, PendingOp.OP_DELETE)
        dao.insert(PendingOp(syncId = syncId, opType = PendingOp.OP_DELETE))
    }

    suspend fun getPendingOps(): List<PendingOp> = pendingDao?.getAll() ?: emptyList()
    suspend fun getDeleteTombstoneIds(): List<String> = pendingDao?.getDeleteTombstoneIds() ?: emptyList()
    suspend fun hasDeleteTombstone(syncId: String): Boolean = (pendingDao?.countDeleteTombstones(syncId) ?: 0) > 0
    suspend fun deletePendingOp(id: Long) { pendingDao?.deleteById(id) }
    suspend fun clearTombstone(syncId: String) { pendingDao?.deleteBySyncId(syncId) }
}
