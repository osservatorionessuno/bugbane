package org.osservatorionessuno.bugbane.qf

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.osservatorionessuno.bugbane.BuildConfig
import org.osservatorionessuno.bugbane.utils.Utils
import org.osservatorionessuno.bugbane.qf.modules.Env
import org.osservatorionessuno.bugbane.qf.modules.Dumpsys
import org.osservatorionessuno.bugbane.qf.modules.Files
import org.osservatorionessuno.bugbane.qf.modules.Logcat
import org.osservatorionessuno.bugbane.qf.modules.GetProp
import org.osservatorionessuno.bugbane.qf.modules.Processes
import org.osservatorionessuno.bugbane.qf.modules.SELinux
import org.osservatorionessuno.bugbane.qf.modules.Services
import org.osservatorionessuno.bugbane.qf.modules.Settings
import org.osservatorionessuno.bugbane.qf.modules.Bugreport
import org.osservatorionessuno.bugbane.qf.modules.Logs
import org.osservatorionessuno.bugbane.qf.modules.Mounts
import org.osservatorionessuno.bugbane.qf.modules.Packages
import org.osservatorionessuno.bugbane.qf.modules.RootBinaries
import org.osservatorionessuno.bugbane.qf.modules.Temp

private const val TAG = "QuickForensics"

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
class QuickForensics(
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

    /**
     * Listener used to report progress and check for cancellation.
     */
    interface ProgressListener {
        fun onModuleStart(name: String, completed: Int, total: Int)
        fun onModuleProgress(name: String, bytes: Long)
        fun onModuleComplete(name: String, completed: Int, total: Int)
        fun isCancelled(): Boolean
        fun onFinished(cancelled: Boolean)
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
        manager: AbsAdbConnectionManager,
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

        val shell = Shell(manager)
        val cpu = shell.exec("getprop ro.product.cpu.abi").trim()
        var tmpDir = "/data/local/tmp/"
        var sdCard = "/sdcard/"
        shell.exec("env").split('\n').forEach { line ->
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

        for (module in modules) {
            if (listener?.isCancelled() == true) {
                Log.i(TAG, "Acquisition cancelled before module ${module.name}")
                listener.onFinished(true)
                return acquisitionDir
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
                if (!acquisitionDir.exists()) acquisitionDir.mkdirs()
                module.run(context, manager, acquisitionDir, progressCb)
                Log.i(TAG, "Module ${module.name} finished")
            } catch (t: Throwable) {
                Log.e(TAG, "Module ${module.name} failed", t)
            }
            completedCount++
            listener?.onModuleComplete(module.name, completedCount, total)
        }

        val completed = Instant.now()
        val metadata = JSONObject().apply {
            put("uuid", acquisitionDir.name)
            // TODO: here we should use a better key, for exampple "bugbane_version"
            put("androidqf_version", BuildConfig.VERSION_NAME)
            put("storage_path", acquisitionDir.absolutePath)
            put("started", started.toString())
            put("completed", completed.toString())
            put("tmp_dir", tmpDir)
            put("sdcard", sdCard)
            put("cpu", cpu)
            put("streaming_mode", false)
        }
        File(acquisitionDir, "acquisition.json").writeText(Utils.toJsonString(metadata))

        Log.i(TAG, "Acquisition complete in ${acquisitionDir.absolutePath}")
        listener?.onFinished(false)
        return acquisitionDir
    }
}