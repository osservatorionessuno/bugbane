package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.File

/**
 * Pull all the temporary files from the device.
 */
class Temp : Module {
    override val name: String = "temp"
    private val TAG = "TempModule"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val sync = AdbSync(manager, progress)

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