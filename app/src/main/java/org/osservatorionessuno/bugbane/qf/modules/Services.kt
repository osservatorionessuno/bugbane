package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Collect the list of system services.
 * Saves to services.txt
 */
class Services : Module {
    override val name: String = "services"

    override fun run(context: Context, manager: AbsAdbConnectionManager, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        val shell = Shell(manager)
        shell.execToFile("service list", File(outDir, "services.txt"))
    }
}