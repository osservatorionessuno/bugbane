package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Collects the list of running processes using `ps -A`.
 * Saves to processes.txt.
 */
class Processes : Module {
    override val name: String = "processes"

    override fun run(context: Context, shell: Shell, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        shell.execToFile("ps -A", File(outDir, "processes.txt"))
    }
}
