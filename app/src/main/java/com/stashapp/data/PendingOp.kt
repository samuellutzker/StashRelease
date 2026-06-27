package com.stashapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable record of a sync operation that must reach the server. Created/edited/pinned
 * items enqueue an [OP_UPSERT]; deletions enqueue an [OP_DELETE] tombstone. The queue is
 * flushed in id (insertion) order on every (re)connect so offline changes survive.
 *
 * A pending [OP_DELETE] also acts as a tombstone: while it exists, an incoming sync_meta /
 * item_added for the same syncId must NOT re-insert the item ("resurrection").
 */
@Entity(
    tableName = "pending_ops",
    indices = [Index(value = ["syncId"])]
)
data class PendingOp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncId: String,
    /** [OP_UPSERT] or [OP_DELETE] */
    val opType: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
    }
}
