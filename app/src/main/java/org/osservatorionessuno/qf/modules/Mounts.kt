package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.ArtifactProtobuf
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Pull all the mount points set int the device.
 */
class Mounts : Module {
    override val name: String = "mounts"
    private val TAG = "MountsModule"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        Log.i(TAG, "Collecting mount information")

        val shell = AdbShell(manager, progress = progress)
        val seen = LinkedHashSet<String>()

        writer.useArtifact("mounts.pb") { output ->
            Log.d(TAG, "Running: mount")
            runCatching {
                shell.execForEachLine("mount") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && seen.add(trimmed)) {
                        ArtifactProtobuf.writeDelimitedStringRecord(output, trimmed)
                    }
                }
            }.onFailure {
                Log.d(TAG, "mount command failed or returned empty result")
            }

            Log.d(TAG, "Running: cat /proc/mounts")
            runCatching {
                shell.execForEachLine("cat /proc/mounts") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && seen.add(trimmed)) {
                        ArtifactProtobuf.writeDelimitedStringRecord(output, trimmed)
                    }
                }
            }.onFailure {
                Log.d(TAG, "cat /proc/mounts command failed or returned empty result")
            }
        }

        Log.i(TAG, "Found ${seen.size} mount entries")
    }
}
