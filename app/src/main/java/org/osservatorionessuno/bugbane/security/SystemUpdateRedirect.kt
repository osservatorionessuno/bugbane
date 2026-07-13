package org.osservatorionessuno.bugbane.security

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Sends the user to where they can apply updates. On GMS devices
 * [Settings.ACTION_SYSTEM_UPDATE_SETTINGS] opens the GMS system-update screen,
 * which covers both the OS security update and the Google Play system update
 * (the adbd Mainline channel). Falls back to Security, then top-level Settings.
 *
 * Note: an app cannot query whether an OEM update is actually pending —
 * `SystemUpdateManager.retrieveSystemUpdateInfo()` needs the privileged
 * `READ_SYSTEM_UPDATE_INFO` permission — so we can only take the user there.
 */
object SystemUpdateRedirect {
    // Not a public Settings constant — the action string resolves to the GMS
    // SystemUpdateActivity on Play devices.
    private const val ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS"

    fun open(context: Context): Boolean {
        val candidates = listOf(
            Intent(ACTION_SYSTEM_UPDATE_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }
}
