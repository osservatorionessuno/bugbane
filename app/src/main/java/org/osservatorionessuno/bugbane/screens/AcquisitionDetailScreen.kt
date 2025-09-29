package org.osservatorionessuno.bugbane.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osservatorionessuno.bugbane.analysis.AcquisitionScanner
import org.osservatorionessuno.bugbane.ScanDetailActivity
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
import kage.Age
import kage.crypto.scrypt.ScryptRecipient

@Composable
fun AcquisitionDetailScreen(acquisitionDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    var size by remember { mutableStateOf(0L) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var files by remember { mutableStateOf(listOf<Pair<String, Long>>()) }
    var scans by remember { mutableStateOf(listOf<ScanSummary>()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    var processing by remember { mutableStateOf(ProcessingState.OFF) }
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
            processing = ProcessingState.SCANNING
            withContext(Dispatchers.IO) {
                AcquisitionScanner.scan(context, acquisitionDir)
            }
            scans = loadScans(acquisitionDir)
            processing = ProcessingState.OFF
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
            processing = ProcessingState.EXPORTING
            val (file, pass) = createEncryptedArchive(context, acquisitionDir)
            generatedFile = file
            passphrase = pass
            processing = ProcessingState.OFF
            exportLauncher.launch(file.name)
        }
    }

    fun startShare() {
        scope.launch {
            processing = ProcessingState.SHARING
            val (file, pass) = createEncryptedArchive(context, acquisitionDir)
            processing = ProcessingState.OFF
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

    fun startAnalysis() {
        scope.launch {
            processing = ProcessingState.SCANNING
            withContext(Dispatchers.IO) {
                AcquisitionScanner.scan(context, acquisitionDir)
            }
            scans = loadScans(acquisitionDir)
            processing = ProcessingState.OFF
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_size, formatBytes(size)),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                    meta?.let {
                        val started = it.optString("started", "null").let { s ->
                            try {
                                dateFormat.format(Date.from(Instant.parse(s)))
                            } catch (_: Exception) {
                                s
                            }
                        } ?: "-"
                        val completed = it.optString("completed", "null").let { s ->
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

                    Spacer(modifier = Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showFilesDialog = true }, 
                            modifier = Modifier.weight(1f),
                            enabled = processing == ProcessingState.OFF
                        ) {
                            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_view_files))
                        }
                        Button(
                            onClick = { startAnalysis() }, 
                            modifier = Modifier.weight(1f),
                            enabled = processing == ProcessingState.OFF
                        ) {
                            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_rescan))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { startExport() }, 
                            modifier = Modifier.weight(1f),
                            enabled = processing == ProcessingState.OFF
                        ) {
                            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_export))
                        }
                        Button(
                            onClick = { startShare() }, 
                            modifier = Modifier.weight(1f),
                            enabled = processing == ProcessingState.OFF
                        ) {
                            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_share))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_analyses),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                    ScanList(
                        acquisitionDir = acquisitionDir,
                        scans = scans,
                        dateFormat = dateFormat,
                        processing = processing,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        } else {
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
                    val started = it.optString("started", "null").let { s ->
                        try {
                            dateFormat.format(Date.from(Instant.parse(s)))
                        } catch (_: Exception) {
                            s
                        }
                    } ?: "-"
                    val completed = it.optString("completed", "null").let { s ->
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

                Button(
                    onClick = { showFilesDialog = true }, 
                    modifier = Modifier.fillMaxWidth(),
                    enabled = processing == ProcessingState.OFF
                ) {
                    Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_view_files))
                }
                Button(
                    onClick = { startAnalysis() }, 
                    modifier = Modifier.fillMaxWidth(),
                    enabled = processing == ProcessingState.OFF
                ) {
                    Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_rescan))
                }
                Text(
                    text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_analyses),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
                ScanList(
                    acquisitionDir = acquisitionDir,
                    scans = scans,
                    dateFormat = dateFormat,
                    processing = processing,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startExport() }, 
                        modifier = Modifier.weight(1f),
                        enabled = processing == ProcessingState.OFF
                    ) {
                        Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_export))
                    }
                    Button(
                        onClick = { startShare() }, 
                        modifier = Modifier.weight(1f),
                        enabled = processing == ProcessingState.OFF
                    ) {
                        Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_share))
                    }
                }
            }
        }

        if (processing != ProcessingState.OFF) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    ),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = if (processing == ProcessingState.SCANNING) {
                                    stringResource(org.osservatorionessuno.bugbane.R.string.analysis_in_progress)
                                } else {
                                    stringResource(org.osservatorionessuno.bugbane.R.string.export_in_progress)
                                },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
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

@Composable
private fun ScanList(
    acquisitionDir: File,
    scans: List<ScanSummary>,
    dateFormat: DateFormat,
    processing: ProcessingState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_date),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_indicators),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_matches),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            items(scans) { scan ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (processing != ProcessingState.OFF) return@clickable
                            val intent = Intent(context, ScanDetailActivity::class.java).apply {
                                putExtra(ScanDetailActivity.EXTRA_ACQUISITION_PATH, acquisitionDir.absolutePath)
                                putExtra(ScanDetailActivity.EXTRA_SCAN_PATH, scan.file.absolutePath)
                            }
                            context.startActivity(intent)
                        }
                ) {
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
}

private data class ScanSummary(
    val file: File,
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
            ScanSummary(file, started, hash, results)
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
        val dest = File.createTempFile("acquisition", ".zip.age", context.cacheDir)
        val plainZip = File.createTempFile("acquisition", ".zip", context.cacheDir)
        // 256MB goes OOM
        val workFactor = 15
        ZipOutputStream(FileOutputStream(plainZip)).use { zipOut ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                val entry = ZipEntry(entryName).apply { time = file.lastModified() }
                zipOut.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
        FileOutputStream(dest).use { fileOut ->
            FileInputStream(plainZip).use { plainIn ->
                Age.encryptStream(listOf(ScryptRecipient(pass.toByteArray(), workFactor = workFactor)), plainIn, fileOut)
            }
        }
        plainZip.delete()
        dest to pass
    }
}

private fun generatePassphrase(): String {
    val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val rnd = SecureRandom()
    return (1..32).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

enum class ProcessingState {
    OFF,
    EXPORTING,
    SCANNING,
    SHARING,
}
