package com.stashapp.sync

import org.json.JSONArray
import org.json.JSONObject

data class SyncItem(
    val id: String,
    val title: String,
    val content: String,
    val type: String,
    val mimeType: String,
    val isPinned: Boolean,
    val createdAt: Long,
    /** Last-modification time (epoch millis); drives last-writer-wins edit sync. */
    val updatedAt: Long = 0,
    /** True only when the server actually has the file stored */
    val hasFile: Boolean,
    val fileHash: String = "",
    /** Highly-compressed thumbnail for cloud-only items (base64 JPEG, may be blank) */
    val thumbnailB64: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("content", content)
        put("type", type); put("mime_type", mimeType)
        put("is_pinned", isPinned); put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("has_file", hasFile); put("file_hash", fileHash)
        if (thumbnailB64.isNotBlank()) put("thumbnail_b64", thumbnailB64)
    }

    companion object {
        fun fromJson(o: JSONObject) = SyncItem(
            id = o.getString("id"),
            title = o.optString("title"),
            content = o.optString("content"),
            type = o.optString("type", "other"),
            mimeType = o.optString("mime_type"),
            isPinned = o.optBoolean("is_pinned"),
            createdAt = o.optLong("created_at"),
            updatedAt = o.optLong("updated_at"),
            hasFile = o.optBoolean("has_file"),
            fileHash = o.optString("file_hash"),
            thumbnailB64 = o.optString("thumbnail_b64")
        )

        fun listFromJson(arr: JSONArray): List<SyncItem> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

sealed class ServerMsg {
    data class Challenge(val nonce: String) : ServerMsg()
    object AuthOk : ServerMsg()
    object AuthFail : ServerMsg()
    data class SyncMeta(val items: List<SyncItem>) : ServerMsg()
    data class ItemAdded(val item: SyncItem) : ServerMsg()
    data class ItemDeleted(val id: String) : ServerMsg()
    data class FileChunk(val id: String, val chunk: String, val index: Int, val total: Int) : ServerMsg()
    data class FileTransferComplete(val id: String) : ServerMsg()
    /** Server rejected a fully-received file because its SHA-256 didn't match. */
    data class FileVerifyFailed(val id: String) : ServerMsg()
    /** How many consecutive chunks (from 0) the server already has for an upload. */
    data class UploadState(val id: String, val received: Int) : ServerMsg()
    /** The item exists but its file isn't on this server yet (transient — retry just this id). */
    data class FileUnavailable(val id: String) : ServerMsg()
    data class Error(val message: String, val context: String = "") : ServerMsg()
    object Unknown : ServerMsg()

    companion object {
        fun parse(text: String): ServerMsg {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return Unknown
            return when (o.optString("type")) {
                "challenge" -> Challenge(o.getString("nonce"))
                "auth_ok" -> AuthOk
                "auth_fail" -> AuthFail
                "sync_meta" -> SyncMeta(SyncItem.listFromJson(o.getJSONArray("items")))
                "item_added" -> ItemAdded(SyncItem.fromJson(o.getJSONObject("item")))
                "item_deleted" -> ItemDeleted(o.getString("id"))
                "file_chunk" -> FileChunk(
                    id = o.getString("id"),
                    chunk = o.getString("chunk"),
                    index = o.getInt("index"),
                    total = o.getInt("total")
                )
                "file_transfer_complete" -> FileTransferComplete(o.getString("id"))
                "file_verify_failed" -> FileVerifyFailed(o.getString("id"))
                "upload_state" -> UploadState(o.getString("id"), o.optInt("received", 0))
                "file_unavailable" -> FileUnavailable(o.getString("id"))
                "error" -> Error(o.optString("message"), o.optString("context"))
                else -> Unknown
            }
        }
    }
}

object ClientMsg {
    fun auth(hash: String, knownIds: List<String> = emptyList()) = JSONObject().apply {
        put("type", "auth")
        put("hash", hash)
        if (knownIds.isNotEmpty()) put("known_ids", JSONArray(knownIds))
    }.toString()

    fun syncMeta(items: List<SyncItem>) = JSONObject().apply {
        put("type", "sync_meta")
        put("items", JSONArray().also { arr -> items.forEach { arr.put(it.toJson()) } })
    }.toString()

    fun pushItem(item: SyncItem) = JSONObject().apply {
        put("type", "push_item"); put("item", item.toJson())
    }.toString()

    fun deleteItem(syncId: String) = JSONObject().apply {
        put("type", "delete_item"); put("id", syncId)
    }.toString()

    fun requestFile(syncId: String, fromChunk: Int = 0) = JSONObject().apply {
        put("type", "request_file"); put("id", syncId)
        if (fromChunk > 0) put("from_chunk", fromChunk)
    }.toString()

    fun pushFileChunk(syncId: String, chunk: String, index: Int, total: Int) = JSONObject().apply {
        put("type", "push_file_chunk"); put("id", syncId)
        put("chunk", chunk); put("index", index); put("total", total)
    }.toString()

    fun queryUploadState(syncId: String, total: Int) = JSONObject().apply {
        put("type", "query_upload_state"); put("id", syncId); put("total", total)
    }.toString()
}
