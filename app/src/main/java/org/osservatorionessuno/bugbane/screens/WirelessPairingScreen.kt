package org.osservatorionessuno.bugbane.screens

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.ViewModelFactory
import org.osservatorionessuno.cadb.AdbNetworkDiscovery
import org.osservatorionessuno.cadb.AdbQrCredentials
import org.osservatorionessuno.cadb.RemoteProbeStatus

data class WirelessDeviceRow(
    val host: String,
    val displayName: String,
    val pairing: AdbNetworkDiscovery.DiscoveredService?,
    val connect: AdbNetworkDiscovery.DiscoveredService?,
    val probe: RemoteProbeStatus?,
) {
    val canPair: Boolean get() = pairing != null
    val needsPairing: Boolean
        get() = canPair || probe == RemoteProbeStatus.PairingRequired
    val canConnect: Boolean
        get() = connect != null && probe == RemoteProbeStatus.Ready
    val isChecking: Boolean
        get() = connect != null && (probe == null || probe == RemoteProbeStatus.Checking)
}

@Composable
fun WirelessPairingScreen(
    onConnected: () -> Unit,
    onRefreshReady: ((() -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val viewModel = remember {
        ViewModelFactory.get(context.applicationContext as Application)
    }
    val adbManager = viewModel.adbManager
    val discovery = remember { AdbNetworkDiscovery(context) }
    val services by discovery.services.collectAsStateWithLifecycle()
    val isScanning by discovery.isScanning.collectAsStateWithLifecycle()
    val probeStatus by adbManager.probeStatus.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    var pairingTargetHost by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var showQrPairing by remember { mutableStateOf(false) }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun rescan() {
        if (busy || showQrPairing) return
        discovery.stop()
        discovery.start()
    }

    DisposableEffect(Unit) {
        discovery.start()
        onRefreshReady?.invoke { rescan() }
        onDispose {
            discovery.stop()
            adbManager.cancelQrPairing()
        }
    }

    val devices = remember(services, probeStatus) {
        services
            .groupBy { it.host }
            .map { (host, hostServices) ->
                val pairing = hostServices.firstOrNull {
                    it.kind == AdbNetworkDiscovery.ServiceKind.TLS_PAIRING
                }
                val connect = hostServices.firstOrNull {
                    it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT
                }
                WirelessDeviceRow(
                    host = host,
                    displayName = (connect ?: pairing)?.serviceName ?: host,
                    pairing = pairing,
                    connect = connect,
                    probe = probeStatus.entries.firstOrNull { (key, _) ->
                        key.equals(host, ignoreCase = true)
                    }?.value,
                )
            }
            .sortedWith(
                compareBy<WirelessDeviceRow> { it.canConnect }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    val pairingTarget = pairingTargetHost?.let { host ->
        devices.firstOrNull { it.host.equals(host, ignoreCase = true) }
            ?: WirelessDeviceRow(
                host = host,
                displayName = host,
                pairing = null,
                connect = null,
                probe = RemoteProbeStatus.PairingRequired,
            )
    }

    LaunchedEffect(services) {
        val connectServices = services.filter {
            it.kind == AdbNetworkDiscovery.ServiceKind.TLS_CONNECT
        }
        adbManager.clearProbesExcept(connectServices.map { it.host }.toSet())
        connectServices.forEach { service ->
            adbManager.probeRemote(service.host, service.port)
        }
    }

    fun connectTo(device: WirelessDeviceRow) {
        val connect = device.connect ?: return
        busy = true
        adbManager.connectRemote(
            device.host,
            connect.port,
            label = device.displayName,
        ) { success, error ->
            runOnMain {
                busy = false
                if (success) {
                    onConnected()
                } else {
                    val message = when {
                        error?.contains("pairing", ignoreCase = true) == true ->
                            context.getString(R.string.wireless_pairing_open_pair_ui)
                        else -> error ?: context.getString(R.string.wireless_pairing_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    adbManager.forceProbeRemote(device.host, connect.port)
                    if (error?.contains("pairing", ignoreCase = true) == true) {
                        pairingCode = ""
                        pairingTargetHost = device.host
                    }
                }
            }
        }
    }

    fun onDeviceClick(device: WirelessDeviceRow) {
        when {
            device.canConnect -> connectTo(device)
            device.needsPairing -> {
                pairingCode = ""
                pairingTargetHost = device.host
            }
        }
    }

    fun submitPairing(target: WirelessDeviceRow) {
        if (pairingCode.length != 6) return
        busy = true
        adbManager.pairAndConnectRemote(
            host = target.host,
            pairingPort = target.pairing?.port,
            pairingCode = pairingCode,
            connectPort = target.connect?.port,
            label = target.displayName,
            discovery = discovery,
        ) { success, error ->
            runOnMain {
                busy = false
                if (success) {
                    pairingTargetHost = null
                    target.connect?.let {
                        adbManager.forceProbeRemote(target.host, it.port)
                    }
                    onConnected()
                } else {
                    Toast.makeText(
                        context,
                        error ?: context.getString(R.string.wireless_pairing_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun dismissQrPairing() {
        adbManager.cancelQrPairing()
        showQrPairing = false
        busy = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isScanning || busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                    Text(
                        text = stringResource(R.string.wireless_pairing_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(devices, key = { it.host }) { device ->
                        WirelessDeviceRowItem(
                            device = device,
                            enabled = !busy && !showQrPairing &&
                                (device.canPair || !device.isChecking),
                            onClick = { onDeviceClick(device) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            if (busy && !showQrPairing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        Button(
            onClick = { showQrPairing = true },
            enabled = !busy && !showQrPairing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.QrCode,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.wireless_pairing_qr_button))
        }
    }

    if (showQrPairing) {
        QrPairingFullscreen(
            onDismiss = { dismissQrPairing() },
            onStart = { credentials ->
                busy = true
                adbManager.pairViaQr(credentials) { success, error ->
                    runOnMain {
                        busy = false
                        if (success) {
                            showQrPairing = false
                            onConnected()
                        } else if (error != "Cancelled") {
                            showQrPairing = false
                            Toast.makeText(
                                context,
                                error ?: context.getString(R.string.wireless_pairing_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            },
            onCancel = { adbManager.cancelQrPairing() },
        )
    }

    pairingTarget?.let { target ->
        val pairingPort = target.pairing?.port
        val canSubmit = !busy && pairingCode.length == 6 && pairingPort != null && pairingPort > 0
        AlertDialog(
            onDismissRequest = {
                if (!busy) pairingTargetHost = null
            },
            title = { Text(stringResource(R.string.wireless_pairing_enter_code_title)) },
            text = {
                Column {
                    Text(
                        text = target.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wireless_pairing_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (target.pairing == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.wireless_pairing_waiting_port),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { value ->
                            pairingCode = value.filter { it.isDigit() }.take(6)
                        },
                        label = { Text(stringResource(R.string.wireless_pairing_code_label)) },
                        placeholder = { Text("••••••") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSubmit,
                    onClick = { submitPairing(target) },
                ) {
                    Text(stringResource(R.string.wireless_pairing_pair_button))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pairingTargetHost = null },
                ) {
                    Text(stringResource(R.string.wireless_pairing_cancel))
                }
            },
        )
    }
}

@Composable
private fun QrPairingFullscreen(
    onDismiss: () -> Unit,
    onStart: (AdbQrCredentials) -> Unit,
    onCancel: () -> Unit,
) {
    val credentials = remember { AdbQrCredentials.generate() }
    val density = LocalDensity.current
    val qrBitmap = remember(credentials) {
        val sizePx = with(density) { 280.dp.roundToPx() }
        credentials.toBitmap(sizePx)
    }

    LaunchedEffect(credentials) {
        onStart(credentials)
    }

    Dialog(
        onDismissRequest = {
            onCancel()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        onCancel()
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.wireless_pairing_qr_close),
                        tint = Color.Black,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.wireless_pairing_qr_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.wireless_pairing_qr_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.DarkGray,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.wireless_pairing_qr_title),
                modifier = Modifier.size(280.dp),
            )
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.DarkGray,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.wireless_pairing_qr_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    onCancel()
                    onDismiss()
                },
            ) {
                Text(
                    text = stringResource(R.string.wireless_pairing_cancel),
                    color = Color.DarkGray,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WirelessDeviceRowItem(
    device: WirelessDeviceRow,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val statusLabel = when {
        device.isChecking -> stringResource(R.string.wireless_pairing_action_checking)
        device.canPair || device.probe == RemoteProbeStatus.PairingRequired ->
            stringResource(R.string.wireless_pairing_action_pair)
        device.canConnect -> stringResource(R.string.wireless_pairing_action_connect)
        device.probe == RemoteProbeStatus.Unreachable ->
            stringResource(R.string.wireless_pairing_action_unreachable)
        else -> null
    }
    val statusColor = when {
        device.canConnect -> MaterialTheme.colorScheme.primary
        device.probe == RemoteProbeStatus.Unreachable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = device.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (statusLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (device.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
