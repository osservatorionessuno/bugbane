package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Collect Android settings namespaces: system, secure, global.
 * Saves to settings_system.txt, settings_secure.txt, settings_global.txt
 */
class Settings : Module {
    override val name: String = "settings"

    override fun run(context: Context, shell: Shell, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        val namespaces = listOf("system", "secure", "global")
        for (ns in namespaces) {
            val outFile = File(outDir, "settings_${ns}.txt")
            shell.execToFile("cmd settings list $ns", outFile)
        }
    }
}