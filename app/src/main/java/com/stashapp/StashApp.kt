package com.stashapp

import android.app.Application
import com.stashapp.sync.StashSyncClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class StashApp : Application() {

    lateinit var syncClient: StashSyncClient
        private set

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val logFile = File(filesDir, "crash_log.txt")
                logFile.writeText("Thread: ${thread.name}\n\n$sw")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        syncClient = StashSyncClient(this)
        syncClient.start()
    }

    override fun onTerminate() {
        super.onTerminate()
        syncClient.close()
    }
}
