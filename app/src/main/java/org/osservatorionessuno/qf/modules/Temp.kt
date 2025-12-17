package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Sync
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

/**
 * Pull all the temporary files from the device.
 */
class Temp : Module {
    override val name: String = "temp"
    private val TAG = "TempModule"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val sync = Sync(manager, progress)

        val dest = File(outDir, "tmp")
        dest.mkdirs()

        try {
            sync.pullFolder("/data/local/tmp/", dest)
            Log.i(TAG, "Pulled temp to: ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull temp", e)
        }
    }
}