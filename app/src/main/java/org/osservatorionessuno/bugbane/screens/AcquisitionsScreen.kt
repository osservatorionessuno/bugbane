package org.osservatorionessuno.bugbane.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.json.JSONObject
import org.osservatorionessuno.bugbane.R

data class AcquisitionItem(
    val dir: File,
    val name: String,
    val completed: Date?
)

@Composable
fun AcquisitionsScreen() {
    val context = LocalContext.current
    var acquisitionItems by remember { mutableStateOf(listOf<AcquisitionItem>()) }

    fun loadAcquisitions() {
        val baseDir = File(context.filesDir, "acquisitions")
        acquisitionItems = baseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val metaFile = File(dir, "acquisition.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val json = JSONObject(metaFile.readText())
                val completed = json.optString("completed", null)?.let {
                    try { Date.from(Instant.parse(it)) } catch (e: Exception) { null }
                }
                AcquisitionItem(dir, dir.name, completed)
            } catch (_: Throwable) {
                null
            }
        }?.sortedByDescending { it.completed } ?: emptyList()
    }

    LaunchedEffect(Unit) { loadAcquisitions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(acquisitionItems) { item ->
                AcquisitionItemRow(
                    item = item,
                    onRename = { newName ->
                        val newDir = File(item.dir.parentFile, newName)
                        if (!newDir.exists() && item.dir.renameTo(newDir)) {
                            val meta = File(newDir, "acquisition.json")
                            try {
                                val json = JSONObject(meta.readText())
                                json.put("uuid", newName)
                                meta.writeText(json.toString(1))
                            } catch (_: Throwable) { }
                            loadAcquisitions()
                        }
                    },
                    onDelete = {
                        item.dir.deleteRecursively()
                        loadAcquisitions()
                    }
                )
            }
        }
    }
}

@Composable
fun AcquisitionItemRow(
    item: AcquisitionItem,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
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

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.acquisitions_rename))
                        }
                    },
                    onClick = {
                        expanded = false
                        showRename = true
                    }
                )

                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.acquisitions_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        thickness = 1.dp
    )
}

