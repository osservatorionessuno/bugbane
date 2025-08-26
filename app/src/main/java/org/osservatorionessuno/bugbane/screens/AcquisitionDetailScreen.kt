package org.osservatorionessuno.bugbane.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osservatorionessuno.bugbane.analysis.AcquisitionScanner
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Composable
fun AcquisitionDetailScreen(acquisitionDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var size by remember { mutableStateOf(0L) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var files by remember { mutableStateOf(listOf<Pair<String, Long>>()) }
    var scans by remember { mutableStateOf(listOf<ScanSummary>()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    var processing by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf<String?>(null) }
    var showPassDialog by remember { mutableStateOf(false) }
    var showFilesDialog by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(acquisitionDir) {
        size = calculateSize(acquisitionDir)
        files = acquisitionDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(acquisitionDir).path to it.length() }
            .toList()
        val metaFile = File(acquisitionDir, "acquisition.json")
        if (metaFile.exists()) {
            try {
                meta = JSONObject(metaFile.readText())
            } catch (_: Throwable) {
            }
        }
        scans = loadScans(acquisitionDir)
        if (scans.isEmpty()) {
            processing = true
            withContext(Dispatchers.IO) {
                AcquisitionScanner.scan(context, acquisitionDir)
            }
            scans = loadScans(acquisitionDir)
            processing = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && generatedFile != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(generatedFile!!).use { it.copyTo(out) }
                }
            }
            showPassDialog = true
        }
    }

    fun startExport() {
        scope.launch {
            processing = true
            val (file, pass) = createEncryptedArchive(context, acquisitionDir)
            generatedFile = file
            passphrase = pass
            processing = false
            exportLauncher.launch(file.name)
        }
    }

    fun startShare() {
        scope.launch {
            processing = true
            val (file, pass) = createEncryptedArchive(context, acquisitionDir)
            processing = false
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(org.osservatorionessuno.bugbane.R.string.acquisition_share_message, pass)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    fun startRescan() {
        scope.launch {
            processing = true
            withContext(Dispatchers.IO) {
                AcquisitionScanner.scan(context, acquisitionDir)
            }
            scans = loadScans(acquisitionDir)
            processing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_size, formatBytes(size)),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
            meta?.let {
                val started = it.optString("started", null)?.let { s ->
                    try {
                        dateFormat.format(Date.from(Instant.parse(s)))
                    } catch (_: Exception) {
                        s
                    }
                } ?: "-"
                val completed = it.optString("completed", null)?.let { s ->
                    try {
                        dateFormat.format(Date.from(Instant.parse(s)))
                    } catch (_: Exception) {
                        s
                    }
                } ?: "-"

                Text(
                    text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_uuid, it.optString("uuid")),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_started, started),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_completed, completed),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Button(onClick = { showFilesDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_view_files))
            }
            Text(
                text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_scans),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_scans_date),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_scans_indicators),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_scans_matches),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    items(scans) { scan ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                dateFormat.format(Date.from(scan.started)),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                scan.indicatorsHash.take(8),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                scan.matchCount.toString(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Button(onClick = { startExport() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_export))
            }
            Button(onClick = { startShare() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_share))
            }
            Button(onClick = { startRescan() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_rescan))
            }
        }

        if (processing) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    if (showFilesDialog) {
        AlertDialog(
            onDismissRequest = { showFilesDialog = false },
            confirmButton = {
                TextButton(onClick = { showFilesDialog = false }) {
                    Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_passphrase_close))
                }
            },
            title = { Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_files)) },
            text = {
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn {
                        items(files) { (name, fsize) ->
                            Text("$name - ${formatBytes(fsize)}")
                        }
                    }
                }
            }
        )
    }

    if (showPassDialog && passphrase != null) {
        AlertDialog(
            onDismissRequest = { showPassDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("passphrase", passphrase))
                }) {
                    Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_passphrase_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPassDialog = false }) {
                    Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_passphrase_close))
                }
            },
            text = {
                Text(
                    stringResource(
                        org.osservatorionessuno.bugbane.R.string.acquisition_export_passphrase,
                        passphrase!!
                    )
                )
            }
        )
    }
}

private data class ScanSummary(
    val started: Instant,
    val indicatorsHash: String,
    val matchCount: Int,
)

private fun loadScans(acquisitionDir: File): List<ScanSummary> {
    val analysisDir = File(acquisitionDir, "analysis")
    if (!analysisDir.exists()) return emptyList()
    return analysisDir.listFiles { f -> f.isFile && f.extension == "json" }?.mapNotNull { file ->
        try {
            val obj = JSONObject(file.readText())
            val started = Instant.parse(obj.optString("started"))
            val hash = obj.optString("indicatorsHash").ifEmpty {
                val arr = obj.optJSONArray("indicators")
                val hashes = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        hashes += arr.getJSONObject(i).optString("sha256")
                    }
                }
                hashes.sort()
                sha256(hashes.joinToString(""))
            }
            val results = obj.optJSONArray("results")?.length() ?: 0
            ScanSummary(started, hash, results)
        } catch (_: Exception) {
            null
        }
    }?.sortedByDescending { it.started } ?: emptyList()
}

private fun sha256(data: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data.toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun calculateSize(file: File): Long {
    return if (file.isFile) file.length() else file.listFiles()?.sumOf { calculateSize(it) } ?: 0L
}

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return String.format("%.1f %s", value, units[idx])
}

private suspend fun createEncryptedArchive(context: Context, sourceDir: File): Pair<File, String> {
    return withContext(Dispatchers.IO) {
        val pass = generatePassphrase()
        val dest = File.createTempFile("acquisition", ".zip.enc", context.cacheDir)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pass.toCharArray(), salt, 100_000, 256)
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        FileOutputStream(dest).use { fileOut ->
            fileOut.write(salt)
            fileOut.write(iv)
            CipherOutputStream(fileOut, cipher).use { cipherOut ->
                ZipOutputStream(cipherOut).use { zipOut ->
                    sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val entryName = file.relativeTo(sourceDir).path
                        val entry = ZipEntry(entryName).apply { time = file.lastModified() }
                        zipOut.putNextEntry(entry)
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
        }
        dest to pass
    }
}

private fun generatePassphrase(): String {
    val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val rnd = SecureRandom()
    return (1..32).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

