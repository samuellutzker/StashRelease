package com.stashapp.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object BitmapUtils {
    /**
     * Decode an image file downsampled so that neither dimension exceeds [maxDim]. This keeps
     * the decoded bitmap well under the Canvas/GPU draw limit (a full-resolution photo handed to
     * an ImageView throws "Canvas: trying to draw too large bitmap") and avoids decoding the whole
     * image into memory. Returns null if the file can't be decoded.
     */
    fun decodeBounded(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxDim || h / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}
