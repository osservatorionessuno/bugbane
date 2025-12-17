package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Sample module that collects logcat output.
 */
class Logcat : Module {
    override val name: String = "logcat"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = Shell(manager, progress = progress)
        shell.execToFile("logcat -d -b all \"*:V\"", File(outDir, "logcat.txt"))
        try {
            shell.execToFile("logcat -L -b all \"*:V\"", File(outDir, "logcat_old.txt"))
        } catch (_: Throwable) {
            // best-effort
        }
    }
}