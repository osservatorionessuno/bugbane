package org.osservatorionessuno.bugbane.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osservatorionessuno.libmvt.common.IndicatorsUpdates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    val updates = remember { IndicatorsUpdates(context.filesDir.toPath(), null) }
    var lastUpdate by remember { mutableStateOf<Long?>(null) }
    var lastFetch by remember { mutableStateOf<Long?>(null) }
    var indicatorCount by remember { mutableStateOf<Long?>(null) }

    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    fun formatEpoch(epoch: Long?): String =
        if (epoch == null || epoch == 0L) "N/A" else formatter.format(Instant.ofEpochSecond(epoch))

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            lastUpdate = updates.latestUpdate
            lastFetch = updates.latestCheck
            indicatorCount = updates.countIndicators()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.settings_indicators_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.settings_indicators_last_fetch, formatEpoch(lastFetch)))
                Text(text = stringResource(R.string.settings_indicators_last_update, formatEpoch(lastUpdate)))
                Text(text = stringResource(R.string.settings_indicators_count, (indicatorCount?.toInt() ?: 0)))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                
                // Reset Slideshow Button
                Button(
                    onClick = {
                        ConfigurationManager.openDeveloperOptions(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_developer_options),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.settings_developer_options_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
} 