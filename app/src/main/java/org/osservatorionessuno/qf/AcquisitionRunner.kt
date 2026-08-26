package org.osservatorionessuno.qf

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.osservatorionessuno.bugbane.BuildConfig
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.modules.Env
import org.osservatorionessuno.qf.modules.Dumpsys
import org.osservatorionessuno.qf.modules.Files
import org.osservatorionessuno.qf.modules.Logcat
import org.osservatorionessuno.qf.modules.GetProp
import org.osservatorionessuno.qf.modules.Processes
import org.osservatorionessuno.qf.modules.SELinux
import org.osservatorionessuno.qf.modules.Services
import org.osservatorionessuno.qf.modules.Settings
import org.osservatorionessuno.qf.modules.Bugreport
import org.osservatorionessuno.qf.modules.Logs
import org.osservatorionessuno.qf.modules.Mounts
import org.osservatorionessuno.qf.modules.Packages
import org.osservatorionessuno.qf.modules.RootBinaries
import org.osservatorionessuno.qf.modules.Temp
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.qf.storage.AcquisitionIndex
import org.osservatorionessuno.qf.storage.EncryptedAcquisitionWriter
import org.osservatorionessuno.qf.storage.InsufficientStorageException
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.SessionKeyCache

private const val TAG = "AcquisitionRunner"

/** Thrown from the progress callback to abort a module the moment the user cancels. */
private class AcquisitionCancelledException : RuntimeException()

/**
 * Entry point used by the UI layer to trigger an AndroidQF-compatible dump.
 *
 * The class wires the ADB connection with a collection of [Module] instances
 * responsible for generating each file inside the resulting acquisition
 * directory.
 *
 * At this stage only the scaffolding is provided – concrete modules still need
 * to be implemented.
 */
class AcquisitionRunner(
    private val modules: List<Module> = DEFAULT_MODULES
) {

    companion object {
        // The space-hungry modules run last, so low storage only ever costs
        // them and never the smaller high-value modules.
        val DEFAULT_MODULES: List<Module> = listOf(
            Env(),
            Dumpsys(),
            Logs(),
            Logcat(),
            GetProp(),
            Mounts(),
            Processes(),
            RootBinaries(),
            Services(),
            Settings(),
            SELinux(),
            Temp(),
            // Large; skipped first under low storage. The whole-filesystem
            // listing can be big, and Bugreport/Packages are also poorly
            // compressible.
            Files(),
            Bugreport(),
            Packages(),
        )

        val MODULE_NAMES: List<String> = DEFAULT_MODULES.map { it.name }

        // Byte-level callbacks arrive per transfer chunk; cap what reaches the
        // UI at ~10 Hz or every 4 MiB, whichever comes first.
        private const val PROGRESS_REPORT_INTERVAL_NANOS = 100_000_000L
        private const val PROGRESS_REPORT_BYTES = 4L shl 20
    }

    /**
     * Listener used to report progress and check for cancellation.
     */
    interface ProgressListener {
        fun onModuleStart(name: String, completed: Int, total: Int)
        fun onModuleProgress(name: String, bytes: Long)
        fun onModuleComplete(name: String, completed: Int, total: Int, success: Boolean)
        /** A module skipped because storage ran low. */
        fun onModuleSkipped(name: String) {}
        fun isCancelled(): Boolean
        fun onFinished(cancelled: Boolean, output: File?)
    }

    /**
     * Run all registered modules and store their output inside a newly created
     * acquisition directory located under [baseOutputDir].
     *
     * @param context Application context.
     * @param manager Active ADB connection manager.
     * @param baseOutputDir Directory where the acquisition folder will be created.
     * @return The directory containing the acquisition results.
     */
    @Throws(IOException::class)
    fun run(
        context: Context,
        manager: AdbConnectionManager,
        baseOutputDir: File,
        listener: ProgressListener? = null
    ): File {
        if (!baseOutputDir.exists() && !baseOutputDir.mkdirs()) {
            throw IOException("Unable to create base output directory: $baseOutputDir")
        }

        val started = Instant.now()

        val acquisitionDir = File(baseOutputDir, UUID.randomUUID().toString())
        if (!acquisitionDir.mkdirs()) {
            throw IOException("Unable to create acquisition directory: $acquisitionDir")
        }
        Log.i(TAG, "Starting acquisition in ${acquisitionDir.absolutePath}")

        var cancelled = false
        // The dir once the index is written; null on setup failure so the UI never
        // navigates into an unreadable acquisition.
        var output: File? = null
        try {
            val shell = AdbShell(manager)
            val cpu = shell.execFirstLine("getprop ro.product.cpu.abi")
            var tmpDir = "/data/local/tmp/"
            var sdCard = "/sdcard/"
            shell.execForEachLine("env") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("TMPDIR=") -> tmpDir = trimmed.removePrefix("TMPDIR=")
                    trimmed.startsWith("EXTERNAL_STORAGE=") -> sdCard = trimmed.removePrefix("EXTERNAL_STORAGE=")
                }
            }
            if (!tmpDir.endsWith('/')) tmpDir += '/'
            if (!sdCard.endsWith('/')) sdCard += '/'

            val total = modules.size
            var completedCount = 0
            val failedModules = mutableListOf<String>()
            val skippedModules = mutableListOf<String>()

            val adbHostKey = runCatching { manager.hostPublicKey() }
                .onFailure { Log.w(TAG, "Could not encode host adb public key", it) }
                .getOrNull()

            var index = AcquisitionIndex(
                uuid = acquisitionDir.name,
                status = AcquisitionIndex.STATUS_RUNNING,
                created = started.toString(),
                completed = null,
                bugbaneVersion = BuildConfig.VERSION_NAME,
                storagePath = acquisitionDir.absolutePath,
                tmpDir = tmpDir,
                sdcard = sdCard,
                cpu = cpu,
                analysisDir = AcquisitionIndex.ANALYSIS_DIR,
                adbHostPublicKey = adbHostKey,
            )

            // Encrypting needs only the public acquisition identity, so it never
            // prompts. The fresh file key is cached so the first analysis doesn't
            // prompt either (see SessionKeyCache).
            val recipient = AcquisitionIdentityVault.recipient(context)
            // On devices with no secure lock the acquisition is encrypted to an
            // in-memory ephemeral key until the user sets a password; record it so an
            // unsealed archive is swept if the process dies before that happens.
            if (AcquisitionIdentityVault.hasPendingEphemeral()) {
                AcquisitionIdentityVault.markUnsealed(context, acquisitionDir)
            }
            var fileKey: ByteArray? = null
            val writer = EncryptedAcquisitionWriter(
                acquisitionDir,
                listOf(recipient),
                onFileKey = { fileKey = it },
            )

            try {
                adbHostKey?.let { key ->
                    runCatching {
                        writer.useArtifact("adb_host_key.pub") { it.write((key + "\n").toByteArray()) }
                    }.onFailure { Log.w(TAG, "Failed to write adb_host_key.pub", it) }
                }

                for (module in modules) {
                    if (listener?.isCancelled() == true) {
                        Log.i(TAG, "Acquisition cancelled before module ${module.name}")
                        cancelled = true
                        break
                    }
                    // Re-check free space at each boundary so a disk that was
                    // already full at the start, or filled by another app between
                    // modules, trips before we write anything.
                    writer.refreshOutOfSpace()
                    // Once out of space, skip this and every remaining module.
                    if (writer.outOfSpace) {
                        skippedModules += module.name
                        listener?.onModuleSkipped(module.name)
                        continue
                    }

                    var moduleBytes = 0L
                    var lastReportBytes = 0L
                    var lastReportNanos = 0L
                    val progressCb: (Long) -> Unit = { delta ->
                        // Honor cancellation mid-transfer, not just between modules.
                        if (listener?.isCancelled() == true) throw AcquisitionCancelledException()
                        moduleBytes += delta
                        val now = System.nanoTime()
                        if (moduleBytes - lastReportBytes >= PROGRESS_REPORT_BYTES ||
                            now - lastReportNanos >= PROGRESS_REPORT_INTERVAL_NANOS
                        ) {
                            lastReportBytes = moduleBytes
                            lastReportNanos = now
                            listener?.onModuleProgress(module.name, moduleBytes)
                        }
                        Unit
                    }
                    Log.i(TAG, "Running module ${module.name}")
                    listener?.onModuleStart(module.name, completedCount, total)
                    var success = true
                    try {
                        module.run(context, manager, writer, progressCb)
                        // Flush the throttled tail so the completed card shows the real total.
                        if (moduleBytes > lastReportBytes) {
                            listener?.onModuleProgress(module.name, moduleBytes)
                        }
                        Log.i(TAG, "Module ${module.name} finished")
                    } catch (ise: InsufficientStorageException) {
                        Log.w(TAG, "Module ${module.name} hit the storage reserve")
                    } catch (c: AcquisitionCancelledException) {
                        Log.i(TAG, "Acquisition cancelled during module ${module.name}")
                        cancelled = true
                        break
                    } catch (t: Throwable) {
                        success = false
                        Log.e(TAG, "Module ${module.name} failed", t)
                    }
                    // The latched guard, not the throw, is authoritative (modules may swallow it).
                    if (writer.outOfSpace) {
                        Log.w(TAG, "Skipping ${module.name}: out of space")
                        skippedModules += module.name
                        listener?.onModuleSkipped(module.name)
                        continue
                    }
                    if (!success) failedModules += module.name
                    completedCount++
                    listener?.onModuleComplete(module.name, completedCount, total, success)
                }

                val completed = Instant.now()
                index = if (cancelled) index.markAsCancelled(completed)
                    else index.markAsFinished(completed, failedModules, skippedModules)
                writer.writeIndex(index)
                output = acquisitionDir
            } catch (io: IOException) {
                // Finalizing hit the disk despite the reserve; keep what was collected.
                Log.e(TAG, "Failed to finalize acquisition", io)
            } finally {
                runCatching { writer.close() }
                    .onFailure { Log.e(TAG, "Failed to close acquisition archive", it) }
            }

            // Cache only once the writer is closed: a screen-off during the (long)
            // acquisition evicts the cache, so an early put would never survive
            // until the automatic first analysis.
            fileKey?.let { SessionKeyCache.put(context, acquisitionDir, it) }
        } finally {
            // Report finished even when setup (env probe, recipient, writer) throws,
            // so the UI never hangs in the scanning state.
            Log.i(TAG, "Acquisition finished in ${acquisitionDir.absolutePath}")
            listener?.onFinished(cancelled, output)
        }
        return acquisitionDir
    }
}
