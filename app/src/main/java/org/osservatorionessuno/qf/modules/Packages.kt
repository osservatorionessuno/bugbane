package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.libmvt.android.parsers.APKParser
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser
import org.osservatorionessuno.libmvt.android.parsers.RemoteAPKParser
import org.osservatorionessuno.libmvt.common.Utils
import org.osservatorionessuno.qf.ArtifactJson
import org.osservatorionessuno.qf.rethrowIfCancelled
import org.osservatorionessuno.qf.storage.ArtifactSink
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Collects the list of installed packages using `pm list packages`.
 * In the future we wanna gather all the APK for the installed packages.
 */
class Packages : Module {
    override val name: String = "packages"
    private val TAG = "PackagesModule"

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val HEX = Regex("[0-9a-f]+")
        // Packages per batched `pm path` exec, to bound single-command output and runtime.
        private const val PM_PATH_BATCH = 50
        private val HASH_ALGORITHMS = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")
        /**
         * When true, always parse via [RemoteAPKParser] even if the APK is readable locally.
         * Debug only — leave false in production.
         */
        const val FORCE_REMOTE_APK_PARSER = false
    }

    // Data class to hold package info
    data class Package(
        val name: String,
        val files: List<PackageFile> = emptyList(),
        val installer: String = "",
        val uid: Int = -1,
        var disabled: Boolean = false,
        var system: Boolean = false,
        var thirdParty: Boolean = false,
    )

    data class PackageFile(
        val path: String,
        var localName: String,
        var md5: String,
        var sha1: String,
        var sha256: String,
        var sha512: String,
        var suspicious: Boolean,
        var certificates: List<CertificateParser.CertificateInfo>,
        var infiles: List<String>,
    )

    private data class FileHashes(
        val md5: String,
        val sha1: String,
        val sha256: String,
        val sha512: String,
    )

    // Helper function to parse package info line
    fun parsePackageLine(fields: List<String>, withInstaller: Boolean): Triple<String, String, Int> {
        val packageName = fields.getOrNull(0)?.trim()?.removePrefix("package:") ?: ""
        var installer = ""
        var uid = -1
        try {
            if (withInstaller && fields.size >= 3) {
                installer = fields[1].trim().removePrefix("installer=")
                uid = fields[2].trim().removePrefix("uid:").toIntOrNull() ?: -1
            } else if (!withInstaller && fields.size >= 2) {
                uid = fields[1].trim().removePrefix("uid:").toIntOrNull() ?: -1
            }
        } catch (_: Throwable) {}
        return Triple(packageName, installer, uid)
    }

    fun getLocalFileName(writer: ArtifactSink, packageName: String, filePath: String): String {
        val fileName = APKParser.extractFileName(filePath)
        var counter = 0

        var localPath = "${packageName}${fileName}.apk"
        while (writer.artifactExists("apks/$localPath")) {
            counter++
            localPath = "${packageName}${fileName}_$counter.apk"
        }
        return "apks/$localPath"
    }

    /**
     * Resolve APK paths for [packageNames] with batched `pm path` loops instead of
     * one exec per package.
     */
    private fun collectPackagePaths(shell: AdbShell, packageNames: List<String>): Map<String, List<String>> {
        val paths = HashMap<String, LinkedHashSet<String>>()
        for (batch in packageNames.chunked(PM_PATH_BATCH)) {
            val names = batch.joinToString(" ") { "'$it'" }
            var current: LinkedHashSet<String>? = null
            runCatching {
                // Sizes ride along (SIZE:<bytes> <path>), to decide pull versus remote parse.
                shell.execForEachLine("for p in $names; do echo \"PKG:\$p\"; pm path \"\$p\" | while read l; do echo \"\$l\"; stat -c 'SIZE:%s %n' \"\${l#package:}\" 2>/dev/null; done; done") { line ->
                    val trimmed = line.trim()
                    when {
                        // exec retries re-stream the same lines: the per-package set dedups paths
                        trimmed.startsWith("PKG:") ->
                            current = paths.getOrPut(trimmed.removePrefix("PKG:")) { LinkedHashSet() }
                        trimmed.startsWith("package:") ->
                            current?.add(trimmed.removePrefix("package:"))
                        trimmed.startsWith("SIZE:") -> {
                            val (size, path) = trimmed.removePrefix("SIZE:").split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                            size.toLongOrNull()?.let { apkSizes[path] = it }
                        }
                    }
                }
            }.rethrowIfCancelled().onFailure { Log.w(TAG, "pm path batch failed: ${it.message}") }
            // A batch that died (the target busy after bugreportz, a stalled link) must not
            // silently drop its packages: resolve the missing ones one by one.
            for (name in batch) {
                if (name in paths) continue
                runCatching {
                    shell.execForEachLine("pm path ${Utils.shQuote(name)}") { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("package:")) paths.getOrPut(name) { LinkedHashSet() }.add(trimmed.removePrefix("package:"))
                    }
                }.rethrowIfCancelled().onFailure { Log.w(TAG, "pm path failed for $name: ${it.message}") }
            }
        }
        return paths.mapValues { it.value.toList() }
    }

    /** Hash the APK with a single local read; the app can read other packages' APKs directly. */
    private fun hashFileLocally(file: File, log: AcquisitionLog): FileHashes? {
        val digests = HASH_ALGORITHMS.map { MessageDigest.getInstance(it) }
        try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    for (digest in digests) digest.update(buf, 0, read)
                }
            }
        } catch (e: Exception) {
            log.warning("Local hashing failed for ${file.path}, falling back to the device: ${e.message}")
            return null
        }
        val (md5, sha1, sha256, sha512) = digests.map { digest ->
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        return FileHashes(md5, sha1, sha256, sha512)
    }

    /** Fallback: all four sums in one exec, matched to fields by digest length. */
    private fun hashFileRemotely(shell: AdbShell, path: String, log: AcquisitionLog): FileHashes {
        var md5 = ""
        var sha1 = ""
        var sha256 = ""
        var sha512 = ""
        runCatching {
            val quoted = Utils.shQuote(path)
            shell.execForEachLine("md5sum $quoted; sha1sum $quoted; sha256sum $quoted; sha512sum $quoted") { line ->
                val token = line.trim().split(WHITESPACE, 2).firstOrNull().orEmpty()
                if (token.matches(HEX)) when (token.length) {
                    32 -> md5 = token
                    40 -> sha1 = token
                    64 -> sha256 = token
                    128 -> sha512 = token
                }
            }
        }.rethrowIfCancelled().onFailure { log.warning("Remote hashing failed for $path: ${it.message}") }
        if (md5.isEmpty() && sha1.isEmpty() && sha256.isEmpty() && sha512.isEmpty()) {
            log.warning("Remote hashing produced no digests for $path")
        }
        return FileHashes(md5, sha1, sha256, sha512)
    }

    // APKs whose copy into the archive broke off; the partial file stays, the module reports it.
    private val copyFailures = mutableListOf<String>()
    private val apkSizes = HashMap<String, Long>()
    private var cacheDir: File? = null
    private var pullApkBelowBytes = 0L

    /**
     * Pull [path] into [output]. The archive streams, so a pull that stalls part-way cannot be
     * rewound; the bytes already written are intact, so the rest is fetched through the shell.
     */
    private fun pullResumable(shell: AdbShell, sync: AdbSync, path: String, output: OutputStream, log: AcquisitionLog) {
        var received = 0L
        val counted = object : OutputStream() {
            override fun write(b: Int) { output.write(b); received++ }
            override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len); received += len }
            override fun flush() = output.flush()
        }
        try {
            sync.pull(path, counted)
        } catch (e: IOException) {
            log.warning("Pull of $path stalled after $received bytes (${e.message}); fetching the rest through the shell")
            shell.execToStream("tail -c +${received + 1} ${Utils.shQuote(path)}", output)
        }
    }

    private fun buildPackageFile(
        shell: AdbShell,
        sync: AdbSync,
        writer: ArtifactSink,
        packageName: String,
        packagePath: String,
        uid: Int,
        log: AcquisitionLog,
        remoteSession: RemoteAPKParser.Session,
        remoteTarget: Boolean,
    ): PackageFile {
        // Another phone's path may exist and be readable here too (/system, /product, /apex):
        // only a self scan may ever touch the local filesystem.
        var useRemote = remoteTarget || FORCE_REMOTE_APK_PARSER
        var local = if (useRemote) null else File(packagePath).takeIf { it.canRead() }
        // Another phone's small APK: one pull, then hashed and parsed here like a local one.
        // Larger ones stay on the device and are parsed through the shell.
        var pulled: File? = null
        val size = apkSizes[packagePath]
        if (useRemote && !FORCE_REMOTE_APK_PARSER && size != null && size <= pullApkBelowBytes) {
            val tmp = File(cacheDir, "apk-" + packageName + ".apk")
            val ok = runCatching { tmp.outputStream().use { pullResumable(shell, sync, packagePath, it, log) } }
                .rethrowIfCancelled().onFailure { log.warning("Pulling $packagePath failed (${it.message}); parsing it on the device instead"); tmp.delete() }.isSuccess
            if (ok) { pulled = tmp; local = tmp; useRemote = false }
        }
        try {
        val hashes = when {
            local != null && !useRemote ->
                hashFileLocally(local, log) ?: hashFileRemotely(shell, packagePath, log)
            else -> hashFileRemotely(shell, packagePath, log)
        }
        val packageFile = PackageFile(
            path = packagePath,
            localName = "", // not set/used here
            md5 = hashes.md5,
            sha1 = hashes.sha1,
            sha256 = hashes.sha256,
            sha512 = hashes.sha512,
            suspicious = false,
            certificates = emptyList(),
            infiles = emptyList(),
        )

        // RemoteAPKParser soft-fails (empty certs) without throwing; track verified too.
        var verified = false
        val parseOk = runCatching {
            val apkInfo = if (local != null && !useRemote) {
                APKParser.parseAPK(local)
            } else {
                remoteSession.parse(packageName, packagePath, androidUserId(uid))
            }
            packageFile.suspicious = apkInfo.suspicious
            packageFile.certificates = apkInfo.certificates
            packageFile.infiles = apkInfo.files
            verified = apkInfo.verified
        }.rethrowIfCancelled().onFailure { log.warning("Failed to parse $packagePath: ${it.message}") }.isSuccess

        // Analyst path: throw or soft-fail (no verified signer), then we pull so we don't under-export.
        if (useRemote && (!parseOk || !verified)) {
            log.warning("Exporting $packagePath: " + if (parseOk) "no signer attested by the device" else "parse failed")
            packageFile.suspicious = true
        }

        if (packageFile.suspicious) {
            val archivePath = getLocalFileName(writer, packageName, packageFile.path)
            packageFile.localName = archivePath
            log.step(R.string.step_packages_copying, packagePath)
            val result = runCatching {
                writer.useArtifact(archivePath) { output ->
                    if (local != null && !useRemote) {
                        local.inputStream().use { it.copyTo(output) }
                    } else {
                        pullResumable(shell, sync, packagePath, output, log)
                    }
                }
            }
            result.rethrowIfCancelled().onFailure { copyFailures += packagePath; log.error("Failed to copy $packagePath: ${it.message}") }
        }

        return packageFile
        } finally {
            pulled?.delete()
        }
    }

    private fun adbShellAsRemote(shell: AdbShell): RemoteAPKParser.Shell =
        object : RemoteAPKParser.Shell {
            override fun execForEachLine(command: String, onLine: (String) -> Unit) =
                shell.execForEachLine(command, onLine)

            override fun execToStream(command: String, output: OutputStream) =
                shell.execToStream(command, output)
        }

    /** Android multi-user id from app uid (`pm list packages -U`). */
    private fun androidUserId(uid: Int): Int = if (uid >= 0) uid / 100_000 else 0

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        copyFailures.clear()
        apkSizes.clear()
        cacheDir = context.cacheDir
        pullApkBelowBytes = manager.pullApkBelowBytes
        val shell = AdbShell(manager, progress = progress)
        val sync = AdbSync(manager, progress = progress)

        var withInstaller = true

        val packages = mutableListOf<Package>()
        // execInternal retries re-stream the same lines into this callback, and the pm-list
        // fallbacks below can re-run after a partial first attempt: dedup by name so neither
        // can produce duplicate records in packages.json.
        val seen = HashSet<String>()

        fun addPackage(line: String) {
            if (line.isBlank()) return
            val fields = line.trim().split(WHITESPACE)
            val (packageName, installer, uid) = parsePackageLine(fields, withInstaller)
            if (packageName.isBlank() || !seen.add(packageName)) return

            packages.add(
                Package(
                    name = packageName,
                    installer = installer,
                    uid = uid,
                )
            )
        }

        // A fallback replaces whatever a partly-failed attempt collected, so every record is
        // parsed with the flags of the command that actually succeeded.
        fun restart() {
            packages.clear()
            seen.clear()
        }

        log.step(R.string.step_packages_listing)
        try {
            shell.execForEachLine("pm list packages -U -u -i") { addPackage(it) }
        } catch (_: Throwable) {
            // fallback without "-i"
            try {
                withInstaller = false
                restart()
                shell.execForEachLine("pm list packages -U -u") { addPackage(it) }
            } catch (_: Throwable) {
                // Some Samsung devices allow only packages installed by current user, as per AndroidQF
                try {
                    withInstaller = true
                    restart()
                    shell.execForEachLine("pm list packages -U -u -i --user 0") { addPackage(it) }
                } catch (e: Throwable) {
                    // Could not obtain packages; write an empty JSON array and return.
                    writer.useArtifact("packages.json") { output -> ArtifactJson.Array(output).close() }
                    return
                }
            }
        }

        // These map to field names in the Package class
        val filters = listOf(
            Pair("disabled", "-d"),
            Pair("system", "-s"),
            Pair("thirdParty", "-3")
        )
        val fieldMap = mapOf(
            "disabled" to { p: Package -> p.disabled = true },
            "system" to { p: Package -> p.system = true },
            "thirdParty" to { p: Package -> p.thirdParty = true }
        )

        val byName = packages.associateBy { it.name }
        for ((fieldName, arg) in filters) {
            val setFlag = fieldMap[fieldName]
            val markLine = { line: String ->
                val packageName = line.trim().removePrefix("package:")
                if (packageName.isNotBlank()) {
                    byName[packageName]?.let { p ->
                        setFlag?.invoke(p)
                    }
                }
            }
            // "-u" keeps each flag pass on the same package set as the enumeration above;
            // without it, a system package not installed for user 0 (e.g. the Android 16
            // private-space app) is never marked system and reads as sideloaded.
            try {
                shell.execForEachLine("pm list packages $arg -u", markLine)
            } catch (_: Throwable) {
                // Same Samsung quirk as the enumeration fallback: retry scoped to user 0.
                try {
                    shell.execForEachLine("pm list packages $arg -u --user 0", markLine)
                } catch (_: Throwable) {
                    continue
                }
            }
        }

        log.info("Found ${packages.size} installed packages")

        // System packages don't need path resolution or hashing.
        log.step(R.string.step_packages_paths)
        val pathsByPackage = collectPackagePaths(
            shell,
            packages.filter { !it.system }.map { it.name },
        )
        // One Session per acquisition: caches getPackageInfo ordinal + SDK flag width.
        val remoteSession = RemoteAPKParser.Session(adbShellAsRemote(shell))
        var checked = 0
        for (i in packages.indices) {
            val pkg = packages[i]
            val packagePaths = pathsByPackage[pkg.name] ?: continue
            log.step(R.string.step_packages_apk, pkg.name, ++checked, pathsByPackage.size)
            packages[i] = pkg.copy(
                files = packagePaths.map {
                    buildPackageFile(shell, sync, writer, pkg.name, it, pkg.uid, log, remoteSession, manager.remoteTarget)
                },
            )
        }

        writer.useArtifact("packages.json") { output ->
            ArtifactJson.Array(output).use { arr ->
                for (pkg in packages) {
                    arr.pkg(pkg)
                }
            }
        }
        if (copyFailures.isNotEmpty()) throw IOException("incomplete copy of ${copyFailures.joinToString()}")
    }
}
