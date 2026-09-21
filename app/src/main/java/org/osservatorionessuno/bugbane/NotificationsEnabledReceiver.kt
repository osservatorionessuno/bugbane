package org.osservatorionessuno.bugbane

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the user back to the wizard once they allow notifications in Settings. The system
 * sends this broadcast to the app whose block state changed, running or not.
 */
class NotificationsEnabledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ponytail: no "sent by the wizard" flag; the platform refuses the start unless Settings
        // sits in the wizard's task. Add one if another screen ever opens notification settings.
        if (!intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, true)) {
            SlideshowActivity.bringForward(context)
        }
    }
}
