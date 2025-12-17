package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Collects the list of running processes using `ps -A`.
 * Saves to processes.txt.
 */
class Processes : Module {
    override val name: String = "processes"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        shell.execToFile("ps -A", File(outDir, "processes.txt"))
    }
}
