package org.osservatorionessuno.qf

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import android.os.Build
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
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
import org.osservatorionessuno.qf.storage.AcquisitionTransport
import org.osservatorionessuno.qf.storage.EncryptedAcquisitionWriter
import org.osservatorionessuno.qf.storage.InsufficientStorageException
import org.osservatorionessuno.qf.storage.StoredArtifact
import org.osservatorionessuno.qf.storage.ACQUISITION_FREE_SPACE_RESERVE_BYTES
import org.osservatorionessuno.qf.storage.COMMAND_LOG_FILE
import org.osservatorionessuno.qf.storage.HASHES_FILE
import org.osservatorionessuno.qf.storage.availableAcquisitionBytes
import android.content.pm.PackageManager
import java.security.MessageDigest
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.SessionKeyCache
import org.osservatorionessuno.qf.AcquisitionLog.Companion.describe

private const val TAG = "AcquisitionRunner"

/**
 * Thrown from the progress callback to abort a module the moment the user cancels.
 * Transports must let it propagate rather than retry (see AdbShell).
 */
class AcquisitionCancelledException : RuntimeException()

/** Re-throw a cancel swallowed by runCatching so a module's fallbacks don't run. */
fun <T> Result<T>.rethrowIfCancelled(): Result<T> {
    exceptionOrNull()?.let { if (it is AcquisitionCancelledException) throw it }
    return this
}

/** Package, signing certificate, installer and install time of this app, for the log. */
private fun appBuild(context: Context): String {
    val pm = context.packageManager
    val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signers = info.signingInfo?.apkContentsSigners.orEmpty().joinToString(" ") { cert ->
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val installer = runCatching { pm.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
    return "${context.packageName}, signing certificate sha256 $signers, installed by ${installer ?: "unknown"}" +
        " at ${Instant.ofEpochMilli(info.lastUpdateTime)}"
}

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

        // Verified boot, bootloader lock and patch levels.
        private val BOOT_STATE_PROPS = listOf(
            "ro.boot.verifiedbootstate", "ro.boot.flash.locked", "ro.boot.vbmeta.device_state",
            "ro.boot.veritymode", "ro.boot.vbmeta.digest", "sys.oem_unlock_allowed", "ro.boot.warranty_bit",
            "ro.build.type", "ro.build.tags", "ro.debuggable", "ro.secure", "ro.bootloader",
            "ro.build.version.security_patch", "ro.vendor.build.security_patch", "ro.build.date.utc",
        )

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
        /** What the running module is doing right now, localized. */
        fun onModuleStep(name: String, step: String) {}
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
        listener: ProgressListener? = null,
        transport: AcquisitionTransport? = null,
        knownDevice: DeviceInfo? = null,
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

        val log = AcquisitionLog(context)
        // Records every shell command and file transfer until the run ends.
        manager.commandLog = log
        log.info("Bugbane version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})")
        runCatching { log.info("Bugbane build: ${appBuild(context)}") }
            .onFailure { log.warning("Bugbane build details unavailable: ${it.describe()}") }
        log.info("Started new acquisition ${acquisitionDir.name} at $started")
        log.info("Running on: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})")
        transport?.let { log.info("Transport: ${it.type}" + (it.hotspotSsid?.let { ssid -> ", hotspot $ssid" } ?: "")) }
        log.info("Free space for the acquisition: ${availableAcquisitionBytes(acquisitionDir)} bytes, ${ACQUISITION_FREE_SPACE_RESERVE_BYTES} kept in reserve for finalizing")
        log.info("Shell commands are sent as: ${AdbShell.wrap("<command>", "<end marker>")}")

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
            runCatching { shell.execFirstLine("date +%Y-%m-%dT%H:%M:%S%z") }
                .onSuccess { log.info("Device clock: $it (this phone: ${OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)})") }
            if (knownDevice != null) log.info("Device details collected when it connected")
            val device = knownDevice ?: DeviceInfo.collect(shell)
            // Identifies the acquired phone; missing values are logged as such rather than dropped.
            val fingerprint = runCatching { shell.execFirstLine("getprop ro.build.fingerprint") }.getOrDefault("")
            val bootId = runCatching { shell.execFirstLine("cat /proc/sys/kernel/random/boot_id") }.getOrDefault("")
            fun id(value: String?) = value?.ifBlank { null } ?: "unavailable"
            fun ids(values: List<String>) = values.joinToString(" ").ifBlank { "unavailable" }
            log.info(
                "Acquired device: ${id(device.label)}, Android ${id(device.androidVersion)} (SDK ${id(device.sdk)}, patch ${id(device.securityPatch)})" +
                    ", serial ${id(device.serial)}, Android ID ${id(device.androidId)}, IMEI ${ids(device.imei)}" +
                    ", IMSI ${ids(device.imsi)}, ICCID ${ids(device.iccid)}, build ${id(fingerprint)}, boot ID ${id(bootId)}"
            )
            log.info("CPU: $cpu, TMPDIR: $tmpDir, EXTERNAL_STORAGE: $sdCard")
            runCatching {
                val state = mutableListOf<String>()
                val props = BOOT_STATE_PROPS.joinToString(" ")
                shell.execForEachLine("for p in $props; do echo \"\$p=\$(getprop \$p)\"; done") { line ->
                    val trimmed = line.trim()
                    if (trimmed.contains('=')) state += if (trimmed.endsWith('=')) "${trimmed}unset" else trimmed
                }
                // getprop values can be spoofed on a compromised device; only key attestation proves them.
                log.info("Boot state as reported by the device (not attested): ${state.joinToString(", ")}")
            }.onFailure { log.warning("Boot state unavailable: ${it.describe()}") }

            val total = modules.size
            var completedCount = 0
            val failedModules = mutableListOf<String>()
            val skippedModules = mutableListOf<String>()
            val moduleErrors = mutableMapOf<String, String>()

            val adbHostKey = runCatching { manager.hostPublicKey() }
                .onFailure {
                    log.warning("Could not encode the ADB host public key: ${it.describe()}")
                    Log.w(TAG, "Could not encode host adb public key", it)
                }
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
                device = device,
                transport = transport,
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
                log.info("Archive encrypted with age to a temporary key until a screen lock is set")
            } else {
                log.info("Archive encrypted with age to this phone's acquisition key")
            }
            var fileKey: ByteArray? = null
            var artifacts = 0
            var storedBytes = 0L
            val stored = mutableListOf<StoredArtifact>()
            // Tags artifact lines with the module writing them.
            var currentModule = "acquisition metadata"
            val writer = EncryptedAcquisitionWriter(
                acquisitionDir,
                listOf(recipient),
                onFileKey = { fileKey = it },
                onArtifactOpened = { path -> log.info("Writing artifact $path (module $currentModule)") },
                onArtifact = { path, bytes, sha256 ->
                    artifacts++
                    storedBytes += bytes
                    stored += StoredArtifact(path, bytes, sha256, currentModule)
                    if (sha256 != null) log.info("Stored artifact $path (module $currentModule): $bytes bytes, sha256 $sha256")
                    else log.error("Stored artifact $path (module $currentModule) truncated at $bytes bytes: storage reserve reached")
                },
            )

            try {
                adbHostKey?.let { key ->
                    runCatching {
                        writer.useArtifact("adb_host_key.pub") { it.write((key + "\n").toByteArray()) }
                    }.onFailure {
                        log.error("Failed to write adb_host_key.pub: ${it.describe()}")
                        Log.w(TAG, "Failed to write adb_host_key.pub", it)
                    }
                }

                for (module in modules) {
                    if (listener?.isCancelled() == true) {
                        log.warning("Acquisition cancelled by the user before module ${module.name}")
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
                        log.error("Skipping module ${module.name}: out of space (${availableAcquisitionBytes(acquisitionDir)} bytes free, reserve ${ACQUISITION_FREE_SPACE_RESERVE_BYTES})")
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
                        // A zero delta is only a cancel probe; keep the throttle untouched.
                        if (delta > 0) {
                            moduleBytes += delta
                            val now = System.nanoTime()
                            if (moduleBytes - lastReportBytes >= PROGRESS_REPORT_BYTES ||
                                now - lastReportNanos >= PROGRESS_REPORT_INTERVAL_NANOS
                            ) {
                                lastReportBytes = moduleBytes
                                lastReportNanos = now
                                listener?.onModuleProgress(module.name, moduleBytes)
                            }
                        }
                    }
                    Log.i(TAG, "Running module ${module.name}")
                    log.info("Running module ${module.name}")
                    currentModule = module.name
                    listener?.onModuleStart(module.name, completedCount, total)
                    log.onStep = { listener?.onModuleStep(module.name, it) }
                    val moduleStarted = System.nanoTime()
                    var success = true
                    try {
                        module.run(context, manager, writer, progressCb, log)
                        // Flush the throttled tail so the completed card shows the real total.
                        if (moduleBytes > lastReportBytes) {
                            listener?.onModuleProgress(module.name, moduleBytes)
                        }
                        Log.i(TAG, "Module ${module.name} finished")
                    } catch (ise: InsufficientStorageException) {
                        Log.w(TAG, "Module ${module.name} hit the storage reserve")
                    } catch (c: AcquisitionCancelledException) {
                        // Handled by the flag check below.
                    } catch (t: Throwable) {
                        success = false
                        moduleErrors[module.name] = (t.message ?: t.javaClass.simpleName).take(500)
                        log.error("Module ${module.name} failed after ${AcquisitionLog.elapsed(moduleStarted)}: ${t.describe()}")
                        Log.e(TAG, "Module ${module.name} failed", t)
                    } finally {
                        log.onStep = null
                    }
                    // The flag, not the throw, is authoritative (modules may swallow it in runCatching).
                    if (listener?.isCancelled() == true) {
                        log.warning("Acquisition cancelled by the user during module ${module.name}, after ${AcquisitionLog.elapsed(moduleStarted)}")
                        Log.i(TAG, "Acquisition cancelled during module ${module.name}")
                        cancelled = true
                        break
                    }
                    // The latched guard, not the throw, is authoritative (modules may swallow it).
                    if (writer.outOfSpace) {
                        log.error("Module ${module.name} incomplete after ${AcquisitionLog.elapsed(moduleStarted)}: out of space (${availableAcquisitionBytes(acquisitionDir)} bytes free, reserve ${ACQUISITION_FREE_SPACE_RESERVE_BYTES})")
                        Log.w(TAG, "Skipping ${module.name}: out of space")
                        skippedModules += module.name
                        listener?.onModuleSkipped(module.name)
                        continue
                    }
                    if (!success) failedModules += module.name
                    else log.info("Module ${module.name} completed in ${AcquisitionLog.elapsed(moduleStarted)}, $moduleBytes bytes received from the device")
                    completedCount++
                    listener?.onModuleComplete(module.name, completedCount, total, success)
                }

                currentModule = "acquisition metadata"
                val completed = Instant.now()
                log.info(
                    (if (cancelled) "Acquisition cancelled" else "Acquisition finished") +
                        " in ${Duration.between(started, completed).seconds} s: $completedCount of $total modules ran" +
                        (if (failedModules.isNotEmpty()) ", failed: ${failedModules.joinToString()}" else "") +
                        (if (skippedModules.isNotEmpty()) ", skipped: ${skippedModules.joinToString()}" else "") +
                        "; $artifacts artifacts, $storedBytes bytes stored"
                )
                log.info("Writing $COMMAND_LOG_FILE; its sha256 is listed in $HASHES_FILE")
                // Unlike androidqf, written before acquisition.json (the index must be the last entry).
                runCatching { writer.writeCommandLog(log.toByteArray()) }
                    .onFailure { Log.e(TAG, "Failed to write $COMMAND_LOG_FILE", it) }
                index = if (cancelled) index.markAsCancelled(completed)
                    else index.markAsFinished(completed, failedModules, skippedModules, moduleErrors)
                writer.writeIndex(index, stored)
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
            manager.commandLog = null
            Log.i(TAG, "Acquisition finished in ${acquisitionDir.absolutePath}")
            listener?.onFinished(cancelled, output)
        }
        return acquisitionDir
    }
}
