package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
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
        progress: ((Long) -> Unit)?
    ) {
        // logcat -d can dump large buffers and stay quiet while the device reads them
        val shell = AdbShell(
            manager = manager,
            progress = progress,
            timeoutMs = 5 * 60_000L,
            inactivityMs = 30_000L,
        )
        writer.useArtifact("logcat.txt") { output ->
            shell.execToStream("logcat -d -b all \"*:V\"", output)
        }
        try {
            writer.useArtifact("logcat_old.txt") { output ->
                shell.execToStream("logcat -L -b all \"*:V\"", output)
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }
}
