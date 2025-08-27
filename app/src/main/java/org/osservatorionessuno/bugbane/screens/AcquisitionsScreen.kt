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
    var acquisitionItems by remember { mutableStateOf(listOf<AcquisitionItem>()) }

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

    LaunchedEffect(Unit) { loadAcquisitions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
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
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(acquisitionItems) { item ->
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
                            item.dir.deleteRecursively()
                            loadAcquisitions()
                        }
                    )
                }
            }
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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

