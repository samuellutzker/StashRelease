package com.stashapp.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

object FileStorage {

    fun copyToStash(context: Context, uri: Uri, type: String): Pair<File, String> {
        val dir = File(context.filesDir, "stash/$type").also { it.mkdirs() }
        val filename = resolveFilename(context, uri) ?: "file_${System.currentTimeMillis()}"
        val dest = uniqueFile(dir, filename)

        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open input stream for $uri")

        return dest to filename
    }

    fun saveThumbnail(context: Context, bitmap: android.graphics.Bitmap, itemId: Long): File {
        val dir = File(context.filesDir, "stash/thumbnails").also { it.mkdirs() }
        val file = File(dir, "thumb_$itemId.jpg")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        return file
    }

    fun deleteFile(path: String) {
        if (path.isNotBlank()) File(path).takeIf { it.exists() }?.delete()
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
        else -> "${"%.1f".format(bytes / (1024f * 1024f * 1024f))} GB"
    }

    private fun resolveFilename(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var counter = 1
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        while (file.exists()) {
            file = File(dir, "${base}_${counter++}${ext}")
        }
        return file
    }
}
