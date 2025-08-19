package org.osservatorionessuno.bugbane.qf

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Basic contract for a Quick Forensics module.
 *
 * Implementations may interact with the device through the provided
 * [AbsAdbConnectionManager] or by using Android platform APIs via
 * [Context]. All generated artifacts should be written inside [outDir].
 */
interface Module {
    /** Name used for logging or debugging. */
    val name: String

    /**
     * Execute the module.
     *
     * @param context Application context.
     * @param manager Active ADB connection manager.
     * @param outDir Directory where module output should be written.
     * @param progress Optional callback to report bytes processed.
     */
    fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)? = null,
    )
}