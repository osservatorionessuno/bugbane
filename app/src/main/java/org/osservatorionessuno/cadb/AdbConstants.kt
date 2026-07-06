package org.osservatorionessuno.cadb

object AdbConstants {
    // Request commands
    const val RECV = "RECV"
    const val LIST = "LIST"
    const val LIS2 = "LIS2"

    // Response commands
    const val DATA = "DATA"
    const val DENT = "DENT"
    const val DNT2 = "DNT2"
    const val DONE = "DONE"
    const val FAIL = "FAIL"

    /** Trailing bytes after LIST v1 DONE on modern AOSP adb daemons (mode/size/mtime/namelen). */
    const val LIST_V1_DONE_TAIL = 16

    /** Trailing bytes after LIS2 DONE once the 4-byte id has been consumed. */
    const val LIST_V2_DONE_TAIL = 72
}
