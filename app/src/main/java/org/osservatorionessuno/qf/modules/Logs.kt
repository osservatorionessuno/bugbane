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

    // List of logs file and directories to collect. /proc/kmsg is the live kernel
    // ring buffer — a stream that never EOFs, so a file-sync pull of it blocks
    // where it is readable; kept here for parity/coverage because canStat skips it
    // where denied and the AdbSync inactivity timeout bounds it where it streams.
    // The kernel log is also captured non-blocking via `dmesg` below (see run()).
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

        // Kernel log via dmesg (syslog syscall, exits) — the non-blocking companion
        // to the /proc/kmsg pull above; whichever the device permits gets captured.
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
