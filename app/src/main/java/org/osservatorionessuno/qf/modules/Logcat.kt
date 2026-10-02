package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Sample module that collects logcat output.
 */
class Logcat : Module {
    override val name: String = "logcat"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val shell = AdbShell(manager, progress = progress)
        log.step(R.string.step_logcat_current)
        writer.useArtifact("logcat.txt") { output ->
            shell.execToStream("logcat -d -b all \"*:V\"", output)
        }
        try {
            log.step(R.string.step_logcat_old)
            writer.useArtifact("logcat_old.txt") { output ->
                shell.execToStream("logcat -L -b all \"*:V\"", output)
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }
}
