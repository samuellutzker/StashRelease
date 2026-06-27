package com.stashapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingOpDao {
    @Insert
    suspend fun insert(op: PendingOp): Long

    /** All pending ops in insertion order, ready to flush. */
    @Query("SELECT * FROM pending_ops ORDER BY id ASC")
    suspend fun getAll(): List<PendingOp>

    /** Distinct syncIds that currently have a delete tombstone. */
    @Query("SELECT DISTINCT syncId FROM pending_ops WHERE opType = 'delete'")
    suspend fun getDeleteTombstoneIds(): List<String>

    /** True if a delete tombstone exists for this syncId. */
    @Query("SELECT COUNT(*) FROM pending_ops WHERE syncId = :syncId AND opType = 'delete'")
    suspend fun countDeleteTombstones(syncId: String): Int

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_ops WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    /** Drop superseded upserts for a syncId before recording a new op for it. */
    @Query("DELETE FROM pending_ops WHERE syncId = :syncId AND opType = :opType")
    suspend fun deleteBySyncIdAndType(syncId: String, opType: String)
}
