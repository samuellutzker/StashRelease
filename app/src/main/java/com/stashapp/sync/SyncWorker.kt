package com.stashapp.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stashapp.R
import com.stashapp.StashApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Guaranteed outbound sync. Enqueued when an item is stashed so the upload completes even if
 * the app is backgrounded or its process is later killed — WorkManager re-runs the job after
 * process death and reboot, and retries with backoff when the network is unavailable.
 *
 * Runs as a foreground service while active (large uploads need more than expedited-work time
 * and must survive Doze), then exits so there is no persistent background connection draining
 * the battery. Reused as a normal client connection: WorkManager starts the app process, so
 * [StashApp.syncClient] exists and its reconnect → initial-sync path uploads anything the
 * server is missing. The worker just keeps the process alive until transfers drain.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!SyncSettings.isConfigured(applicationContext)) return Result.success()

        runCatching { setForeground(buildForegroundInfo()) }

        val client = (applicationContext as StashApp).syncClient
        client.start()

        // Wait for the connection (and thus the initial-sync upload pass) to come up.
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            while (!client.isConnected) delay(POLL_MS)
            true
        } ?: false
        if (!connected) {
            Log.d(TAG, "Not connected within timeout — will retry")
            return Result.retry()
        }

        // Give initial sync a moment to enqueue uploads, then wait for all transfers to drain.
        delay(SETTLE_MS)
        val drained = withTimeoutOrNull(WORK_TIMEOUT_MS) {
            var idleMs = 0L
            while (idleMs < IDLE_REQUIRED_MS) {
                if (!client.isConnected) return@withTimeoutOrNull false
                if (client.activeTransfers == 0) idleMs += POLL_MS else idleMs = 0
                delay(POLL_MS)
            }
            true
        }

        return when (drained) {
            true -> { Log.d(TAG, "Sync complete"); Result.success() }
            else -> { Log.d(TAG, "Sync incomplete (disconnect/timeout) — will retry"); Result.retry() }
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Active while items are syncing to the server"
                }
            )
        }
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Stash")
            .setContentText("Syncing…")
            .setSmallIcon(R.drawable.ic_sync)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val CHANNEL_ID = "stash_sync"
        private const val NOTIF_ID = 1002
        private const val UNIQUE_WORK = "stash-outbound-sync"

        private const val POLL_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 2_000L
        private const val IDLE_REQUIRED_MS = 4_000L
        private const val WORK_TIMEOUT_MS = 30 * 60_000L

        /** Schedule a guaranteed sync. Coalesces with any already-pending run. */
        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
