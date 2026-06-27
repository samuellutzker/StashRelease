package com.stashapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stash_items",
    indices = [Index(value = ["syncId"]), Index(value = ["fileHash"])]
)
data class StashItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val type: String,
    val mimeType: String,
    val originalFilename: String,
    val fileSize: Long = -1L,
    val sourceDomain: String,
    val thumbnailPath: String = "",
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last-modification time (epoch millis). Drives last-writer-wins edit sync. */
    val updatedAt: Long = System.currentTimeMillis(),
    val syncId: String = "",
    /** "local" or "cloud" */
    val source: String = "local",
    /** true if the file is stored on this device (always true for link/text) */
    val fileLocal: Boolean = true,
    /** SHA-256 hex of file content; used for cross-device deduplication */
    val fileHash: String = "",
    /** true if the user explicitly "removed locally" (ghosted) this item — distinguishes it from
     *  a never-downloaded cloud item so auto-download won't re-fetch it. */
    val removedLocally: Boolean = false,
    /** true once the server is confirmed to hold this item's file (upload acknowledged, file
     *  downloaded from the server, or a sync_meta/item_added reported has_file). Gates uploads so a
     *  file is sent exactly once, and drives the "pending upload" vs "synced" UI state. */
    val serverHasFile: Boolean = false
)
