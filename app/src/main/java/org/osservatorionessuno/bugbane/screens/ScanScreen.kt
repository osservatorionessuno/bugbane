package org.osservatorionessuno.bugbane.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.SlideshowActivity
import java.io.File

@Composable
fun ScanScreen(
    lacksPermissions: Boolean = false,
    onLacksPermissionsChange: (Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<org.osservatorionessuno.bugbane.utils.AdbViewModel>()
    var isScanning by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var completedModules by remember { mutableStateOf(0) }
    var totalModules by remember { mutableStateOf(0) }
    val progressLogs = remember { mutableStateListOf<String>() }

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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (totalModules > 0) {
                            CircularProgressIndicator(progress = completedModules / totalModules.toFloat())
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
                        .padding(16.dp)
                ) {
                    items(progressLogs) { log ->
                        Text(log)
                    }
                }
                Button(
                    onClick = { viewModel.cancelQuickForensics() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.scan_cancel_button),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        } else {
            // Disable Development Tools Dialog
            AnimatedVisibility(
                visible = showDisableDialog,
                enter = slideInVertically(
                    animationSpec = tween(400),
                    initialOffsetY = { it }
                ) + fadeIn(
                    animationSpec = tween(400)
                ) + scaleIn(
                    animationSpec = tween(400),
                    initialScale = 0.95f
                ),
                exit = slideOutVertically(
                    animationSpec = tween(300),
                    targetOffsetY = { it }
                ) + fadeOut(
                    animationSpec = tween(300)
                ) + scaleOut(
                    animationSpec = tween(300),
                    targetScale = 0.95f
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
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

            Spacer(modifier = Modifier.height(2.dp))

            // Scan Button
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
                        completedModules = 0
                        totalModules = 0
                        viewModel.runQuickForensics(baseDir, object : org.osservatorionessuno.bugbane.qf.QuickForensics.ProgressListener {
                            override fun onModuleStart(name: String, completed: Int, total: Int) {
                                coroutineScope.launch {
                                    totalModules = total
                                    progressLogs.add("Starting $name")
                                }
                            }

                            override fun onModuleComplete(name: String, completed: Int, total: Int) {
                                coroutineScope.launch {
                                    completedModules = completed
                                    progressLogs.add("Completed $name")
                                }
                            }

                            override fun isCancelled(): Boolean = viewModel.isQuickForensicsCancelled()

                            override fun onFinished(cancelled: Boolean) {
                                coroutineScope.launch {
                                    isScanning = false
                                    if (!cancelled) {
                                        showDisableDialog = true
                                    }
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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