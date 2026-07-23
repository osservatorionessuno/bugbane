package org.osservatorionessuno.bugbane.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.osservatorionessuno.bugbane.INTENT_EXIT_BACKPRESS
import org.osservatorionessuno.bugbane.SlideshowActivity
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import java.io.File

/**
 * Recovery from an acquisition identity that was permanently invalidated (the
 * device's secure lock was removed, erasing the auth-gated key). Every acquisition
 * was encrypted to that one identity, so all are now unreadable.
 */
object AcquisitionRecovery {

    /**
     * Discard the dead identity, delete its unreadable acquisitions, and send the
     * user back into onboarding to re-establish protection (set a screen lock or a
     * password). Finishes the calling activity.
     */
    fun begin(context: Context) {
        AcquisitionIdentityVault.beginRecovery(context)
        // Every acquisition was encrypted to the discarded identity, so all are now
        // unreadable — drop them.
        File(context.filesDir, "acquisitions").deleteRecursively()
        // Recompute onboarding state now so the slideshow opens on the protection
        // step instead of briefly resolving a stale state.
        runCatching { SlideshowManager.checkState() }

        val intent = Intent(context, SlideshowActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Launched from inside the app, so back should behave normally, not quit.
            putExtra(INTENT_EXIT_BACKPRESS, false)
        }
        context.startActivity(intent)
        (context as? Activity)?.finish()
    }
}
