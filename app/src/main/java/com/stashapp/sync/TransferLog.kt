package com.stashapp.sync

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Lightweight, shareable transfer log. Every upload/download lifecycle event is recorded here
 * (and mirrored to logcat under tag "StashTX") so a misbehaving transfer can be pinpointed
 * without an adb cable: the user can export the buffer from the app and send it over.
 *
 * Backed by an in-memory ring buffer plus an append-only file capped at [MAX_FILE_BYTES];
 * cheap enough to call on the hot chunk path at coarse granularity.
 */
object TransferLog {
    private const val TAG = "StashTX"
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private var file: File? = null

    /** Wire the persistent file once the app context is available. Safe to call repeatedly. */
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "transfer.log").also { f ->
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
        }
        log("──── log opened ────")
    }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        Log.i(TAG, msg)
        if (buffer.size >= MAX_LINES) buffer.pollFirst()
        buffer.addLast(line)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    /** Full buffer for export/sharing, newest last. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n")
}
