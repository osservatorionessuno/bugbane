package org.osservatorionessuno.bugbane.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import org.osservatorionessuno.qf.AcquisitionScanner
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.libmvt.common.AlertLevel
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kage.Age
import kage.crypto.scrypt.ScryptRecipient
import org.osservatorionessuno.bugbane.utils.Utils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquisitionDetailScreen(acquisitionDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    var size by remember { mutableStateOf(0L) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var scans by remember { mutableStateOf(listOf<ScanSummary>()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    var processing by remember { mutableStateOf(ProcessingState.OFF) }
    var passphrase by remember { mutableStateOf<String?>(null) }
    var showPassDialog by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    
    // Bottom sheet state
    val screenHeight = configuration.screenHeightDp.dp
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    var selectedScan by remember { mutableStateOf<ScanSummary?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedScans by remember { mutableStateOf<Set<ScanSummary>>(emptySet()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(acquisitionDir) {
        size = Utils.calculateSize(acquisitionDir)
        val metaFile = File(acquisitionDir, "acquisition.json")
        if (metaFile.exists()) {
            try {
                meta = JSONObject(metaFile.readText())
            } catch (_: Throwable) {
            }
        }
        scans = loadScans(acquisitionDir)
        // Temporary disabled: scan only when user requests it
        //if (scans.isEmpty()) {
        //    startAnalysis()
        //}
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

    fun deleteAnalysis(scan: ScanSummary) {
        scope.launch(Dispatchers.IO) {
            scan.file.delete()
            scans = loadScans(acquisitionDir)
            size = Utils.calculateSize(acquisitionDir)
        }
    }

    fun deleteSelectedAnalyses() {
        scope.launch(Dispatchers.IO) {
            selectedScans.forEach { scan ->
                scan.file.delete()
            }
            scans = loadScans(acquisitionDir)
            size = Utils.calculateSize(acquisitionDir)
            selectedScans = emptySet()
            isSelectionMode = false
        }
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedScans = emptySet()
    }

    fun toggleScanSelection(scan: ScanSummary) {
        if (selectedScans.contains(scan)) {
            val newSet = selectedScans - scan
            // Exit selection mode if all items are unselected
            if (newSet.isEmpty()) {
                exitSelectionMode()
            } else {
                selectedScans = newSet
            }
        } else {
            selectedScans = selectedScans + scan
        }
    }

    // Handle back press to exit selection mode
    BackHandler(enabled = isSelectionMode) {
        exitSelectionMode()
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
                        stringResource(R.string.acquisition_details_name, acquisitionDir.name),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    meta?.let {
                        val completed = it.optString("completed", "null").let { s ->
                            try {
                                dateFormat.format(Date.from(Instant.parse(s)))
                            } catch (_: Exception) {
                                s
                            }
                        } ?: "-"

                        Text(
                            text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_completed, completed),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_size, Utils.formatBytes(size)),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                    Button(
                        onClick = { startAnalysis() },
                        enabled = processing == ProcessingState.OFF,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.acquisition_details_rescan))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScanList(
                        acquisitionDir = acquisitionDir,
                        scans = scans,
                        dateFormat = dateFormat,
                        processing = processing,
                        isSelectionMode = isSelectionMode,
                        selectedScans = selectedScans,
                        onScanClick = { scan ->
                            if (isSelectionMode) {
                                toggleScanSelection(scan)
                            } else {
                                selectedScan = scan
                                scope.launch {
                                    sheetState.show()
                                }
                            }
                        },
                        onScanLongClick = { scan ->
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedScans = setOf(scan)
                            }
                        },
                        onToggleSelection = { scan ->
                            toggleScanSelection(scan)
                        },
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
                    stringResource(R.string.acquisition_details_name, acquisitionDir.name),
                    style = MaterialTheme.typography.bodyLarge
                )
                meta?.let {
                    val completed = it.optString("completed", "null").let { s ->
                        try {
                            dateFormat.format(Date.from(Instant.parse(s)))
                        } catch (_: Exception) {
                            s
                        }
                    } ?: "-"

                    Text(
                        text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_completed, completed),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_size, Utils.formatBytes(size)),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
                Button(
                    onClick = { startAnalysis() },
                    enabled = processing == ProcessingState.OFF,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.acquisition_details_rescan))
                }

                ScanList(
                    acquisitionDir = acquisitionDir,
                    scans = scans,
                    dateFormat = dateFormat,
                    processing = processing,
                    isSelectionMode = isSelectionMode,
                    selectedScans = selectedScans,
                    onScanClick = { scan ->
                        if (isSelectionMode) {
                            toggleScanSelection(scan)
                        } else {
                            selectedScan = scan
                            scope.launch {
                                sheetState.show()
                            }
                        }
                    },
                    onScanLongClick = { scan ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedScans = setOf(scan)
                        }
                    },
                    onToggleSelection = { scan ->
                        toggleScanSelection(scan)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }

        // Export and Share buttons
        AnimatedVisibility(
            visible = !isSelectionMode,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { startExport() },
                    enabled = processing == ProcessingState.OFF,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.acquisition_details_export))
                }
                Button(
                    onClick = { startShare() },
                    enabled = processing == ProcessingState.OFF,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.acquisition_details_share))
                }
            }
        }

        // Selection mode delete button
        AnimatedVisibility(
            visible = isSelectionMode && selectedScans.isNotEmpty(),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.analysis_delete))
                    Text(" (${selectedScans.size})")
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
    
    // Bottom sheet for scan details
    selectedScan?.let { scan ->
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch {
                    sheetState.hide()
                }.invokeOnCompletion {
                    selectedScan = null
                }
            },
            sheetState = sheetState,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ) {}
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val initialHeight = screenHeight * 0.9f
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = initialHeight, max = screenHeight)
            ) {
                ScanDetailScreen(
                    acquisitionDir = acquisitionDir,
                    scanFile = scan.file,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirmDialog && selectedScans.isNotEmpty()) {
        val count = selectedScans.size
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmDialog = false
            },
            title = {
                Text(stringResource(R.string.analysis_delete_confirm_title))
            },
            text = {
                Text(
                    if (count == 1) {
                        stringResource(R.string.analysis_delete_confirm_message)
                    } else {
                        context.getString(R.string.analysis_delete_confirm_message) + " ($count analyses)"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        deleteSelectedAnalyses()
                    }
                ) {
                    Text(stringResource(R.string.analysis_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
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
    isSelectionMode: Boolean,
    selectedScans: Set<ScanSummary>,
    onScanClick: (ScanSummary) -> Unit,
    onScanLongClick: (ScanSummary) -> Unit,
    onToggleSelection: (ScanSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 2.dp, top = 4.dp, end = 2.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_date),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_indicators),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_analyses_level),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(scans) { scan ->
            val isSelected = selectedScans.contains(scan)
            val scale = animateFloatAsState(
                targetValue = if (isSelected && isSelectionMode) 1.02f else 1f,
                animationSpec = tween(300),
                label = "scale"
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale.value)
                    .combinedClickable(
                        enabled = processing == ProcessingState.OFF,
                        onClick = {
                            if (processing != ProcessingState.OFF) return@combinedClickable
                            onScanClick(scan)
                        },
                        onLongClick = {
                            if (processing != ProcessingState.OFF) return@combinedClickable
                            onScanLongClick(scan)
                        }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected && isSelectionMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (isSelected && isSelectionMode) 6.dp else 2.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dateFormat.format(Date.from(scan.started)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        scan.indicatorsHash.take(8),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        scan.level.toString(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
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
    val level: AlertLevel,
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
                Utils.sha256(hashes.joinToString(""))
            }
            val resultsArray = obj.optJSONArray("results")
            val level = if (resultsArray != null) {
                var highestLevel = AlertLevel.LOG
                for (i in 0 until resultsArray.length()) {
                    val resultObj = resultsArray.getJSONObject(i)
                    val levelStr = resultObj.optString("level", "").uppercase()
                    try {
                        val level = AlertLevel.valueOf(levelStr)
                        if (level.ordinal > highestLevel.ordinal) {
                            highestLevel = level
                        }
                    } catch (_: Exception) {
                        // Ignore unknown levels
                    }
                }
                highestLevel
            } else {
                AlertLevel.LOG
            }
            ScanSummary(file, started, hash, level)
        } catch (_: Exception) {
            null
        }
    }?.sortedByDescending { it.started } ?: emptyList()
}

private suspend fun createEncryptedArchive(context: Context, sourceDir: File): Pair<File, String> {
    return withContext(Dispatchers.IO) {
        val pass = Utils.generatePassphrase()
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

enum class ProcessingState {
    OFF,
    EXPORTING,
    SCANNING,
    SHARING,
}
