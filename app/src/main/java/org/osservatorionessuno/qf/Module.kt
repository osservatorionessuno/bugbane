package org.osservatorionessuno.qf

import android.content.Context
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Basic contract for a Quick Forensics module.
 *
 * Implementations may interact with the device through the provided
 * [AdbConnectionManager] or by using Android platform APIs via
 * [Context]. All generated artifacts should be written through [writer].
 */
interface Module {
    /** Name used for logging or debugging. */
    val name: String

    /**
     * Execute the module.
     *
     * @param context Application context.
     * @param manager Active ADB connection manager.
     * @param writer Writer to write artifacts to.
     * @param progress Optional callback to report bytes processed.
     */
    fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)? = null,
    )
}