package org.osservatorionessuno.bugbane.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.SlideshowActivity
import org.osservatorionessuno.bugbane.AcquisitionActivity
import org.osservatorionessuno.bugbane.utils.AdbManager
import java.io.File

@Composable
fun ScanScreen(
    lacksPermissions: Boolean = false,
    onLacksPermissionsChange: (Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val adbManager = AdbManager(context.applicationContext)
    var isScanning by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var completedModules by remember { mutableStateOf(0) }
    var totalModules by remember { mutableStateOf(0) }
    val progressLogs = remember { mutableStateListOf<String>() }
    val moduleLogIndex = remember { mutableStateMapOf<String, Int>() }
    val moduleBytes = remember { mutableStateMapOf<String, Long>() }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Update lacksPermissions based on current permissions
    LaunchedEffect(Unit) {
        val hasPermissions = ConfigurationManager.isNotificationPermissionGranted(context) &&
                           ConfigurationManager.isWirelessDebuggingEnabled(context)
        onLacksPermissionsChange(!hasPermissions)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (isScanning) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isLandscape) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (totalModules > 0) {
                                    CircularProgressIndicator(
                                        progress = {
                                            (completedModules / totalModules.toFloat()).coerceIn(0f, 1f)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("$completedModules / $totalModules")
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp),
                        ) {
                            items(progressLogs) { log ->
                                Text(log)
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (totalModules > 0) {
                                    CircularProgressIndicator(
                                        progress = {
                                            (completedModules / totalModules.toFloat()).coerceIn(0f, 1f)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("$completedModules / $totalModules")
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            items(progressLogs) { log ->
                                Text(log)
                            }
                        }
                    }
                }
                Button(
                    onClick = { adbManager.cancelQuickForensics() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.scan_cancel_button),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Welcome content in the center
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_bugbane_zoom),
                            contentDescription = "Bugbane Logo",
                            modifier = Modifier.size(160.dp),
                            alpha = 0.4f
                        )
                        Spacer(modifier = Modifier.width(24.dp))
                        Column(modifier = Modifier.fillMaxWidth(0.5f)) {
                            Text(
                                text = stringResource(R.string.scan_welcome_title),
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.scan_welcome_description),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_bugbane_zoom),
                            contentDescription = "Bugbane Logo",
                            modifier = Modifier.size(160.dp),
                            alpha = 0.4f
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.scan_welcome_title),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.scan_welcome_description),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Disable Development Tools Dialog
                if (showDisableDialog) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.scan_disable_dialog_title),
                                modifier = Modifier.weight(1f)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        ConfigurationManager.openDeveloperOptions(context)
                                        showDisableDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(stringResource(R.string.scan_disable_dialog_button))
                                }

                                IconButton(
                                    onClick = { showDisableDialog = false }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.scan_disable_dialog_close_button),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Scan Button fixed at the bottom
                Button(
                    onClick = {
                        if (lacksPermissions) {
                            SlideshowActivity.start(context)
                            return@Button
                        }

                        if (!isScanning) {
                            val baseDir = File(context.filesDir, "acquisitions")
                            isScanning = true
                            progressLogs.clear()
                            moduleLogIndex.clear()
                            moduleBytes.clear()
                            completedModules = 0
                            totalModules = 0
                            adbManager.runQuickForensics(baseDir, object : org.osservatorionessuno.bugbane.qf.QuickForensics.ProgressListener {
                                override fun onModuleStart(name: String, completed: Int, total: Int) {
                                    coroutineScope.launch {
                                        totalModules = total
                                        moduleLogIndex[name] = progressLogs.size
                                        moduleBytes[name] = 0L
                                        progressLogs.add("Running $name: 0 B")
                                    }
                                }

                                override fun onModuleProgress(name: String, bytes: Long) {
                                    coroutineScope.launch {
                                        val idx = moduleLogIndex[name] ?: return@launch
                                        moduleBytes[name] = bytes
                                        progressLogs[idx] = "Running $name: ${formatBytes(bytes)}"
                                    }
                                }

                                override fun onModuleComplete(name: String, completed: Int, total: Int) {
                                    coroutineScope.launch {
                                        completedModules = completed
                                        val idx = moduleLogIndex[name]
                                        val finalBytes = moduleBytes[name] ?: 0L
                                        if (idx != null) {
                                            progressLogs[idx] = "Completed $name: ${formatBytes(finalBytes)}"
                                        } else {
                                            progressLogs.add("Completed $name: ${formatBytes(finalBytes)}")
                                        }
                                    }
                                }

                                override fun isCancelled(): Boolean = adbManager.isQuickForensicsCancelled()

                                override fun onFinished(cancelled: Boolean) {
                                    coroutineScope.launch {
                                        if (!cancelled) {
                                            val latest = baseDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
                                            if (latest != null) {
                                                val intent = Intent(context, AcquisitionActivity::class.java).apply {
                                                    putExtra(AcquisitionActivity.EXTRA_PATH, latest.absolutePath)
                                                }
                                                context.startActivity(intent)
                                            }
                                            showDisableDialog = true
                                        }
                                        isScanning = false
                                    }
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    enabled = !isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else if (lacksPermissions)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning)
                            stringResource(R.string.home_scanning_button)
                        else if (lacksPermissions)
                            stringResource(R.string.home_permissions_button)
                        else
                            stringResource(R.string.home_scan_button),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
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