package com.stashapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface StashDao {
    @Query("SELECT * FROM stash_items ORDER BY isPinned DESC, createdAt DESC")
    fun getAll(): LiveData<List<StashItem>>

    @Query("SELECT * FROM stash_items WHERE type = :type ORDER BY isPinned DESC, createdAt DESC")
    fun getByType(type: String): LiveData<List<StashItem>>

    @Query("SELECT * FROM stash_items WHERE title LIKE '%' || :query || '%' OR originalFilename LIKE '%' || :query || '%' OR (type != 'file' AND content LIKE '%' || :query || '%') ORDER BY isPinned DESC, createdAt DESC")
    fun search(query: String): LiveData<List<StashItem>>

    @Query("SELECT * FROM stash_items WHERE id = :id")
    suspend fun getById(id: Long): StashItem?

    @Query("SELECT * FROM stash_items WHERE id = :id")
    fun getLiveById(id: Long): LiveData<StashItem?>

    @Query("SELECT * FROM stash_items WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): StashItem?

    @Query("SELECT * FROM stash_items WHERE fileHash = :hash AND fileHash != '' LIMIT 1")
    suspend fun getByFileHash(hash: String): StashItem?

    @Query("SELECT * FROM stash_items WHERE content = :url AND type = 'link' LIMIT 1")
    suspend fun findByUrl(url: String): StashItem?

    @Query("SELECT * FROM stash_items")
    suspend fun getAllSync(): List<StashItem>

    @Query("SELECT * FROM stash_items WHERE syncId = '' OR syncId IS NULL")
    suspend fun getItemsWithoutSyncId(): List<StashItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: StashItem): Long

    @Query("DELETE FROM stash_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM stash_items WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("UPDATE stash_items SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE stash_items SET syncId = :syncId WHERE id = :id")
    suspend fun updateSyncId(id: Long, syncId: String)

    // A downloaded file means the server holds it, so serverHasFile is set true here.
    @Query("UPDATE stash_items SET fileLocal = :fileLocal, content = :content, thumbnailPath = :thumbnailPath, fileSize = :fileSize, removedLocally = 0, serverHasFile = 1 WHERE syncId = :syncId")
    suspend fun updateFileLocalBySyncId(syncId: String, fileLocal: Boolean, content: String, thumbnailPath: String = "", fileSize: Long = 0)

    @Query("UPDATE stash_items SET serverHasFile = :present WHERE syncId = :syncId")
    suspend fun setServerHasFile(syncId: String, present: Boolean)

    @Query("UPDATE stash_items SET content = :content, title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateEdited(id: Long, content: String, title: String, updatedAt: Long)

    /** Apply a newer edit received from the server (matched by syncId). */
    @Query("UPDATE stash_items SET title = :title, content = :content, isPinned = :isPinned, updatedAt = :updatedAt WHERE syncId = :syncId")
    suspend fun applyRemoteEdit(syncId: String, title: String, content: String, isPinned: Boolean, updatedAt: Long)

    @Query("UPDATE stash_items SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnailPath(id: Long, path: String)

    @Query("UPDATE stash_items SET fileLocal = 0, content = '', source = 'cloud', removedLocally = 1 WHERE id = :id")
    suspend fun setCloudOnly(id: Long)

    @Query("SELECT * FROM stash_items WHERE fileLocal = 1 OR type IN ('link','text') ORDER BY isPinned DESC, createdAt DESC")
    fun getLocalOnly(): LiveData<List<StashItem>>

    @Query("SELECT * FROM stash_items WHERE (fileLocal = 1 OR type IN ('link','text')) AND type = :type ORDER BY isPinned DESC, createdAt DESC")
    fun getLocalOnlyByType(type: String): LiveData<List<StashItem>>

    @Query("SELECT * FROM stash_items WHERE (fileLocal = 1 OR type IN ('link','text')) AND (title LIKE '%' || :query || '%' OR originalFilename LIKE '%' || :query || '%' OR (type != 'file' AND content LIKE '%' || :query || '%')) ORDER BY isPinned DESC, createdAt DESC")
    fun searchLocalOnly(query: String): LiveData<List<StashItem>>
}
