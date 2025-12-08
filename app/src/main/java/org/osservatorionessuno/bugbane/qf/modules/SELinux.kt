package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Collects SELinux status via `getenforce`.
 * Output: selinux.txt
 */
class SELinux : Module {
    override val name: String = "selinux"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = Shell(manager, progress = progress)
        shell.execToFile("getenforce", File(outDir, "selinux.txt"))
    }
}