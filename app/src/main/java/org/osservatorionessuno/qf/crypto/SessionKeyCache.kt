package org.osservatorionessuno.qf.crypto

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import org.osservatorionessuno.qf.crypto.age.FileKeyIdentity
import java.io.File

/**
 * Session-scoped cache of per-archive file keys, cached as an acquisition
 * finishes writing, so the first analysis/export/share right after doesn't
 * prompt for the identity unlock: the plaintext just transited this process, so
 * retaining its (single-archive) file key for a bounded window adds no
 * data-at-rest exposure beyond what a live compromise would already capture.
 *
 * Deliberately narrow:
 *  - holds per-archive file keys, never the acquisition identity — every other
 *    acquisition keeps its full unlock gate;
 *  - entries expire after [TTL_MS] and are zeroed on eviction, on device lock
 *    (screen off), and with the process.
 *
 * Reboot clears the cache by construction (process death). Because it holds only
 * the file key of the *just-written* archive, an attacker who reads it has to
 * already be live-root on the running device — which beats every tier anyway.
 */
object SessionKeyCache {
    private const val TTL_MS = 30L * 60 * 1000

    private class Entry(val secret: Secret, val expiresAt: Long)

    private val entries = HashMap<String, Entry>()

    // All access is serialized so a screen-off evictAll() can't zero a key while
    // identityFor() is mid-copy of it (a torn read).
    private val lock = Any()

    @Volatile
    private var hookInstalled = false

    /** Register the file key of a freshly written archive. Takes ownership of [fileKey]. */
    fun put(context: Context, acquisitionDir: File, fileKey: ByteArray) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        synchronized(lock) {
            // Never cache into a locked device, but only when a secure lock exists:
            // with no keyguard (the first-run ephemeral window) screen-off is not a
            // protection boundary and this key is the first analysis's only handle.
            if (keyguard.isKeyguardSecure && !power.isInteractive) {
                fileKey.fill(0)
                return
            }
            installEvictionHook(context)
            evictExpired()
            entries.put(acquisitionDir.absolutePath, Entry(Secret(fileKey), SystemClock.elapsedRealtime() + TTL_MS))
                ?.secret?.close()
        }
    }

    /**
     * Identity able to read [acquisitionDir]'s archive if its key is still cached,
     * else null. The identity holds its own copy — destroy it when the action ends.
     */
    fun identityFor(acquisitionDir: File): FileKeyIdentity? = synchronized(lock) {
        evictExpired()
        entries[acquisitionDir.absolutePath]?.let { entry -> entry.secret.withBytes { FileKeyIdentity(it) } }
    }

    /** Zero and drop every cached key (device lock, sign-out, tests). */
    fun evictAll() = synchronized(lock) {
        entries.values.forEach { it.secret.close() }
        entries.clear()
    }

    /** Caller must hold [lock]. */
    private fun evictExpired() {
        val now = SystemClock.elapsedRealtime()
        entries.values.removeIf { entry ->
            (entry.expiresAt < now).also { expired -> if (expired) entry.secret.close() }
        }
    }

    private fun installEvictionHook(context: Context) {
        if (hookInstalled) return
        synchronized(this) {
            if (hookInstalled) return
            val app = context.applicationContext
            // A seized phone is usually locked; drop the cache the moment the
            // screen turns off so a later unlock (even with a known PIN) finds it cold.
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = evictAll()
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF),
            )
            hookInstalled = true
        }
    }
}
