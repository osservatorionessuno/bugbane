package org.osservatorionessuno.qf.crypto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * Caches the Argon2id-derived wrap key for the current acquisition identity so a
 * password is entered at most once per session, not once per read.
 *
 * It holds only the *knowledge* factor (the derived key), never the identity itself.
 * The eviction policy is chosen by the caller per tier:
 *  - **two-factor tiers** (fingerprint + password): kept until process death, with
 *    no screen-off eviction — every read still requires a fresh per-operation
 *    fingerprint to unwrap the outer layer, so the cached key is inert on its own
 *    (a seized/locked phone can't use it, and a live attacker who could read it
 *    still can't produce the hardware auth).
 *  - **password-only tier** (no secure lock): the password is the sole factor, so
 *    the key is bounded by [DEFAULT_TTL_MS] and dropped on screen-off, mirroring
 *    [SessionKeyCache] — a seized, locked phone finds it cold.
 *
 * There is a single device identity, so at most one key is cached.
 */
object PassphraseKeyCache {
    const val DEFAULT_TTL_MS = 30L * 60 * 1000

    private val lock = Any()
    private var key: ByteArray? = null
    private var expiresAt: Long = Long.MAX_VALUE
    private var evictOnScreenOff = false

    @Volatile
    private var hookInstalled = false

    /**
     * Cache a copy of [derivedKey]. [ttlMs] null keeps it until process death;
     * [evictScreenOff] drops it when the screen turns off.
     */
    fun put(context: Context, derivedKey: ByteArray, ttlMs: Long?, evictScreenOff: Boolean) {
        if (evictScreenOff) installEvictionHook(context)
        synchronized(lock) {
            key?.fill(0)
            key = derivedKey.copyOf()
            expiresAt = if (ttlMs == null) Long.MAX_VALUE else SystemClock.elapsedRealtime() + ttlMs
            evictOnScreenOff = evictScreenOff
        }
    }

    /** A copy of the cached key, or null if none / expired. Destroy the returned copy when done. */
    fun get(): ByteArray? = synchronized(lock) {
        val k = key ?: return null
        if (SystemClock.elapsedRealtime() >= expiresAt) {
            k.fill(0); key = null; return null
        }
        k.copyOf()
    }

    /** Zero and drop the cached key (password change, identity discard, screen off). */
    fun evict() = synchronized(lock) {
        key?.fill(0)
        key = null
    }

    private fun installEvictionHook(context: Context) {
        if (hookInstalled) return
        synchronized(this) {
            if (hookInstalled) return
            context.applicationContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (synchronized(lock) { evictOnScreenOff }) evict()
                    }
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF),
            )
            hookInstalled = true
        }
    }
}
