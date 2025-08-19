package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File

/**
 * Collects device properties via `getprop`.
 * Output: getprop.txt
 */
class GetProp : Module {
    override val name: String = "getprop"

    override fun run(context: Context, shell: Shell, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        shell.execToFile("getprop", File(outDir, "getprop.txt"))
    }
}