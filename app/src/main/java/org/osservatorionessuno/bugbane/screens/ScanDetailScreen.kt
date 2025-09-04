package org.osservatorionessuno.bugbane.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import org.osservatorionessuno.bugbane.R

@Composable
fun ScanDetailScreen(acquisitionDir: File, scanFile: File) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var acquisitionMeta by remember { mutableStateOf<JSONObject?>(null) }
    var scanMeta by remember { mutableStateOf<JSONObject?>(null) }
    var results by remember { mutableStateOf(listOf<ScanResult>()) }
    var selected by remember { mutableStateOf<ScanResult?>(null) }

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
                        o.optString("file"),
                        o.optString("type"),
                        o.optString("ioc"),
                        o.optString("context")
                    )
                }
            }
            results = tmp
        } catch (_: Exception) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.scan_details_acquisition_name, acquisitionDir.name),
            style = MaterialTheme.typography.bodyLarge
        )
        acquisitionMeta?.let {
            val uuid = it.optString("uuid")
            val completed = it.optString("completed").let { s ->
                try { dateFormat.format(Date.from(Instant.parse(s))) } catch (_: Exception) { s }
            }
            Text(
                stringResource(R.string.scan_details_acquisition_uuid, uuid),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(R.string.scan_details_acquisition_completed, completed),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        scanMeta?.let {
            val completed = it.optString("completed").let { s ->
                try { dateFormat.format(Date.from(Instant.parse(s))) } catch (_: Exception) { s }
            }
            Text(
                stringResource(R.string.scan_details_analysis_completed, completed),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        ResultList(results = results, onSelect = { selected = it }, modifier = Modifier.weight(1f))
    }

    selected?.let { res ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.acquisition_passphrase_close))
                }
            },
            title = { Text(stringResource(R.string.scan_details_context_dialog_title)) },
            text = { Text(res.context) }
        )
    }
}

data class ScanResult(
    val file: String,
    val type: String,
    val ioc: String,
    val context: String,
)

@Composable
private fun ResultList(
    results: List<ScanResult>,
    onSelect: (ScanResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .fillMaxSize()
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.scan_details_artifact),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.scan_details_type),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.scan_details_ioc),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            items(results) { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(r) }
                ) {
                    Text(r.file, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.type, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.ioc, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
