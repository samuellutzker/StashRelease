package com.stashapp.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.stashapp.data.StashItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opening a stashed file in another app, shared by the item-detail screen and the main-list
 * double-tap. Files are stored on disk named by syncId; we expose a cached copy named with the
 * item's ORIGINAL filename so the receiving app sees a sensible name.
 */
object FileOpener {

    fun openExternally(activity: Activity, item: StashItem, scope: CoroutineScope) {
        scope.launch {
            val uri = runCatching { withContext(Dispatchers.IO) { sharableUri(activity, item) } }.getOrNull()
            if (uri == null) {
                Toast.makeText(activity, "Cannot open this file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure {
                Toast.makeText(activity, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * A content:// URI for the item's file under its original filename (cached copy, reused across
     * opens of the same file). Runs on a background thread — may copy a large file.
     */
    fun sharableUri(context: Context, item: StashItem): Uri {
        val src = File(item.content)
        val desired = sanitizeFilename(item.originalFilename.ifBlank { item.title }.ifBlank { src.name })
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val dest = File(dir, desired)
        if (!dest.exists() || dest.length() != src.length()) {
            src.copyTo(dest, overwrite = true)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    }

    fun sanitizeFilename(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in ".-_ ") it else '_' }
            .joinToString("").trim().ifBlank { "file" }
        return cleaned.takeLast(120)
    }

    fun shareCacheDir(context: Context) = File(context.cacheDir, "share")

    /** The cache filename a given item's external-open copy lands at (see [sharableUri]). */
    fun shareCacheName(item: StashItem): String =
        sanitizeFilename(item.originalFilename.ifBlank { item.title }.ifBlank { File(item.content).name })

    /** Remove an item's cached external-open copy, if any (called on delete to reclaim disk). */
    fun deleteShareCache(context: Context, item: StashItem) {
        runCatching { File(shareCacheDir(context), shareCacheName(item)).delete() }
    }
}
