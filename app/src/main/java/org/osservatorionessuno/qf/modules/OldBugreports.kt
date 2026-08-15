package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Pulls bugreports already present on the device (androidqf#50). Collection only:
 * analysis of the pulled ZIPs is a separate concern.
 */
class OldBugreports : Module {
    override val name: String = "old_bugreports"
    private val TAG = "OldBugreportsModule"

    // On modern devices the second entry is a symlink to the first: pull from the
    // first directory that has content so nothing is stored twice.
    private val candidateDirs = listOf(
        "/data/user_de/0/com.android.shell/files/bugreports/",
        "/bugreports/",
    )

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val sync = AdbSync(manager, progress)
        for (dir in candidateDirs) {
            val entries = runCatching { sync.list(dir) }.getOrElse { emptyList() }
            if (entries.none { it["path"] != "." && it["path"] != ".." }) continue

            runCatching {
                sync.pullFolder(dir, writer, "bugreports")
                Log.i(TAG, "Pulled old bugreports from $dir")
            }.onFailure {
                // TODO: write this feedback to the acquisition report in some way
                Log.e(TAG, "Failed to pull old bugreports from $dir", it)
            }
            return
        }
        Log.i(TAG, "No old bugreports on the device")
    }
}
