package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Sample module that collects logcat output.
 */
class Logcat : Module {
    override val name: String = "logcat"

    override fun run(context: Context, manager: AbsAdbConnectionManager, outDir: File) {
        val shell = Shell(manager)
        shell.execToFile("logcat -d -b all \"*:V\"", File(outDir, "logcat.txt"))
        try {
            shell.execToFile("logcat -L -b all \"*:V\"", File(outDir, "logcat_old.txt"))
        } catch (_: Throwable) {
            // best-effort
        }
    }
}