package org.osservatorionessuno.bugbane.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.osservatorionessuno.bugbane.R
import java.text.DateFormat
import java.util.*

data class BackupItem(
    val id: Int,
    val name: String,
    val creationDate: Date
)

@Composable
fun BackupsScreen() {
    var backupItems by remember {
        mutableStateOf(
            listOf(
                BackupItem(1, "Personal Documents", Date()),
                BackupItem(2, "Work Notes", Date(System.currentTimeMillis() - 86400000)), // 1 day ago
                BackupItem(3, "Financial Data", Date(System.currentTimeMillis() - 172800000)), // 2 days ago
                BackupItem(4, "Medical Records", Date(System.currentTimeMillis() - 259200000)), // 3 days ago
                BackupItem(5, "Travel Plans", Date(System.currentTimeMillis() - 345600000)), // 4 days ago
                BackupItem(6, "Shopping List", Date(System.currentTimeMillis() - 432000000)), // 5 days ago
                BackupItem(7, "Meeting Notes", Date(System.currentTimeMillis() - 518400000)), // 6 days ago
                BackupItem(8, "Photos Backup", Date(System.currentTimeMillis() - 604800000)) // 7 days ago
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(backupItems) { _, item ->
                BackupItemRow(
                    item = item,
                    onShare = {
                        // Handle share action
                    },
                    onShowPassword = {
                        // Handle show password action
                    },
                    onDelete = {
                        backupItems = backupItems.filter { it.id != item.id }
                    }
                )
            }
        }
    }
}

@Composable
fun BackupItemRow(
    item: BackupItem,
    onShare: () -> Unit,
    onShowPassword: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Created: ${dateFormat.format(item.creationDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Box {
            IconButton(
                onClick = { expanded = true }
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backups_share))
                        }
                    },
                    onClick = {
                        onShare()
                        expanded = false
                    }
                )
                
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                                                         Icon(
                                 imageVector = Icons.Default.Lock,
                                 contentDescription = null,
                                 modifier = Modifier.size(16.dp),
                                 tint = MaterialTheme.colorScheme.onSurface
                             )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backups_show_password))
                        }
                    },
                    onClick = {
                        onShowPassword()
                        expanded = false
                    }
                )
                
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.backups_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    onClick = {
                        onDelete()
                        expanded = false
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