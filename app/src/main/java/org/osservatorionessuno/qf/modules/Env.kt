package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Sample module that captures the shell environment variables using `env`.
 */
class Env : Module {
    override val name: String = "env"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = Shell(manager, progress = progress)
        shell.execToFile("env", File(outDir, "env.txt"))
    }
}