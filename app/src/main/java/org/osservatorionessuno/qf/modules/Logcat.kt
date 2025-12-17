package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Sample module that collects logcat output.
 */
class Logcat : Module {
    override val name: String = "logcat"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        shell.execToFile("logcat -d -b all \"*:V\"", File(outDir, "logcat.txt"))
        try {
            shell.execToFile("logcat -L -b all \"*:V\"", File(outDir, "logcat_old.txt"))
        } catch (_: Throwable) {
            // best-effort
        }
    }
}