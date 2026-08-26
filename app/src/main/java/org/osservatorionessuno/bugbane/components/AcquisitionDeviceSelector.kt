package org.osservatorionessuno.bugbane.components

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.WirelessPairingActivity
import org.osservatorionessuno.cadb.AdbManager
import org.osservatorionessuno.cadb.AdbNetworkDiscovery
import org.osservatorionessuno.cadb.AdbTarget
import org.osservatorionessuno.cadb.RemoteProbeStatus

private data class RemoteMenuDevice(
    val host: String,
    val port: Int,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquisitionDeviceSelector(
    adbManager: AdbManager,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val connected by adbManager.connectedTarget.collectAsStateWithLifecycle()
    val knownRemotes by adbManager.knownRemotes.collectAsStateWithLifecycle()
    val probeStatus by adbManager.probeStatus.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    val discovery = remember { AdbNetworkDiscovery(context) }
    val services by discovery.services.collectAsStateWithLifecycle()

    DisposableEffect(expanded) {
        if (expanded) {
            discovery.start()
        } else {
            discovery.stop()
        }
        onDispose { discovery.stop() }
    }

    val remoteDevices = remember(services, connected, probeStatus, knownRemotes) {
        buildRemoteMenuDevices(
            services = services,
            connected = connected,
            knownRemotes = knownRemotes,
            probeStatus = probeStatus,
        )
    }

    LaunchedEffect(expanded, services) {
        if (!expanded) return@LaunchedEffect
        services
            .filter { it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT }
            .forEach { adbManager.probeRemote(it.host, it.port) }
    }

    val displayLabel = when {
        connected == null -> stringResource(R.string.scan_device_none)
        connected!!.isLocal -> stringResource(R.string.scan_device_this_device)
        else -> resolveRemoteLabel(connected!!, services, knownRemotes)
    }
    val displayAddress = connected?.let { target ->
        buildString {
            append(target.host)
            if (target.port > 0) append(':').append(target.port)
        }
    }
    val interactive = enabled && !switching

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    ExposedDropdownMenuBox(
        expanded = expanded && interactive,
        onExpandedChange = { next ->
            if (interactive) expanded = next
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        val shape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = interactive)
                .fillMaxWidth()
                .clip(shape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(
                        alpha = if (interactive) 1f else 0.4f,
                    ),
                    shape = shape,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (connected?.isLocal != false) {
                    Icons.Default.PhoneAndroid
                } else {
                    Icons.Default.Wifi
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (interactive) 1f else 0.5f,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (interactive) 1f else 0.6f,
                    ),
                )
                if (!displayAddress.isNullOrBlank()) {
                    Text(
                        text = displayAddress,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (interactive) 1f else 0.5f,
                        ),
                    )
                }
            }
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && interactive)
        }

        ExposedDropdownMenu(
            expanded = expanded && interactive,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.scan_device_this_device))
                        Text(
                            text = connected?.takeIf { it.isLocal }?.host
                                ?: stringResource(R.string.scan_device_this_device_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                },
                trailingIcon = {
                    if (connected?.isLocal == true) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onClick = {
                    expanded = false
                    if (connected?.isLocal == true) return@DropdownMenuItem
                    switching = true
                    adbManager.reconnectLocal { _, _ ->
                        runOnMain { switching = false }
                    }
                },
            )

            remoteDevices.forEach { device ->
                val isSelected = connected?.let { current ->
                    !current.isLocal && hostsMatch(current.host, device.host)
                } == true
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = device.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = device.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Wifi, contentDescription = null)
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (isSelected) return@DropdownMenuItem
                        switching = true
                        adbManager.connectRemote(
                            host = device.host,
                            port = device.port,
                            label = device.label,
                        ) { _, _ ->
                            runOnMain { switching = false }
                        }
                    },
                )
            }

            DropdownMenuItem(
                text = { Text(stringResource(R.string.scan_device_pair_new)) },
                leadingIcon = {
                    Icon(Icons.Default.PhonelinkSetup, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(context, WirelessPairingActivity::class.java))
                },
            )
        }
    }
}

private fun buildRemoteMenuDevices(
    services: List<AdbNetworkDiscovery.DiscoveredService>,
    connected: AdbTarget?,
    knownRemotes: List<AdbTarget>,
    probeStatus: Map<String, RemoteProbeStatus>,
): List<RemoteMenuDevice> {
    val byHost = linkedMapOf<String, RemoteMenuDevice>()

    fun put(host: String, port: Int, label: String) {
        val key = host.lowercase()
        val existing = byHost[key]
        val niceLabel = pickRemoteLabel(label, existing?.label, host)
        if (existing == null) {
            byHost[key] = RemoteMenuDevice(host = host, port = port, label = niceLabel)
        } else {
            byHost[key] = existing.copy(
                port = if (port > 0) port else existing.port,
                label = niceLabel,
            )
        }
    }

    knownRemotes.filter { !it.isLocal }.forEach { put(it.host, it.port, it.label) }

    services
        .filter { it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT }
        .filter { service ->
            probeStatus.entries.any { (host, status) ->
                status == RemoteProbeStatus.Ready && hostsMatch(host, service.host)
            } || knownRemotes.any { hostsMatch(it.host, service.host) }
        }
        .forEach { put(it.host, it.port, it.serviceName) }

    connected?.takeIf { !it.isLocal }?.let { put(it.host, it.port, it.label) }

    return byHost.values.sortedBy { it.label.lowercase() }
}

private fun resolveRemoteLabel(
    connected: AdbTarget,
    services: List<AdbNetworkDiscovery.DiscoveredService>,
    knownRemotes: List<AdbTarget>,
): String {
    val mdnsName = services.firstOrNull {
        it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT &&
            hostsMatch(it.host, connected.host)
    }?.serviceName
    val knownName = knownRemotes.firstOrNull { hostsMatch(it.host, connected.host) }?.label
    return pickRemoteLabel(mdnsName, knownName, connected.label, connected.host)
}

private fun pickRemoteLabel(vararg candidates: String?): String {
    val adb = candidates.firstOrNull { it != null && it.startsWith("adb-", ignoreCase = true) }
    if (adb != null) return adb
    return candidates.firstOrNull { !it.isNullOrBlank() && !isLikelyQrPairingName(it) }
        ?: candidates.firstOrNull { !it.isNullOrBlank() }
        ?: ""
}

/** QR pairing mDNS names are random alphanumerics (the QR "S" field), not adb-*. */
private fun isLikelyQrPairingName(name: String): Boolean {
    if (name.startsWith("adb-", ignoreCase = true)) return false
    if (name.contains('.') || name.contains(':') || name.contains('-')) return false
    return name.length in 8..20 && name.all { it.isLetterOrDigit() }
}

private fun hostsMatch(a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = true)) return true
    return try {
        java.net.InetAddress.getByName(a) == java.net.InetAddress.getByName(b)
    } catch (_: Exception) {
        false
    }
}
