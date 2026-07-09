package org.osservatorionessuno.bugbane.utils

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.osservatorionessuno.bugbane.MainActivity
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.cadb.AdbManager
import org.osservatorionessuno.qf.AcquisitionRunner
import java.io.File

private const val TAG = "AcquisitionProgressTracker"

/**
 * Application-scoped holder for the progress of a running acquisition.
 *
 * The UI only renders these flows, so the progress display survives the
 * hosting activity being recreated (e.g. after visiting settings or a
 * configuration change) while the acquisition keeps running on the
 * AdbManager executor.
 *
 * When the acquisition finishes while the app is not in the foreground, a
 * notification is posted; tapping it reopens [MainActivity], where the
 * pending acquisition is picked up and the results are shown.
 */
object AcquisitionProgressTracker {
    private const val CHANNEL_ID = "acquisition_complete"
    private const val NOTIFICATION_ID = 2

    data class ModuleProgress(val name: String, val bytes: Long, val done: Boolean)

    private val _modules = MutableStateFlow<List<ModuleProgress>>(emptyList())
    val modules: StateFlow<List<ModuleProgress>> = _modules.asStateFlow()

    private val _completedModules = MutableStateFlow(0)
    val completedModules: StateFlow<Int> = _completedModules.asStateFlow()

    private val _totalModules = MutableStateFlow(0)
    val totalModules: StateFlow<Int> = _totalModules.asStateFlow()

    /** A finished acquisition the UI has not navigated to yet. */
    private val _pendingAcquisition = MutableStateFlow<File?>(null)
    val pendingAcquisition: StateFlow<File?> = _pendingAcquisition.asStateFlow()

    /** True after a successful acquisition until the user dismisses the reminder. */
    private val _showDisableReminder = MutableStateFlow(false)
    val showDisableReminder: StateFlow<Boolean> = _showDisableReminder.asStateFlow()

    fun start(context: Context, adbManager: AdbManager, baseDir: File) {
        val appContext = context.applicationContext
        _modules.value = emptyList()
        _completedModules.value = 0
        _totalModules.value = 0
        _pendingAcquisition.value = null

        adbManager.runQuickForensics(baseDir, object : AcquisitionRunner.ProgressListener {
            override fun onModuleStart(name: String, completed: Int, total: Int) {
                _totalModules.value = total
                _modules.update { it + ModuleProgress(name, 0L, false) }
            }

            override fun onModuleProgress(name: String, bytes: Long) {
                _modules.update { list ->
                    list.map { if (it.name == name && !it.done) it.copy(bytes = bytes) else it }
                }
            }

            override fun onModuleComplete(name: String, completed: Int, total: Int) {
                _completedModules.value = completed
                _modules.update { list ->
                    if (list.any { it.name == name }) {
                        list.map { if (it.name == name) it.copy(done = true) else it }
                    } else {
                        list + ModuleProgress(name, 0L, true)
                    }
                }
            }

            override fun isCancelled(): Boolean = adbManager.isQuickForensicsCancelled

            override fun onFinished(cancelled: Boolean, output: File?) {
                if (cancelled || output == null) return
                _showDisableReminder.value = true
                _pendingAcquisition.value = output
                if (!isAppInForeground()) {
                    postFinishedNotification(appContext)
                }
            }
        })
    }

    /** Called by the UI once it has navigated to the finished acquisition. */
    fun consumePendingAcquisition(context: Context) {
        _pendingAcquisition.value = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun dismissDisableReminder() {
        _showDisableReminder.value = false
    }

    private fun isAppInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun postFinishedNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing, skipping completion notification")
            return
        }

        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_acquisition),
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        // Reopen the app; ScanScreen consumes the pending acquisition and
        // navigates to the results.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(context.getString(R.string.notification_acquisition_complete_title))
            .setContentText(context.getString(R.string.notification_acquisition_complete_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
