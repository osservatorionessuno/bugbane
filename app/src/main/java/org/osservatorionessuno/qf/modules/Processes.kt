package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Collects the list of running processes using `ps -A`.
 * Saves to processes.txt.
 */
class Processes : Module {
    override val name: String = "processes"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        writer.useArtifact("processes.txt") { output ->
            shell.execToStream("ps -A", output)
        }
    }
}
