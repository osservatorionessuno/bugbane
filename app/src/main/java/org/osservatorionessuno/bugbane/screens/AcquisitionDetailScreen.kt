package org.osservatorionessuno.bugbane.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date

@Composable
fun AcquisitionDetailScreen(acquisitionDir: File) {
    var size by remember { mutableStateOf(0L) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var files by remember { mutableStateOf(listOf<Pair<String, Long>>()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(acquisitionDir) {
        size = calculateSize(acquisitionDir)
        files = acquisitionDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(acquisitionDir).path to it.length() }
            .toList()
        val metaFile = File(acquisitionDir, "acquisition.json")
        if (metaFile.exists()) {
            try {
                meta = JSONObject(metaFile.readText())
            } catch (_: Throwable) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_size, formatBytes(size)),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
        meta?.let {
            val started = it.optString("started", null)?.let { s ->
                try {
                    dateFormat.format(Date.from(Instant.parse(s)))
                } catch (_: Exception) {
                    s
                }
            } ?: "-"
            val completed = it.optString("completed", null)?.let { s ->
                try {
                    dateFormat.format(Date.from(Instant.parse(s)))
                } catch (_: Exception) {
                    s
                }
            } ?: "-"

            Text(
                text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_uuid, it.optString("uuid")),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_started, started),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_completed, completed),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Text(
            text = stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_files),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(files) { (name, fsize) ->
                    Text("$name - ${formatBytes(fsize)}")
                }
            }
        }

        Button(onClick = { /* TODO export */ }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_export))
        }
        Button(onClick = { /* TODO share */ }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_share))
        }
        Button(onClick = { /* TODO scan again */ }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(org.osservatorionessuno.bugbane.R.string.acquisition_details_rescan))
        }
    }
}

private fun calculateSize(file: File): Long {
    return if (file.isFile) file.length() else file.listFiles()?.sumOf { calculateSize(it) } ?: 0L
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

