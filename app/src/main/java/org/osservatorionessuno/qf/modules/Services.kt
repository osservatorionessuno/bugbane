package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Collect the list of system services.
 * Saves to services.txt
 */
class Services : Module {
    override val name: String = "services"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        shell.execToFile("service list", File(outDir, "services.txt"))
    }
}