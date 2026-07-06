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
import org.osservatorionessuno.qf.crypto.AndroidKeystoreKeyVault
import org.osservatorionessuno.qf.crypto.AndroidKeystoreKeyVault.StrongBoxPolicy

private const val TAG = "AcquisitionRunner"

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
    private val modules: List<Module> = listOf(
        Env(),
        Dumpsys(),
        Files(),
        Bugreport(),
        Logs(),
        Logcat(),
        GetProp(),
        Mounts(),
        Packages(),
        Processes(),
        RootBinaries(),
        Services(),
        Settings(),
        SELinux(),
        Temp()
    )
) {

    companion object {
        const val ACQUISITION_KEY_ALIAS = "bugbane.acquisition.kek"

        fun acquisitionKeyVault(): AndroidKeystoreKeyVault =
            AndroidKeystoreKeyVault.getOrCreateKeyVault(ACQUISITION_KEY_ALIAS, StrongBoxPolicy.PREFER)
    }

    /**
     * Listener used to report progress and check for cancellation.
     */
    interface ProgressListener {
        fun onModuleStart(name: String, completed: Int, total: Int)
        fun onModuleProgress(name: String, bytes: Long)
        fun onModuleComplete(name: String, completed: Int, total: Int)
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

        val shell = AdbShell(manager)
        val cpu = shell.exec("getprop ro.product.cpu.abi").trim()
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

        var index = AcquisitionIndex(
            uuid = acquisitionDir.name,
            status = AcquisitionIndex.STATUS_RUNNING,
            created = started.toString(),
            completed = null,
            androidqfVersion = BuildConfig.VERSION_NAME,
            storagePath = acquisitionDir.absolutePath,
            tmpDir = tmpDir,
            sdcard = sdCard,
            cpu = cpu,
            analysisDir = AcquisitionIndex.ANALYSIS_DIR,
        )

        val vault = AcquisitionRunner.acquisitionKeyVault()
        val writer = EncryptedAcquisitionWriter(acquisitionDir, vault)

        var cancelled = false
        writer.use {
            for (module in modules) {
                if (listener?.isCancelled() == true) {
                    Log.i(TAG, "Acquisition cancelled before module ${module.name}")
                    cancelled = true
                    break
                }

                var moduleBytes = 0L
                val progressCb: (Long) -> Unit = { delta ->
                    moduleBytes += delta
                    listener?.onModuleProgress(module.name, moduleBytes)
                    Unit
                }
                Log.i(TAG, "Running module ${module.name}")
                listener?.onModuleStart(module.name, completedCount, total)
                try {
                    module.run(context, manager, writer, progressCb)
                    Log.i(TAG, "Module ${module.name} finished")
                } catch (t: Throwable) {
                    Log.e(TAG, "Module ${module.name} failed", t)
                    // TODO: display error message to the user
                }
                completedCount++
                listener?.onModuleComplete(module.name, completedCount, total)
            }

            val completed = Instant.now()
            index = if (cancelled) {
                index.markAsCancelled(completed)
            } else {
                index.markAsComplete(completed)
            }
            writer.writeIndex(index)
        }

        Log.i(TAG, "Acquisition complete in ${acquisitionDir.absolutePath}")
        listener?.onFinished(cancelled, acquisitionDir)
        return acquisitionDir
    }
}