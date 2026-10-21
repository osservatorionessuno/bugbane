package org.osservatorionessuno.bugbane

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process and the CPU alive for the length of an acquisition. Without it a
 * locked phone freezes the app and suspends the USB host port, which drops the target.
 * Started and stopped by [org.osservatorionessuno.cadb.AdbManager.runQuickForensics].
 */
class AcquisitionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_acquisition_running), NotificationManager.IMPORTANCE_LOW)
        )
        val reopen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(getString(R.string.notification_acquisition_running_title))
            .setContentText(getString(R.string.notification_acquisition_running_text))
            .setContentIntent(reopen)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bugbane:acquisition")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "acquisition_running"
        private const val NOTIFICATION_ID = 3
        // Safety net only: the service is stopped when the acquisition ends.
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AcquisitionService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, AcquisitionService::class.java))
        }
    }
}
