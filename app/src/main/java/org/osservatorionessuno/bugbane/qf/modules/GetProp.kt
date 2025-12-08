package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Collects device properties via `getprop`.
 * Output: getprop.txt
 */
class GetProp : Module {
    override val name: String = "getprop"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = Shell(manager, progress = progress)
        shell.execToFile("getprop", File(outDir, "getprop.txt"))
    }
}