package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Sample module that captures the shell environment variables using `env`.
 */
class Env : Module {
    override val name: String = "env"

    override fun run(context: Context, manager: AbsAdbConnectionManager, outDir: File) {
        val shell = Shell(manager)
        shell.execToFile("env", File(outDir, "env.txt"))
    }
}