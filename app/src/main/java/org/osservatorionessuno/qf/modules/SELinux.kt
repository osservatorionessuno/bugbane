package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Collects SELinux status via `getenforce`.
 * Output: selinux.txt
 */
class SELinux : Module {
    override val name: String = "selinux"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        shell.execToFile("getenforce", File(outDir, "selinux.txt"))
    }
}