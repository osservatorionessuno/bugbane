package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Sample module that runs `dumpsys` and stores the output.
 */
class Dumpsys : Module {
    override val name: String = "dumpsys"

    override fun run(context: Context, shell: Shell, outDir: File) {
        shell.execToFile("dumpsys", File(outDir, "dumpsys.txt"))
    }
}