package org.osservatorionessuno.bugbane.screens

import android.app.Activity
import android.app.Application
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.SlideshowActivity
import org.osservatorionessuno.bugbane.AcquisitionActivity
import org.osservatorionessuno.bugbane.components.LayeredProgressIndicator
import org.osservatorionessuno.bugbane.INTENT_EXIT_BACKPRESS
import org.osservatorionessuno.cadb.AdbState
import org.osservatorionessuno.bugbane.utils.AcquisitionProgressTracker
import org.osservatorionessuno.bugbane.utils.AppState
import org.osservatorionessuno.bugbane.utils.ViewModelFactory
import org.osservatorionessuno.bugbane.utils.Utils
import java.io.File

private const val TAG = "ScanScreen"
private const val BETA_COUNTDOWN_SECONDS = 10

@Composable
fun ScanScreen() {
    val context = LocalContext.current

    val application = LocalContext.current.applicationContext as Application
    val viewModel = remember { ViewModelFactory.get(application) }

    val appState = viewModel.configurationState.collectAsStateWithLifecycle()
    val adbManager = viewModel.adbManager
    val adbState = adbManager.adbState.collectAsStateWithLifecycle()

    // Progress lives in an application-scoped tracker so it survives this
    // screen (or the whole activity) being recreated mid-acquisition.
    val modules = AcquisitionProgressTracker.modules.collectAsStateWithLifecycle()
    val completedModules = AcquisitionProgressTracker.completedModules.collectAsStateWithLifecycle()
    val totalModules = AcquisitionProgressTracker.totalModules.collectAsStateWithLifecycle()
    val pendingAcquisition = AcquisitionProgressTracker.pendingAcquisition.collectAsStateWithLifecycle()
    val showDisableDialog = AcquisitionProgressTracker.showDisableReminder.collectAsStateWithLifecycle()

    val isBetaVersion = context.packageName.contains("beta", ignoreCase = true)
    var showBetaWarningDialog by remember { mutableStateOf(false) }
    var betaCountdown by remember { mutableStateOf(BETA_COUNTDOWN_SECONDS) }
    var betaButtonEnabled by remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun startAcquisition() {
        AcquisitionProgressTracker.start(context, adbManager, File(context.filesDir, "acquisitions"))
    }

    // Navigate to a freshly finished acquisition. This also fires when the
    // screen is recreated (or the app is reopened from the completion
    // notification) while a finished acquisition is still pending.
    LaunchedEffect(pendingAcquisition.value) {
        val pending = pendingAcquisition.value ?: return@LaunchedEffect
        AcquisitionProgressTracker.consumePendingAcquisition(context)
        val intent = Intent(context, AcquisitionActivity::class.java).apply {
            putExtra(AcquisitionActivity.EXTRA_PATH, pending.absolutePath)
        }
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (adbState.value == AdbState.ConnectedAcquiring || adbState.value == AdbState.Cancelling) {
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
                                LayeredProgressIndicator(
                                    totalModules = totalModules.value,
                                    completedModules = completedModules.value,
                                    size = 128.dp,
                                    strokeWidth = 8.dp
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp),
                        ) {
                            items(modules.value) { module ->
                                Text(
                                    stringResource(
                                        if (module.done) R.string.scan_module_completed else R.string.scan_module_running,
                                        module.name,
                                        Utils.formatBytes(module.bytes)
                                    )
                                )
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
                                LayeredProgressIndicator(
                                    totalModules = totalModules.value,
                                    completedModules = completedModules.value,
                                    size = 128.dp,
                                    strokeWidth = 8.dp
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            items(modules.value) { module ->
                                Text(
                                    stringResource(
                                        if (module.done) R.string.scan_module_completed else R.string.scan_module_running,
                                        module.name,
                                        Utils.formatBytes(module.bytes)
                                    )
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = { 
                        if (adbState.value != AdbState.Cancelling) {
                            adbManager.cancelQuickForensics()
                        }
                    },
                    enabled = adbState.value != AdbState.Cancelling,
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
                        text = if (adbState.value == AdbState.Cancelling) stringResource(R.string.scan_cancelling_button) else stringResource(R.string.scan_cancel_button),
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
                            contentDescription = stringResource(R.string.app_name),
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
                            contentDescription = stringResource(R.string.app_name),
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
                if (showDisableDialog.value) {
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
                                        AcquisitionProgressTracker.dismissDisableReminder()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(stringResource(R.string.scan_disable_dialog_button))
                                }

                                IconButton(
                                    onClick = { AcquisitionProgressTracker.dismissDisableReminder() }
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
                        when (appState.value) {
                            AppState.AdbConnected -> {
                                if (isBetaVersion) {
                                    showBetaWarningDialog = true
                                    return@Button
                                }
                                startAcquisition()
                            }
                            AppState.AdbConnecting, AppState.TryAutoConnect -> {
                                // No-op and button is disabled below
                            }
                            else -> {
                                // Restart the slideshow, but leave the option to return to this activity
                                val intent = Intent(context, SlideshowActivity::class.java)
                                intent.putExtra(INTENT_EXIT_BACKPRESS, false)
                                context.startActivity(intent)
                                return@Button
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    enabled = (appState.value !in arrayOf(AppState.AdbScanning, AppState.TryAutoConnect, AppState.AdbConnecting)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (appState.value) {
                            AppState.AdbScanning, AppState.AdbConnecting, AppState.TryAutoConnect -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            AppState.AdbConnected -> MaterialTheme.colorScheme.secondary
                            else ->
                                MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (appState.value) {
                            AppState.AdbScanning -> stringResource(R.string.home_scanning_button)
                            AppState.AdbConnected -> stringResource(R.string.home_scan_button)
                            AppState.TryAutoConnect, AppState.AdbConnecting -> stringResource(R.string.button_working_adb_pairing)
                            else
                                -> stringResource(R.string.home_permissions_button)
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
        
        // Beta warning dialog
        if (showBetaWarningDialog) {
            LaunchedEffect(showBetaWarningDialog) {
                if (showBetaWarningDialog) {
                    betaCountdown = BETA_COUNTDOWN_SECONDS
                    betaButtonEnabled = false
                    for (i in betaCountdown.toInt() downTo 1) {
                        delay(1000)
                        betaCountdown = i - 1
                    }
                    betaButtonEnabled = true
                }
            }

            AlertDialog(
                onDismissRequest = { showBetaWarningDialog = false },
                title = {
                    Text(stringResource(R.string.beta_warning_title))
                },
                text = {
                    Text(stringResource(R.string.beta_warning_message))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBetaWarningDialog = false
                            startAcquisition()
                        },
                        enabled = betaButtonEnabled
                    ) {
                        Text(if (betaButtonEnabled) stringResource(R.string.beta_warning_understand) else "$betaCountdown")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBetaWarningDialog = false
                            // Close the application
                            (context as? Activity)?.finishAffinity()
                            System.exit(0);
                        }
                    ) {
                        Text(stringResource(R.string.beta_warning_quit))
                    }
                }
            )
        }
    }
}