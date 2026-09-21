package org.osservatorionessuno.bugbane.workers

import android.content.Context
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.SlideshowActivity
import org.osservatorionessuno.cadb.AdbPairingService
import java.util.concurrent.TimeUnit

/**
 * Brings the user back to the wizard once Developer options are on. The app's own
 * ContentObserver cannot: the content service delays callbacks to a background process by
 * ten seconds, and a frozen one gets nothing. A content-triggered job runs regardless.
 */
class DeveloperOptionsWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val enabled = Settings.Global.getInt(
            applicationContext.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0,
        ) == 1
        if (enabled) {
            SlideshowActivity.bringForward(applicationContext)
            if (!SlideshowActivity.cameForward()) {
                AdbPairingService.notifyGuidance(
                    applicationContext,
                    applicationContext.getString(R.string.notification_guide_developer_done_title),
                    applicationContext.getString(R.string.notification_guide_developer_done_text),
                )
                // The wizard may have resumed meanwhile, and its own cancel ran before the post.
                if (SlideshowActivity.inForeground) AdbPairingService.cancelNotification(applicationContext)
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "DeveloperOptionsEnabled"

        /** Watch the setting once; the trigger is consumed by the first change. */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false)
                .setTriggerContentUpdateDelay(0, TimeUnit.MILLISECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.SECONDS)
                .build()
            val request = OneTimeWorkRequestBuilder<DeveloperOptionsWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
