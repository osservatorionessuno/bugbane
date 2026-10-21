package org.osservatorionessuno.bugbane

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.osservatorionessuno.bugbane.components.SlideshowPageContent
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.ui.theme.QrSuccessGreen
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.HotspotManager.HotspotState
import org.osservatorionessuno.bugbane.utils.RemoteScanViewModel
import org.osservatorionessuno.bugbane.utils.RemoteScanViewModel.Step
import org.osservatorionessuno.bugbane.utils.RemoteScanViewModel.UsbRole
import org.osservatorionessuno.cadb.QrCode
import kotlinx.coroutines.delay

/** Connect another device over USB or Wi-Fi; the home screen then starts its acquisition. */
class RemoteScanActivity : ComponentActivity() {
    private val viewModel: RemoteScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            Theme {
                val step by viewModel.step.collectAsStateWithLifecycle()
                // The home screen shows the connected device and starts the acquisition.
                LaunchedEffect(step) { if (step == Step.Connected) finish() }
                // Swallow back during the success beat so it doesn't dismiss the wizard.
                BackHandler(enabled = step != Step.Transport) {
                    if (step !is Step.QrSuccess) viewModel.back()
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val current = step
                    // Leave the QR in place so the green covers that screen instead of replacing it.
                    val held = remember { arrayOf(current) }
                    if (current !is Step.QrSuccess) held[0] = current
                    val page = held[0]
                    Box(modifier = Modifier.fillMaxSize()) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .heightIn(min = maxHeight)
                                    .safeDrawingPadding()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                RemoteScanStep(page, viewModel)
                            }
                        }
                        if (current is Step.QrSuccess) QrSuccessScreen(current.index)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteScanStep(step: Step, viewModel: RemoteScanViewModel) {
    when (step) {
        Step.Transport -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(R.string.remote_transport_title),
                description = stringResource(R.string.remote_transport_description),
                icon = Icons.Filled.Usb,
            ),
            middle = {
                TransportButton(R.string.remote_transport_usb_button, enabled = viewModel.usbHostSupported) { viewModel.chooseUsb() }
                TransportButton(
                    if (viewModel.wifiDirectSupported) R.string.remote_transport_wifi_direct_button
                    else R.string.remote_transport_hotspot_button,
                ) { viewModel.chooseWifi() }
                if (!viewModel.usbHostSupported) {
                    Text(
                        text = stringResource(R.string.remote_transport_usb_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )

        is Step.UsbWaiting -> {
            val (title, description) = when (step.role) {
                UsbRole.NONE -> R.string.remote_usb_cable_title to R.string.remote_usb_cable_description
                UsbRole.DEVICE -> R.string.remote_usb_role_title to R.string.remote_usb_role_description
                UsbRole.HOST -> R.string.remote_usb_debugging_title to R.string.remote_usb_debugging_description
            }
            SlideshowPageContent(
                page = SlideshowPageData(
                    title = stringResource(title),
                    description = stringResource(description),
                    icon = if (step.role == UsbRole.NONE) Icons.Filled.Cable else Icons.Filled.Usb,
                ),
                middle = { Waiting(stringResource(R.string.remote_usb_waiting)) },
            )
        }

        is Step.Connecting -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(if (step.usb) R.string.remote_usb_allow_title else R.string.remote_connecting_title),
                description = stringResource(if (step.usb) R.string.remote_usb_allow_description else R.string.remote_connecting_description),
                icon = if (step.usb) Icons.Filled.Usb else Icons.Filled.Wifi,
            ),
            middle = { Waiting(null) },
        )

        Step.Hotspot -> HotspotStep(viewModel)

        is Step.WifiPairing -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(R.string.remote_wifi_pairing_title),
                description = stringResource(R.string.remote_wifi_pairing_description),
                icon = Icons.Filled.Wifi,
            ),
            middle = {
                Qr(step.credentials.qrPayload, index = 2)
                Waiting(stringResource(R.string.remote_wifi_waiting))
            },
        )

        is Step.QrSuccess -> Unit

        is Step.Error -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(R.string.remote_error_title),
                description = stringResource(step.messageRes),
                icon = Icons.Filled.ErrorOutline,
                buttonText = stringResource(R.string.remote_retry_button),
            ),
            onClickContinue = { viewModel.retry(step.retry) },
            isError = true,
        )

        Step.Connected -> Waiting(null)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun HotspotStep(viewModel: RemoteScanViewModel) {
    val hotspot by viewModel.hotspot.collectAsStateWithLifecycle()
    val permission = rememberPermissionState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
    )
    val granted = permission.status.isGranted
    LaunchedEffect(granted) { if (granted) viewModel.startHotspot() }

    val active = hotspot as? HotspotState.Active
    val error = hotspot is HotspotState.Error
    SlideshowPageContent(
        page = SlideshowPageData(
            title = stringResource(R.string.remote_hotspot_title),
            description = stringResource(
                when {
                    !granted -> R.string.remote_hotspot_permission
                    error -> R.string.remote_hotspot_error
                    else -> R.string.remote_hotspot_description
                }
            ),
            icon = Icons.Filled.WifiTethering,
            buttonText = stringResource(
                when {
                    !granted -> R.string.remote_hotspot_permission_button
                    error -> R.string.remote_retry_button
                    else -> R.string.remote_next_button
                }
            ),
        ),
        onClickContinue = {
            when {
                !granted -> permission.launchPermissionRequest()
                error -> viewModel.startHotspot()
                else -> viewModel.hotspotJoined()
            }
        },
        enabled = !granted || error || active != null,
        isError = error,
        middle = {
            if (granted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Text(
                    text = stringResource(R.string.remote_hotspot_location_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (active?.ssid != null && active.passphrase != null) {
                Text(
                    text = "${active.ssid}\n${active.passphrase}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                )
                Qr(QrCode.wifiPayload(active.ssid, active.passphrase), index = 1)
                if (active.wifiDirect && active.frequencyMhz >= 5000) {
                    TextButton(onClick = { viewModel.hotspotOn2GHz() }) {
                        Text(stringResource(R.string.remote_hotspot_switch_band), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!active.wifiDirect) {
                    Text(
                        text = stringResource(R.string.remote_hotspot_keep_open),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else if (granted && !error) {
                Waiting(null)
            }
        },
    )
}

@Composable
private fun TransportButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}

private const val QR_CODE_COUNT = 2

@Composable
private fun Qr(payload: String, index: Int) {
    val bitmap = remember(payload) { QrCode.bitmap(payload, 512).asImageBitmap() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 16.dp)) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.remote_qr_scanned, index, QR_CODE_COUNT),
            modifier = Modifier
                .size(240.dp)
                .background(Color.White),
        )
        QrProgress(index)
    }
}

/** Two numbered marks under the code: the one on screen is filled, earlier ones are checked. */
@Composable
private fun QrProgress(index: Int) {
    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (number in 1..QR_CODE_COUNT) {
            val reached = number <= index
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (number < index) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = number.toString(),
                        color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrSuccessScreen(index: Int) {
    var greenOn by remember { mutableStateOf(false) }
    var checkOn by remember { mutableStateOf(false) }
    val green by animateColorAsState(
        targetValue = if (greenOn) QrSuccessGreen else Color.Transparent,
        animationSpec = tween(durationMillis = 420),
        label = "qrSuccessGreen",
    )
    val scale by animateFloatAsState(
        targetValue = if (checkOn) 1f else 0.2f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "qrSuccessScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (checkOn) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "qrSuccessAlpha",
    )
    LaunchedEffect(index) {
        greenOn = true
        delay(160)
        checkOn = true
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(green)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
        )
        Text(
            text = stringResource(R.string.remote_qr_scanned, index, QR_CODE_COUNT),
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
        )
    }
}

@Composable
private fun Waiting(text: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
