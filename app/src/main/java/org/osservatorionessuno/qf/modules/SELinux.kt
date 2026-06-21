package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Collects SELinux status via `getenforce`.
 * Output: selinux.txt
 */
class SELinux : Module {
    override val name: String = "selinux"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        writer.useArtifact("selinux.txt") { output ->
            shell.execToStream("getenforce", output)
        }
    }
}
