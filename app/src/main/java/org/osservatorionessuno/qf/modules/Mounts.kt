package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.Utils
import java.io.File

/**
 * Pull all the mount points set int the device.
 */
class Mounts : Module {
    override val name: String = "mounts"
    private val TAG = "MountsModule"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        Log.i(TAG, "Collecting mount information")

        val shell = AdbShell(manager, progress = progress)
        val mountsData = mutableListOf<String>()

        Log.d(TAG, "Running: mount")
        val out1: String
        try {
            out1 = shell.exec("mount").trim()
            if (out1.isNotEmpty()) {
                out1.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { mountsData.add(it) }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "mount command failed or returned empty result")
        }

        Log.d(TAG, "Running: cat /proc/mounts")
        val out2: String
        try {
            out2 = shell.exec("cat /proc/mounts").trim()
            if (out2.isNotEmpty()) {
                out2.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        if (!mountsData.contains(line)) {
                            mountsData.add(line)
                        }
                    }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "cat /proc/mounts command failed or returned empty result")
        }

        Log.d(TAG, "Found ${mountsData.size} mount entries")

        // Save as mounts.json in the acquisition directory
        File(outDir, "mounts.json").writeText(
            Utils.toJsonString(JSONArray(mountsData))
        )
    }
}