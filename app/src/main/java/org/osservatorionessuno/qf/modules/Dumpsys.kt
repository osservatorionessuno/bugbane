package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Sample module that runs `dumpsys` and stores the output.
 */
class Dumpsys : Module {
    override val name: String = "dumpsys"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        writer.useArtifact("dumpsys.txt") { output ->
            shell.execToStream("dumpsys", output)
        }
    }
}
