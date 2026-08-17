package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Pull all the various logs files and directories from the device.
 */
class Logs : Module {
    override val name: String = "logs"
    private val TAG = "LogsModule"

    // List of logs file and directories to collect. The live kernel ring buffer
    // (/proc/kmsg) is NOT here: it is a stream that blocks on read with no EOF, so
    // a file-sync pull hangs — it is captured via `dmesg` below, which exits.
    private val targets = listOf(
		"/data/system/uiderrors.txt",
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

        // Kernel log via dmesg (syslog syscall, exits) rather than reading
        // /proc/kmsg (a stream that never EOFs). AdbShell bounds it by timeout.
        runCatching {
            val shell = AdbShell(manager, progress = null)
            writer.useArtifact("logs/kmsg.txt") { output ->
                shell.execToStream("dmesg", output)
            }
        }.onFailure { Log.w(TAG, "dmesg capture failed", it) }

        // Several targets are root-only on production devices: skip per target so one
        // denied path cannot cost the readable ones.
        for (target in targets) {
            runCatching {
                if (target.endsWith("/")) {
                    sync.pullFolder(target, writer, "logs")
                } else {
                    // Stat first: a failed pull would still commit an empty artifact entry.
                    if (!sync.canStat(target)) {
                        Log.w(TAG, "Skipping $target: missing or inaccessible")
                        return@runCatching
                    }
                    val name = target.substringAfterLast('/')
                    writer.useArtifact("logs/$name") { output ->
                        sync.pull(target, output)
                    }
                }
            }.onFailure {
                // TODO: write this feedback to the acquisition report in some way
                Log.e(TAG, "Failed to pull $target", it)
            }
        }
        Log.i(TAG, "Pulled logs")
    }
}
