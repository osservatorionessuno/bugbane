package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.cadb.AdbSync
import java.io.File

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
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val sync = AdbSync(manager, progress)

        val dest = File(outDir, "logs")
        dest.mkdirs()

        runCatching {
            for (target in targets) {
                if (target.endsWith("/")) {
                    sync.pullFolder(target, dest)
                } else {
                    sync.pull(target, dest)
                }
            }
            Log.i(TAG, "Pulled logs to: ${dest.absolutePath}")
        }
    }
}