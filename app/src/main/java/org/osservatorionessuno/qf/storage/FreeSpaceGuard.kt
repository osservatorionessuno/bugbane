package org.osservatorionessuno.qf.storage

import android.os.StatFs
import java.io.File
import java.io.IOException

/** Free space kept in reserve while writing, leaving room to finalize the archive. */
const val ACQUISITION_FREE_SPACE_RESERVE_BYTES: Long = 5L * 1024 * 1024

/** Free bytes on [dir]'s filesystem; MAX_VALUE if unreadable. */
fun availableAcquisitionBytes(dir: File): Long =
    try {
        StatFs(dir.path).availableBytes
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

class InsufficientStorageException(val availableBytes: Long, val reserveBytes: Long) :
    IOException("Free space $availableBytes below reserve $reserveBytes")

/**
 * Latches [tripped] and throws once free space drops below [reserveBytes],
 * re-checking every [checkIntervalBytes] rather than per write. [checkNow]
 * forces an out-of-band sample (e.g. before a module starts). [availableBytes]
 * is injected for testing.
 */
class FreeSpaceGuard(
    private val reserveBytes: Long,
    private val checkIntervalBytes: Long = 2L * 1024 * 1024,
    private val availableBytes: () -> Long,
) {
    @Volatile
    var tripped: Boolean = false
        private set

    private var bytesSinceCheck: Long = 0

    /** Records bytes about to be written; throws before the write once space runs low. */
    @Synchronized
    fun record(len: Int) {
        if (tripped) throw exception()
        bytesSinceCheck += len
        if (bytesSinceCheck >= checkIntervalBytes) {
            bytesSinceCheck = 0
            if (isLow()) {
                tripped = true
                throw exception()
            }
        }
    }

    /** Samples free space immediately and trips (without throwing) if it is already low. */
    @Synchronized
    fun checkNow() {
        if (tripped) return
        bytesSinceCheck = 0
        if (isLow()) tripped = true
    }

    private fun isLow() = reserveBytes > 0 && availableBytes() < reserveBytes

    private fun exception() = InsufficientStorageException(availableBytes(), reserveBytes)
}
