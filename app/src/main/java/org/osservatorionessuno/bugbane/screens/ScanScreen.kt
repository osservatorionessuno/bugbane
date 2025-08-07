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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
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

data class ChecklistItem(
    val id: Int,
    val title: String,
    val isChecked: Boolean = false
)

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
    
    // Update lacksPermissions based on current permissions
    LaunchedEffect(Unit) {
        val hasPermissions = ConfigurationManager.isNotificationPermissionGranted(context) && 
                           ConfigurationManager.isWirelessDebuggingEnabled(context)
        onLacksPermissionsChange(!hasPermissions)
    }
    
    // Get string resources at composable level
    val backupTitle = stringResource(R.string.checkbox_smsbackup)
    val getpropTitle = stringResource(R.string.checkbox_getprop)
    val settingsTitle = stringResource(R.string.checkbox_settings)
    val processesTitle = stringResource(R.string.checkbox_processes)
    val servicesTitle = stringResource(R.string.checkbox_services)
    val logcatTitle = stringResource(R.string.checkbox_logcat)
    val logsTitle = stringResource(R.string.checkbox_logs)
    val dumpsysTitle = stringResource(R.string.checkbox_dumpsys)
    
    var checklistItems by remember {
        mutableStateOf(
            listOf(
                ChecklistItem(1, backupTitle),
                ChecklistItem(2, getpropTitle),
                ChecklistItem(3, settingsTitle),
                ChecklistItem(4, processesTitle),
                ChecklistItem(5, servicesTitle),
                ChecklistItem(6, logcatTitle),
                ChecklistItem(7, logsTitle),
                ChecklistItem(8, dumpsysTitle)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Card(
            modifier = Modifier.weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) { 
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(checklistItems) { index, item ->
                        ChecklistItemRow(
                            item = item
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                        //style = MaterialTheme.typography.bodyLarge.copy(
                        //    fontWeight = FontWeight.Medium
                        //),
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
                    coroutineScope.launch {
                        viewModel.execute("id")
                        
                        isScanning = true
                        // Reset all items to unchecked
                        checklistItems = checklistItems.map { it.copy(isChecked = false) }
                        
                        // Check items one by one with delay
                        checklistItems.forEachIndexed { index, item ->
                            //delay(800) // 800ms delay between each check
                            checklistItems = checklistItems.map { 
                                if (it.id == item.id) it.copy(isChecked = true) else it 
                            }
                        }
                        isScanning = false
                        
                        // Show the disable dialog after scanning is complete
                        showDisableDialog = true
                    }
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

@Composable
fun ChecklistItemRow(
    item: ChecklistItem
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isChecked,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 4.dp)
        )
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = if (item.isChecked) 
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
} 