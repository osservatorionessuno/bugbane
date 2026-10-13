package org.osservatorionessuno.qf.modules

import android.content.Context
import java.io.OutputStream
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.AcquisitionProgressTracker
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Android 16 intrusion logs (Advanced Protection), as androidqf's intrusion_logs module.
 *
 * The framework keeps no copy readable by shell: the logs live end-to-end encrypted in
 * the Google account, and only GMS's retrieval screen downloads and decrypts them, as
 * JSON lines under [LOG_DIR]. We open that screen, wait for the user to leave it, then
 * pull the folder, including logs downloaded before.
 */
class IntrusionLogs : Module {
    override val name: String = "intrusion_logs"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val shell = AdbShell(manager, progress = progress, timeoutMs = (WAIT_SECONDS + 60) * 1000L)
        // "null" where Advanced Protection is unsupported, "1" when on.
        val mode = shell.execFirstLine("settings get secure advanced_protection_mode")
        log.info("Advanced Protection mode: $mode")
        if (mode == "1") {
            shell.execToStream("am start -W -n $RETRIEVAL_ACTIVITY", OutputStream.nullOutputStream())
            log.step(R.string.step_intrusion_logs_download)
            // Leaving the screen ends the wait: back (it's gone, as when it never opened), or
            // home or Bugbane on top. One looping command; the dots keep it from going idle.
            val loop = "h=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | cut -d/ -f1); " +
                "i=0; while [ \$i -lt ${WAIT_SECONDS / 2} ]; do " +
                "a=\$(dumpsys activity activities | grep -E 'intrusiondetection|ResumedActivity'); " +
                "echo \"\$a\" | grep -q intrusiondetection || break; " +
                "echo \"\$a\" | grep ResumedActivity | grep -qE \" (${context.packageName}|\$h)/\" && break; " +
                "echo .; sleep 2; i=\$((i+1)); done"
            AcquisitionProgressTracker.postIntrusionLogsNotification(context)
            try {
                shell.execToStream(loop, OutputStream.nullOutputStream())
            } finally {
                AcquisitionProgressTracker.cancelIntrusionLogsNotification(context)
            }
        }

        val sync = AdbSync(manager, progress)
        if (sync.canStat(LOG_DIR)) {
            sync.pullFolder(LOG_DIR, writer, "intrusion_logs") { log.step(R.string.step_copying_file, it) }
        }
    }

    private companion object {
        const val LOG_DIR = "/sdcard/Download/Intrusion Logging/"
        const val RETRIEVAL_ACTIVITY =
            "com.google.android.gms/.intrusiondetection.ui.retrieval.IntrusionDetectionRetrievalActivity"
        // androidqf waits as long.
        const val WAIT_SECONDS = 15 * 60
    }
}
