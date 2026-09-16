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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.AcquisitionProgressTracker
import org.osservatorionessuno.bugbane.utils.HotspotManager.HotspotState
import org.osservatorionessuno.bugbane.utils.RemoteScanViewModel
import org.osservatorionessuno.bugbane.utils.RemoteScanViewModel.Step
import org.osservatorionessuno.bugbane.utils.ViewModelFactory
import org.osservatorionessuno.cadb.QrCode
import java.io.File

/** Connect another device over USB or Wi-Fi, then start its acquisition. */
class RemoteScanActivity : ComponentActivity() {
    private val viewModel: RemoteScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            Theme {
                val step by viewModel.step.collectAsStateWithLifecycle()
                val context = LocalContext.current
                LaunchedEffect(step) {
                    if (step == Step.Connected) {
                        val adbManager = ViewModelFactory.get(application).adbManager
                        AcquisitionProgressTracker.start(context, adbManager, File(filesDir, "acquisitions"))
                        finish()
                    }
                }
                BackHandler(enabled = step != Step.Transport) { viewModel.back() }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))
                        RemoteScanStep(step, viewModel)
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
                TransportButton(R.string.remote_transport_wifi_button) { viewModel.chooseWifi() }
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

        Step.UsbCable -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(R.string.remote_usb_cable_title),
                description = stringResource(R.string.remote_usb_cable_description),
                icon = Icons.Filled.Cable,
                buttonText = stringResource(R.string.remote_next_button),
            ),
            onClickContinue = { viewModel.usbCableConnected() },
        )

        Step.UsbWaiting -> SlideshowPageContent(
            page = SlideshowPageData(
                title = stringResource(R.string.remote_usb_debugging_title),
                description = stringResource(R.string.remote_usb_debugging_description),
                icon = Icons.Filled.Usb,
            ),
            middle = { Waiting(stringResource(R.string.remote_usb_waiting)) },
        )

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
                Qr(step.credentials.qrPayload)
                Waiting(stringResource(R.string.remote_wifi_waiting))
            },
        )

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
                Qr(QrCode.wifiPayload(active.ssid, active.passphrase))
                Text(
                    text = "${active.ssid}\n${active.passphrase}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                )
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

@Composable
private fun Qr(payload: String) {
    val bitmap = remember(payload) { QrCode.bitmap(payload, 512).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .padding(vertical = 16.dp)
            .size(240.dp)
            .background(Color.White),
    )
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
