package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Sample module that runs `dumpsys` and stores the output.
 */
class Dumpsys : Module {
    override val name: String = "dumpsys"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        shell.execToFile("dumpsys", File(outDir, "dumpsys.txt"))
    }
}