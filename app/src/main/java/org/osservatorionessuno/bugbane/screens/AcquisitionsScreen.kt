package org.osservatorionessuno.bugbane.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.json.JSONObject
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.AcquisitionActivity

data class AcquisitionItem(
    val dir: File,
    val name: String,
    val completed: Date?
)

@Composable
fun AcquisitionsScreen() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var acquisitionItems by remember { mutableStateOf(listOf<AcquisitionItem>()) }
    var showDeleteConfirmDialog by remember { mutableStateOf<AcquisitionItem?>(null) }

    fun loadAcquisitions() {
        val baseDir = File(context.filesDir, "acquisitions")
        acquisitionItems = baseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val metaFile = File(dir, "acquisition.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val json = JSONObject(metaFile.readText())
                val completed = json.optString("completed", "null").let {
                    try { Date.from(Instant.parse(it)) } catch (e: Exception) { null }
                }
                AcquisitionItem(dir, dir.name, completed)
            } catch (_: Throwable) {
                null
            }
        }?.sortedByDescending { it.completed } ?: emptyList()
    }

    fun handleDelete(item: AcquisitionItem) {
        // Show confirmation dialog first
        showDeleteConfirmDialog = item
    }

    fun confirmDelete(item: AcquisitionItem) {
        showDeleteConfirmDialog = null
        scope.launch {
            // Finish the delete even if the user leaves the screen.
            withContext(NonCancellable + Dispatchers.IO) {
                item.dir.deleteRecursively()
            }
            loadAcquisitions()
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.acquisitions_deleted_message, item.name),
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(Unit) { loadAcquisitions() }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        if (acquisitionItems.isEmpty()) {
            // No acquisitions, place a logo and a message as placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_bugbane_zoom),
                        contentDescription = "Bugbane Logo",
                        modifier = Modifier.size(200.dp),
                        alpha = 0.4f
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.acquisitions_empty_message),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    items = acquisitionItems,
                    key = { it.dir.absolutePath }
                ) { item ->
                    AcquisitionItemRow(
                        item = item,
                        onClick = {
                            val intent = Intent(context, AcquisitionActivity::class.java).apply {
                                putExtra(AcquisitionActivity.EXTRA_PATH, item.dir.absolutePath)
                            }
                            context.startActivity(intent)
                        },
                        onRename = { newName ->
                            val invalid = newName.contains('/') || newName.contains("\\") ||
                                newName == "." || newName == ".."
                            if (invalid) {
                                Toast.makeText(
                                    context,
                                    R.string.acquisitions_rename_invalid,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val newDir = File(item.dir.parentFile, newName)
                                if (!newDir.exists() && item.dir.renameTo(newDir)) {
                                    loadAcquisitions()
                                } else {
                                    Toast.makeText(
                                        context,
                                        R.string.acquisitions_rename_failed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onDelete = {
                            handleDelete(item)
                        }
                    )
                }
            }
        }
        }
        
        // Delete confirmation dialog
        showDeleteConfirmDialog?.let { item ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = null },
                title = {
                    Text(stringResource(R.string.acquisitions_delete_confirm_title))
                },
                text = {
                    Text(stringResource(R.string.acquisitions_delete_confirm_message, item.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmDelete(item) }
                    ) {
                        Text(stringResource(R.string.acquisitions_delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmDialog = null }
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun AcquisitionItemRow(
    item: AcquisitionItem,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(item.name) }
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty() && trimmed != item.name) {
                        onRename(trimmed)
                    }
                }) {
                    Text(stringResource(R.string.acquisitions_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true
                )
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                item.completed?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Completed: ${dateFormat.format(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(180.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.acquisitions_rename)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            expanded = false
                            showRename = true
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.acquisitions_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            expanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

