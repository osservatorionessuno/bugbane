package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Pull all the temporary files from the device.
 */
class Temp : Module {
    override val name: String = "temp"
    private val TAG = "TempModule"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val sync = AdbSync(manager, progress)

        val result = runCatching {
            sync.pullFolder("/data/local/tmp/", writer, "tmp")
            Log.i(TAG, "Pulled temp")
        }
        if (result.isFailure) {
            // TODO: write this feedback to the acquisition report in some way
            Log.e(TAG, "Failed to pull temp", result.exceptionOrNull())
        }
    }
}
