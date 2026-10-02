package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.AcquisitionLog
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
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val sync = AdbSync(manager, progress)

        val result = runCatching {
            sync.pullFolder("/data/local/tmp/", writer, "tmp") { log.step(R.string.step_copying_file, it) }
            Log.i(TAG, "Pulled temp")
        }
        result.onFailure { log.warning("Failed to pull /data/local/tmp/: ${it.message}") }
    }
}
