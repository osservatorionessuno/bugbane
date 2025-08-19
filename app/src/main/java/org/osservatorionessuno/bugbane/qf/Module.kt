package org.osservatorionessuno.bugbane.qf

import android.content.Context
import java.io.File

/**
 * Basic contract for a Quick Forensics module.
 *
 * Implementations may interact with the device through the provided [Shell]
 * or by using Android platform APIs via [Context].
 * All generated artifacts should be written inside [outDir].
 */
interface Module {
    /** Name used for logging or debugging. */
    val name: String

    /**
     * Execute the module.
     *
     * @param context Application context.
     * @param shell A [Shell] instance for device interactions.
     * @param outDir Directory where module output should be written.
     */
    fun run(context: Context, shell: Shell, outDir: File)
}