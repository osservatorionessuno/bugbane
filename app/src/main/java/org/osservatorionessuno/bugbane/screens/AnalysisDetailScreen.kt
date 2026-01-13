package org.osservatorionessuno.bugbane.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.libmvt.common.AlertLevel

@Composable
fun ScanDetailScreen(
    acquisitionDir: File, 
    scanFile: File,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val uriHandler = LocalUriHandler.current
    val supportUrl = stringResource(R.string.spyware_support_url)
    var acquisitionMeta by remember { mutableStateOf<JSONObject?>(null) }
    var scanMeta by remember { mutableStateOf<JSONObject?>(null) }
    var results by remember { mutableStateOf(listOf<ScanResult>()) }

    LaunchedEffect(acquisitionDir, scanFile) {
        val metaFile = File(acquisitionDir, "acquisition.json")
        if (metaFile.exists()) {
            try { acquisitionMeta = JSONObject(metaFile.readText()) } catch (_: Throwable) {}
        }
        try {
            val obj = JSONObject(scanFile.readText())
            scanMeta = obj
            val arr = obj.optJSONArray("results")
            val tmp = mutableListOf<ScanResult>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tmp += ScanResult(
                        o.optString("level"),
                        o.optString("title"),
                        o.optString("context")
                    )
                }
            }
            results = tmp
        } catch (_: Exception) {
        }
    }

    val hasCritical = results.any {
        try {
            AlertLevel.valueOf(it.level.uppercase()) == AlertLevel.CRITICAL
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            acquisitionMeta?.let {
                val completed = it.optString("completed").let { s ->
                    try { dateFormat.format(Date.from(Instant.parse(s))) } catch (_: Exception) { s }
                }
                Text(
                    stringResource(R.string.analysis_details_acquisition_completed, completed),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            scanMeta?.let {
                val completed = it.optString("completed").let { s ->
                    try { dateFormat.format(Date.from(Instant.parse(s))) } catch (_: Exception) { s }
                }
                Text(
                    stringResource(R.string.analysis_details_analysis_completed, completed),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        if (hasCritical) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.analysis_spyware_warning_title),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            uriHandler.openUri(supportUrl)
                        }
                    ) {
                        Text(stringResource(R.string.analysis_spyware_warning_button))
                    }
                }
            }
        }

        Divider()
        
        ResultList(
            results = results
                .filter { 
                    try {
                        AlertLevel.valueOf(it.level.uppercase()) != AlertLevel.LOG
                    } catch (_: IllegalArgumentException) {
                        true // Keep unknown levels
                    }
                }
                .sortedBy { 
                    try {
                        AlertLevel.valueOf(it.level.uppercase()).level
                    } catch (_: IllegalArgumentException) {
                        Int.MAX_VALUE // Put unknown levels at the end
                    }
                },
            modifier = Modifier.weight(1f)
        )
    }
}

data class ScanResult(
    val level: String,
    val title: String,
    val context: String,
)

@Composable
private fun ResultList(
    results: List<ScanResult>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(results) { result ->
            ExpandableResultItem(result = result)
        }
    }
}

@Composable
private fun ExpandableResultItem(result: ScanResult) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val alertLevel = try {
                        AlertLevel.valueOf(result.level.uppercase())
                    } catch (_: IllegalArgumentException) {
                        AlertLevel.INFO
                    }
                    Icon(
                        imageVector = alertLevel.icon,
                        contentDescription = result.level,
                        tint = alertLevel.getColor(),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = result.level,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = alertLevel.getColor()
                    )
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(24.dp)
                )
            }
            
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Divider(modifier = Modifier.padding(bottom = 12.dp))
                    Text(
                        text = result.context,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val AlertLevel.icon: ImageVector
    get() = when (this) {
        AlertLevel.CRITICAL -> Icons.Default.Error
        AlertLevel.HIGH -> Icons.Default.Warning
        AlertLevel.MEDIUM -> Icons.Default.Info
        AlertLevel.LOW -> Icons.Default.Info
        AlertLevel.INFO -> Icons.Default.Info
        AlertLevel.LOG -> Icons.Default.Description
    }

@Composable
private fun AlertLevel.getColor(): Color {
    return when (this) {
        AlertLevel.CRITICAL -> MaterialTheme.colorScheme.error
        AlertLevel.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        AlertLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
        AlertLevel.LOW -> MaterialTheme.colorScheme.primary
        AlertLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        AlertLevel.LOG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
