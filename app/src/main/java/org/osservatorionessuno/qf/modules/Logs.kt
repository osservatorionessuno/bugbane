package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Pull all the various logs files and directories from the device.
 */
class Logs : Module {
    override val name: String = "logs"
    private val TAG = "LogsModule"

    // List of logs file and directories to collect
    private val targets = listOf(
		"/data/system/uiderrors.txt",
		"/proc/kmsg",
		"/proc/last_kmsg",
		"/sys/fs/pstore/console-ramoops",
        "/data/anr/",
        "/data/log/",
        "/sdcard/log/"
    )

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val sync = AdbSync(manager, progress)

        val result = runCatching {
            for (target in targets) {
                if (target.endsWith("/")) {
                    sync.pullFolder(target, writer, "logs")
                } else {
                    val name = target.substringAfterLast('/')
                    writer.useArtifact("logs/$name") { output ->
                        sync.pull(target, output)
                    }
                }
            }
            Log.i(TAG, "Pulled logs")
        }
        if (result.isFailure) {
            // TODO: write this feedback to the acquisition report in some way
            Log.e(TAG, "Failed to pull logs", result.exceptionOrNull())
        }
    }
}
