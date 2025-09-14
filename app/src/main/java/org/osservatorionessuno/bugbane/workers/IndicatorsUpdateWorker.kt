package org.osservatorionessuno.bugbane.workers

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osservatorionessuno.libmvt.common.IndicatorsUpdates
import org.osservatorionessuno.bugbane.R
import java.io.File
import java.io.RandomAccessFile

/**
 * Worker that periodically fetches and updates indicators.
 */
class IndicatorsUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @SuppressLint("MissingPermission") // TODO
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Cross-thread/process lock
        val lockFile = File(applicationContext.filesDir, "indicators_update.lock")
        RandomAccessFile(lockFile, "rw").channel.use { ch ->
            val lock = try { ch.tryLock() } catch (_: Throwable) { null }
            if (lock == null) {
                Log.i(TAG, "Another update is running; skipping this execution")
                return@withContext Result.success()
            }
            try {
                val updates = IndicatorsUpdates(applicationContext.filesDir.toPath(), null)
                val before = updates.countIndicators()
                Log.i(TAG, "Starting indicator update with $before existing files")

                updates.update()

                val after = updates.countIndicators()
                val diff = after - before
                Log.i(TAG, "Indicator update finished, $after files total")
                if (diff > 0) notify(applicationContext, diff)
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Indicator update failed", e)
                Result.retry()
            } finally {
                try { lock.release() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "IndicatorsUpdateWorker"
        private const val CHANNEL_ID = "indicator_updates"

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun notify(context: Context, newCount: Long) {
            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_indicators),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.notification_channel_indicators))
                .setContentText(
                    context.getString(
                        R.string.notification_indicators_updated,
                        newCount
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(1, notification)
        }
    }
}
