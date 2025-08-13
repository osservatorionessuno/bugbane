package org.osservatorionessuno.bugbane.qf

import android.util.Log
import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.IOException
import java.util.UUID
import org.osservatorionessuno.bugbane.qf.modules.Dumpsys
import org.osservatorionessuno.bugbane.qf.modules.Logcat
import org.osservatorionessuno.bugbane.qf.modules.Env
import org.osservatorionessuno.bugbane.qf.modules.GetProp
import org.osservatorionessuno.bugbane.qf.modules.Processes
import org.osservatorionessuno.bugbane.qf.modules.Services
import org.osservatorionessuno.bugbane.qf.modules.Settings

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
         Logcat(),
         GetProp(),
         Processes(),
         Services(),
         Settings()
     )
 ) {

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
        baseOutputDir: File
    ): File {
        if (!baseOutputDir.exists() && !baseOutputDir.mkdirs()) {
            throw IOException("Unable to create base output directory: $baseOutputDir")
        }

        val acquisitionDir = File(baseOutputDir, UUID.randomUUID().toString())
        if (!acquisitionDir.mkdirs()) {
            throw IOException("Unable to create acquisition directory: $acquisitionDir")
        }
        Log.i(TAG, "Starting acquisition in ${acquisitionDir.absolutePath}")

        modules.forEach { module ->
            Log.i(TAG, "Running module ${module.name}")
            try {
                module.run(context, manager, acquisitionDir)
                Log.i(TAG, "Module ${module.name} finished")
            } catch (t: Throwable) {
                Log.e(TAG, "Module ${module.name} failed", t)
            }
        }

        Log.i(TAG, "Acquisition complete in ${acquisitionDir.absolutePath}")
        return acquisitionDir
    }
}