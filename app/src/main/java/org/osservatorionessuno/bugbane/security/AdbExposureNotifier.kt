package org.osservatorionessuno.bugbane.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.osservatorionessuno.bugbane.R

/**
 * Ongoing notification for the exploitable window of CVE-2026-0073.
 *
 * It appears while a vulnerable device has Wireless Debugging switched on and no
 * acquisition is running — i.e. the device is needlessly reachable over the
 * network — and **auto-clears the moment Wireless Debugging is turned off**, so
 * it never lingers once the user has closed the hole. An app cannot toggle the
 * setting itself, so the tap action deep-links to Developer Options.
 *
 * Driven from [org.osservatorionessuno.bugbane.utils.ConfigurationViewModel],
 * which already observes the wireless-debugging and ADB state; call [update]
 * whenever those change.
 */
object AdbExposureNotifier {
    private const val CHANNEL_ID = "adb_exposure"
    private const val NOTIFICATION_ID = 3

    fun update(
        context: Context,
        vulnerable: Boolean,
        wirelessDebuggingOn: Boolean,
        acquiring: Boolean,
        onboardingComplete: Boolean,
    ) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)

        // Only nag once the exposure is gratuitous: device unpatched, wireless
        // debugging on, not mid-acquisition, and past onboarding (which is when
        // the user is deliberately enabling debugging).
        val expose = vulnerable && wirelessDebuggingOn && !acquiring && onboardingComplete
        if (!expose) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // no permission — the in-app banner covers this case
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.notification_channel_adb_exposure),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            app, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = app.getString(R.string.adb_exposure_notification_text)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bugbane_zoom)
            .setContentTitle(app.getString(R.string.adb_exposure_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Clear the notification (e.g. on sign-out/reset). */
    fun clear(context: Context) =
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
}
