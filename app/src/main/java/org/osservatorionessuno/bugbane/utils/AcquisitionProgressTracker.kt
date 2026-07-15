package org.osservatorionessuno.bugbane.utils

import android.Manifest
import android.app.Notification
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
import android.app.ActivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.MainActivity
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.cadb.AdbManager
import org.osservatorionessuno.qf.AcquisitionRunner
import org.osservatorionessuno.qf.AcquisitionScanner
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

    enum class ModuleScanStatus {
        Waiting,
        Running,
        Completed,
        Error,
        Skipped,
    }

    data class ModuleProgress(
        val name: String,
        val bytes: Long = 0L,
        val status: ModuleScanStatus = ModuleScanStatus.Waiting,
    )

    private val _modules = MutableStateFlow<List<ModuleProgress>>(emptyList())
    val modules: StateFlow<List<ModuleProgress>> = _modules.asStateFlow()

    private val _completedModules = MutableStateFlow(0)
    val completedModules: StateFlow<Int> = _completedModules.asStateFlow()

    private val _totalModules = MutableStateFlow(0)
    val totalModules: StateFlow<Int> = _totalModules.asStateFlow()

    /** A finished acquisition the UI has not navigated to yet. */
    private val _pendingAcquisition = MutableStateFlow<File?>(null)
    val pendingAcquisition: StateFlow<File?> = _pendingAcquisition.asStateFlow()

    /**
     * Names of modules that failed in the last acquisition. Non-null until the
     * UI dismisses the error; while set, the acquisition view is not opened.
     */
    private val _failedModules = MutableStateFlow<List<String>?>(null)
    val failedModules: StateFlow<List<String>?> = _failedModules.asStateFlow()

    /** Set when the last acquisition ran low on storage, until the UI dismisses it. */
    private val _skippedForSpace = MutableStateFlow<List<String>?>(null)
    val skippedForSpace: StateFlow<List<String>?> = _skippedForSpace.asStateFlow()

    /** True after a successful acquisition until the user dismisses the reminder. */
    private val _showDisableReminder = MutableStateFlow(false)
    val showDisableReminder: StateFlow<Boolean> = _showDisableReminder.asStateFlow()

    /** The acquisition directory whose first analysis is currently running. */
    private val _analyzing = MutableStateFlow<File?>(null)
    val analyzing: StateFlow<File?> = _analyzing.asStateFlow()

    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, adbManager: AdbManager, baseDir: File) {
        val appContext = context.applicationContext
        _modules.value = AcquisitionRunner.MODULE_NAMES.map { ModuleProgress(it) }
        _completedModules.value = 0
        _totalModules.value = AcquisitionRunner.MODULE_NAMES.size
        _pendingAcquisition.value = null
        _failedModules.value = null
        _skippedForSpace.value = null

        adbManager.runQuickForensics(baseDir, object : AcquisitionRunner.ProgressListener {
            override fun onModuleStart(name: String, completed: Int, total: Int) {
                _totalModules.value = total
                _modules.update { list ->
                    if (list.any { it.name == name }) {
                        list.map {
                            if (it.name == name) it.copy(bytes = 0L, status = ModuleScanStatus.Running)
                            else it
                        }
                    } else {
                        list + ModuleProgress(name, 0L, ModuleScanStatus.Running)
                    }
                }
            }

            override fun onModuleProgress(name: String, bytes: Long) {
                _modules.update { list ->
                    list.map {
                        if (it.name == name && it.status == ModuleScanStatus.Running) {
                            it.copy(bytes = bytes)
                        } else {
                            it
                        }
                    }
                }
            }

            override fun onModuleComplete(name: String, completed: Int, total: Int, success: Boolean) {
                _completedModules.value = completed
                val status = if (success) ModuleScanStatus.Completed else ModuleScanStatus.Error
                _modules.update { list ->
                    if (list.any { it.name == name }) {
                        list.map { if (it.name == name) it.copy(status = status) else it }
                    } else {
                        list + ModuleProgress(name, 0L, status)
                    }
                }
            }

            override fun onModuleSkipped(name: String) {
                _modules.update { list ->
                    list.map { if (it.name == name) it.copy(status = ModuleScanStatus.Skipped) else it }
                }
            }

            override fun isCancelled(): Boolean = adbManager.isQuickForensicsCancelled

            override fun onFinished(cancelled: Boolean, output: File?) {
                if (cancelled || output == null) return
                val failed = _modules.value
                    .filter { it.status == ModuleScanStatus.Error }
                    .map { it.name }
                val skipped = _modules.value
                    .filter { it.status == ModuleScanStatus.Skipped }
                    .map { it.name }
                when {
                    skipped.isNotEmpty() -> _skippedForSpace.value = skipped
                    failed.isNotEmpty() -> _failedModules.value = failed
                    else -> _pendingAcquisition.value = output
                }
                _showDisableReminder.value = true
                autoAnalyze(appContext, output, failed, skipped)
            }
        })
    }

    /**
     * Run the first analysis automatically. When the app is in the
     * background the completion notification is deferred until the analysis
     * is done, so tapping it lands on the results.
     */
    private fun autoAnalyze(
        context: Context,
        acquisitionDir: File,
        failed: List<String>,
        skipped: List<String>,
    ) {
        _analyzing.value = acquisitionDir
        analysisScope.launch {
            var analyzed = false
            try {
                // The file key was cached while writing, so the first analysis
                // decrypts without any biometric/passphrase prompt.
                analyzed = AcquisitionScanner.scanFromSessionCache(context, acquisitionDir) != null
            } catch (t: Throwable) {
                Log.e(TAG, "Automatic analysis failed", t)
            } finally {
                _analyzing.value = null
            }
            if (!isAppInForeground()) {
                when {
                    skipped.isNotEmpty() -> postLowSpaceNotification(context)
                    failed.isNotEmpty() -> postFailedNotification(context, failed)
                    else -> postFinishedNotification(context, analyzed)
                }
            } else {
                Log.d(TAG, "Skipping completion notification; app is in the foreground")
            }
        }
    }

    /** Called by the UI once it has navigated to the finished acquisition. */
    fun consumePendingAcquisition(context: Context) {
        _pendingAcquisition.value = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun dismissDisableReminder() {
        _showDisableReminder.value = false
    }

    fun dismissFailedModules() {
        _failedModules.value = null
    }

    fun dismissSkippedForSpace() {
        _skippedForSpace.value = null
    }

    private fun isAppInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // Permission check and notify() must stay in the same function for lint's
    // MissingPermission dataflow analysis.
    private fun postAcquisitionNotification(context: Context, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing, skipping acquisition notification")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled for the app; skipping acquisition notification")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_acquisition),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun reopenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postFinishedNotification(context: Context, analyzed: Boolean) {
        // Reopen the app; ScanScreen consumes the pending acquisition and
        // navigates to the results.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(context.getString(R.string.notification_acquisition_complete_title))
            .setContentText(
                context.getString(
                    if (analyzed) R.string.notification_acquisition_complete_analyzed_text
                    else R.string.notification_acquisition_complete_text
                )
            )
            .setContentIntent(reopenAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAcquisitionNotification(context, notification)
    }

    private fun postFailedNotification(context: Context, failed: List<String>) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(context.getString(R.string.notification_acquisition_failed_title))
            .setContentText(
                context.getString(
                    R.string.notification_acquisition_failed_text,
                    failed.joinToString(", "),
                )
            )
            .setContentIntent(reopenAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAcquisitionNotification(context, notification)
    }

    private fun postLowSpaceNotification(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(context.getString(R.string.notification_acquisition_low_space_title))
            .setContentText(context.getString(R.string.notification_acquisition_low_space_text))
            .setContentIntent(reopenAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAcquisitionNotification(context, notification)
    }
}
