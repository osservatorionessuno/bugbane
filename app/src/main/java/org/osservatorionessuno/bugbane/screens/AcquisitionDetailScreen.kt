package org.osservatorionessuno.bugbane.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osservatorionessuno.qf.AcquisitionScanner
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.SessionKeyCache
import org.osservatorionessuno.qf.crypto.age.DestroyableAgeIdentity
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.AcquisitionIdentityLostDialog
import org.osservatorionessuno.bugbane.utils.AcquisitionRecovery
import org.osservatorionessuno.bugbane.share.AcquisitionShareProvider
import org.osservatorionessuno.bugbane.share.AcquisitionExport
import org.osservatorionessuno.bugbane.share.EXPORT_FILE_NAME
import org.osservatorionessuno.libmvt.common.AlertLevel
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.osservatorionessuno.bugbane.utils.Utils
import org.osservatorionessuno.qf.storage.ARCHIVE_FILE

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

    // Reading an acquisition unlocks the device identity, per tier: a biometric
    // prompt, the acquisition password, or both. The fresh acquisition is free
    // within the session-cache window. A non-null pendingUnlock shows the password
    // dialog and holds the resumed action (plus, on the stacked tier, the
    // already-unwrapped inner blob so retries need no further prompt).
    var pendingUnlock by remember { mutableStateOf<PendingUnlock?>(null) }
    var exportIdentity by remember { mutableStateOf<DestroyableAgeIdentity?>(null) }
    // Set when the biometric-gated key was destroyed by the screen lock being
    // removed — the acquisitions can no longer be decrypted (see the dialog below).
    var identityLost by remember { mutableStateOf(false) }
    // Guards the async unlock window: a double-tap must not launch two biometric prompts.
    var unlocking by remember { mutableStateOf(false) }

    fun withUnlockedIdentity(onUnlocked: (DestroyableAgeIdentity) -> Unit) {
        // One unlock at a time: a password dialog is up, or an async unlock is in flight.
        if (unlocking || pendingUnlock != null) return
        unlocking = true
        // Right after an acquisition its file key is still cached, so the fresh
        // archive's first actions need no unlock. The cache never holds the
        // identity, so every other acquisition keeps its full gate.
        SessionKeyCache.identityFor(acquisitionDir)?.let { onUnlocked(it); unlocking = false; return }

        fun launchBiometric(block: suspend () -> Unit) = scope.launch {
            try {
                block()
            } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
                // Screen lock was removed → the auth-gated key is gone for good.
                identityLost = true
            } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                // user dismissed or failed the system prompt — abort silently
            } catch (_: Exception) {
                Toast.makeText(context, R.string.acquisition_unlock_failed, Toast.LENGTH_LONG).show()
            } finally {
                unlocking = false
            }
        }
        val tier = AcquisitionIdentityVault.tier(context)
        when {
            tier == AcquisitionIdentityVault.Tier.STRONGBOX ||
                tier == AcquisitionIdentityVault.Tier.TEE_AUTH ->
                launchBiometric { onUnlocked(AcquisitionIdentityVault.unlockWithBiometric(context)) }

            tier != null && tier.usesPassphrase -> launchBiometric {
                // Unwrap the outer layer (a fingerprint prompt on a two-factor tier; silent
                // on password-only). If the session already has the derived key, finish
                // without a password prompt; otherwise raise the dialog with the inner blob.
                val inner = AcquisitionIdentityVault.unlockPassphraseOuter(context)
                val cached = AcquisitionIdentityVault.tryCachedPassphrase(inner)
                if (cached != null) {
                    inner.fill(0)
                    onUnlocked(cached)
                } else {
                    pendingUnlock = PendingUnlock(inner, onUnlocked)
                }
            }

            else -> {
                Toast.makeText(context, R.string.acquisition_unlock_failed, Toast.LENGTH_LONG).show()
                unlocking = false
            }
        }
    }

    // Bottom sheet state
    val screenHeight = configuration.screenHeightDp.dp
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    var selectedScan by remember { mutableStateOf<ScanSummary?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedScans by remember { mutableStateOf<Set<ScanSummary>>(emptySet()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // A fresh acquisition's first analysis starts automatically (see
    // AcquisitionProgressTracker). Mirror it here as if the analyze button
    // had been pressed, and load its results once it completes.
    val autoAnalyzing =
        org.osservatorionessuno.bugbane.utils.AcquisitionProgressTracker.analyzing.collectAsState()
    var wasAutoAnalyzing by remember { mutableStateOf(false) }
    LaunchedEffect(autoAnalyzing.value) {
        if (autoAnalyzing.value?.absolutePath == acquisitionDir.absolutePath) {
            wasAutoAnalyzing = true
            processing = ProcessingState.SCANNING
        } else if (wasAutoAnalyzing) {
            wasAutoAnalyzing = false
            scans = withContext(Dispatchers.IO) { loadScans(acquisitionDir) }
            processing = ProcessingState.OFF
        }
    }

    // Zero any secret material still held when the composition leaves (rotation, navigation).
    DisposableEffect(Unit) {
        onDispose {
            pendingUnlock?.dispose()
            exportIdentity?.destroy()
        }
    }

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
        // If the screen lock was removed, the biometric-gated key is already dead;
        // surface it on open rather than waiting for a failed unlock.
        if (AcquisitionIdentityVault.isIdentityInvalidatedByLockRemoval(context)) {
            identityLost = true
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = passphrase
        val identity = exportIdentity
        exportIdentity = null
        if (uri != null && pass != null && identity != null) {
            scope.launch(Dispatchers.IO) {
                processing = ProcessingState.EXPORTING
                try {
                    // Stream the verbatim re-wrap straight into the chosen destination
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        AcquisitionExport.writeTo(File(acquisitionDir, ARCHIVE_FILE), identity, pass, out)
                    }
                } finally {
                    identity.destroy()
                }
                processing = ProcessingState.OFF
                showPassDialog = true
            }
        } else {
            identity?.destroy()
        }
    }

    fun startExport() {
        withUnlockedIdentity { identity ->
            // Destroy any identity from a prior, unconsumed export so it's never leaked.
            exportIdentity?.destroy()
            exportIdentity = identity
            passphrase = Utils.generatePassphrase()
            exportLauncher.launch(EXPORT_FILE_NAME)
        }
    }

    fun startShare() {
        withUnlockedIdentity { identity ->
            // No upfront work: the archive is re-wrapped lazily as the share target
            // reads the streaming content URI, so nothing is staged on disk first.
            val pass = Utils.generatePassphrase()
            val uri = AcquisitionShareProvider.enqueue(
                context,
                File(acquisitionDir, ARCHIVE_FILE),
                identity,
                pass,
                EXPORT_FILE_NAME,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(org.osservatorionessuno.bugbane.R.string.acquisition_share_message, pass)
                )
                // The read grant only reliably follows a URI in ClipData; EXTRA_STREAM
                // alone isn't covered. Only the app the user picks receives the grant
                // and the EXTRA_TEXT passphrase.
                clipData = ClipData.newRawUri(EXPORT_FILE_NAME, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    fun startAnalysis() {
        withUnlockedIdentity { identity ->
            scope.launch {
                processing = ProcessingState.SCANNING
                try {
                    withContext(Dispatchers.IO) {
                        AcquisitionScanner.scan(context, acquisitionDir, identity)
                    }
                } finally {
                    identity.destroy()
                }
                scans = loadScans(acquisitionDir)
                processing = ProcessingState.OFF
            }
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

    // Passphrase tiers: ask for the acquisition password, derive the Argon2id key
    // off the main thread, and resume the gated action on success. On the stacked
    // tier the outer layer was already unwrapped by the biometric prompt, so
    // retries here need no further prompt.
    pendingUnlock?.let { pending ->
        var password by remember(pending) { mutableStateOf("") }
        var wrongPassword by remember(pending) { mutableStateOf(false) }
        var checking by remember(pending) { mutableStateOf(false) }

        fun dismiss() {
            pending.dispose()
            pendingUnlock = null
        }

        fun attempt() {
            checking = true
            scope.launch {
                val identity = AcquisitionIdentityVault.withPasswordBytes(password) {
                    AcquisitionIdentityVault.openPassphraseWithPassword(context, pending.inner, it)
                }
                checking = false
                if (identity == null) {
                    wrongPassword = true
                } else {
                    val resume = pending.onUnlocked
                    dismiss()
                    resume(identity)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!checking) dismiss() },
            title = { Text(stringResource(R.string.acquisition_unlock_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; wrongPassword = false },
                        label = { Text(stringResource(R.string.acquisition_password_label)) },
                        singleLine = true,
                        isError = wrongPassword,
                        enabled = !checking,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        // Argon2id derivation isn't instant — say what the wait is for.
                        supportingText = when {
                            wrongPassword -> {
                                { Text(stringResource(R.string.acquisition_unlock_password_wrong)) }
                            }
                            checking -> {
                                { Text(stringResource(R.string.acquisition_unlock_password_checking)) }
                            }
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { attempt() }, enabled = !checking && password.isNotEmpty()) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.acquisition_unlock_button))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dismiss() }, enabled = !checking) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // Screen lock removed → the auth-gated key is permanently invalidated and the
    // acquisitions can't be decrypted. Explain it, then reset protection: discard
    // the dead identity, drop its unreadable acquisitions, and route the user to
    // re-establish protection. Non-dismissible: the identity is dead, so there's
    // nothing to go back to.
    if (identityLost) {
        AcquisitionIdentityLostDialog(
            onReset = { AcquisitionRecovery.begin(context) },
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
                        MaterialTheme.colorScheme.surfaceVariant
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
            val level = highestLevelFromScan(obj)
            ScanSummary(file, started, hash, level)
        } catch (_: Exception) {
            null
        }
    }?.sortedByDescending { it.started } ?: emptyList()
}

private fun highestLevelFromScan(obj: JSONObject): AlertLevel {
    val arr = obj.optJSONArray("groupedResults") ?: return AlertLevel.LOG
    var highestLevel = AlertLevel.LOG
    for (i in 0 until arr.length()) {
        val levelStr = arr.getJSONObject(i).optString("level", "").uppercase()
        try {
            val level = AlertLevel.valueOf(levelStr)
            if (level.ordinal > highestLevel.ordinal) {
                highestLevel = level
            }
        } catch (_: Exception) {
            // Ignore unknown levels
        }
    }
    return highestLevel
}

enum class ProcessingState {
    OFF,
    EXPORTING,
    SCANNING,
}

/**
 * A read action waiting on the acquisition password. [inner] is the already-unwrapped
 * Argon2id blob (the outer layer was opened first — on a two-factor tier gated by the
 * screen lock, on the password-only tier a silent device-bind unwrap), so password
 * retries need no further prompt.
 */
class PendingUnlock(
    val inner: ByteArray,
    val onUnlocked: (DestroyableAgeIdentity) -> Unit,
) {
    fun dispose() = inner.fill(0)
}
