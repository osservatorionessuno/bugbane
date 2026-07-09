package org.osservatorionessuno.qf.modules

import android.content.Context
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Collect Android settings namespaces: system, secure, global.
 * Saves to settings_system.txt, settings_secure.txt, settings_global.txt
 */
class Settings : Module {
    override val name: String = "settings"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val namespaces = listOf("system", "secure", "global")
        val shell = AdbShell(manager, progress = progress)
        for (namespace in namespaces) {
            writer.useArtifact("settings_${namespace}.txt") { output ->
                shell.execToStream("cmd settings list $namespace", output)
            }
        }
    }
}
