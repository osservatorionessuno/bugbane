package org.osservatorionessuno.bugbane.utils

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.muntashirakon.adb.android.AdbUsb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.cadb.AdbManager
import org.osservatorionessuno.cadb.AdbNetworkDiscovery
import org.osservatorionessuno.cadb.AdbNetworkDiscovery.ServiceKind
import org.osservatorionessuno.cadb.AdbQrCredentials
import org.osservatorionessuno.qf.storage.AcquisitionTransport

private const val TAG = "RemoteScanViewModel"
private const val ACTION_USB_PERMISSION = "org.osservatorionessuno.bugbane.USB_PERMISSION"

/**
 * The analyst "scan a device" wizard: USB detection and permission, shared network,
 * mDNS, pairing and connection. Ends in [Step.Connected] on the shared ADB manager.
 */
class RemoteScanViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Step {
        object Transport : Step
        object UsbCable : Step
        object UsbWaiting : Step
        data class Connecting(val usb: Boolean) : Step
        object Hotspot : Step
        data class WifiPairing(val credentials: AdbQrCredentials) : Step
        data class Error(val messageRes: Int, val retry: Step) : Step
        object Connected : Step
    }

    private val appContext: Context = app.applicationContext
    private val adbManager: AdbManager = ViewModelFactory.get(app).adbManager
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _step = MutableStateFlow<Step>(Step.Transport)
    val step: StateFlow<Step> = _step.asStateFlow()

    val usbHostSupported: Boolean =
        usbManager != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private var job: Job? = null
    private var discovery: AdbNetworkDiscovery? = null
    private var usbReceiverRegistered = false
    private var requestedUsbDevice: String? = null

    init {
        HotspotManager.initialize(appContext)
        viewModelScope.launch(Dispatchers.IO) { adbManager.disconnect() }
    }

    fun chooseUsb() { _step.value = Step.UsbCable }

    fun usbCableConnected() {
        _step.value = Step.UsbWaiting
        restart {
            // The ADB interface appears only once USB debugging is on.
            val device = pollFlow { usbManager?.deviceList?.values?.firstOrNull(AdbUsb::isAdbDevice) }
            if (usbManager!!.hasPermission(device)) connectUsb(device) else requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (!usbReceiverRegistered) {
            ContextCompat.registerReceiver(
                appContext, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            usbReceiverRegistered = true
        }
        requestedUsbDevice = device.deviceName
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(
            appContext, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager!!.requestPermission(device, pending)
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_step.value != Step.UsbWaiting) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device == null || device.deviceName != requestedUsbDevice) return
            if (granted) {
                restart { connectUsb(device) }
            } else {
                _step.value = Step.Error(R.string.remote_usb_permission_denied, Step.UsbWaiting)
            }
        }
    }

    private suspend fun connectUsb(device: UsbDevice) {
        _step.value = Step.Connecting(usb = true)
        try {
            withContext(Dispatchers.IO) { adbManager.connectUsb(device) }
            _step.value = Step.Connected
        } catch (t: Throwable) {
            Log.w(TAG, "USB connection failed", t)
            _step.value = Step.Error(R.string.remote_usb_connect_failed, Step.UsbWaiting)
        }
    }

    fun chooseWifi() { _step.value = Step.Hotspot }

    val hotspot: StateFlow<HotspotManager.HotspotState> get() = HotspotManager.state

    fun startHotspot() = HotspotManager.start()

    fun hotspotJoined() {
        val credentials = AdbQrCredentials.generate()
        _step.value = Step.WifiPairing(credentials)
        restart {
            val discovery = AdbNetworkDiscovery(appContext).also { discovery = it; it.start() }
            // The target advertises the pairing service under the QR's name.
            val pairing = discovery.services
                .map { list -> list.firstOrNull { it.kind == ServiceKind.TLS_PAIRING && it.serviceName == credentials.serviceName } }
                .filter { it != null }.first()!!
            _step.value = Step.Connecting(usb = false)
            // The link may still be settling: the first packets can fail to route.
            val paired = retrying { adbManager.pairRemote(pairing.host, pairing.port, credentials.password) } == true
            if (!paired) {
                _step.value = Step.Error(R.string.remote_wifi_pairing_failed, Step.Hotspot)
                return@restart
            }
            val connect = withTimeoutOrNull(CONNECT_DISCOVERY_TIMEOUT_MS) {
                discovery.services
                    .map { list -> list.firstOrNull { it.kind == ServiceKind.TLS_CONNECT && it.host == pairing.host } }
                    .filter { it != null }.first()!!
            }
            if (connect == null) {
                _step.value = Step.Error(R.string.remote_wifi_connect_failed, Step.Hotspot)
                return@restart
            }
            val shared = HotspotManager.state.value as? HotspotManager.HotspotState.Active
            val via = AcquisitionTransport(
                if (shared?.wifiDirect == false) AcquisitionTransport.WIFI_HOTSPOT else AcquisitionTransport.WIFI_DIRECT,
                shared?.ssid,
            )
            if (retrying { adbManager.connectRemote(connect.host, connect.port, via); true } == true) {
                _step.value = Step.Connected
            } else {
                _step.value = Step.Error(R.string.remote_wifi_connect_failed, Step.Hotspot)
            }
        }
    }

    fun retry(step: Step) {
        when (step) {
            Step.UsbWaiting -> usbCableConnected()
            else -> { stopWork(); _step.value = step }
        }
    }

    fun back() {
        stopWork()
        _step.value = when (_step.value) {
            Step.UsbCable, Step.Hotspot -> Step.Transport
            Step.UsbWaiting -> Step.UsbCable
            is Step.WifiPairing -> Step.Hotspot
            else -> Step.Transport
        }
        if (_step.value == Step.Transport) HotspotManager.stop()
    }

    /** Run a blocking transport call on IO up to [ATTEMPTS] times; null when all failed. */
    private suspend fun <T> retrying(block: () -> T): T? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return withContext(Dispatchers.IO) { block() }
            } catch (t: Throwable) {
                Log.w(TAG, "attempt ${attempt + 1}/$ATTEMPTS failed", t)
                if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun restart(block: suspend () -> Unit) {
        stopWork()
        job = viewModelScope.launch { block() }
    }

    private fun stopWork() {
        job?.cancel(); job = null
        discovery?.stop(); discovery = null
    }

    private suspend fun <T : Any> pollFlow(probe: () -> T?): T {
        while (true) {
            probe()?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun onCleared() {
        stopWork()
        if (usbReceiverRegistered) runCatching { appContext.unregisterReceiver(usbPermissionReceiver) }
        // A running Wi-Fi acquisition keeps the network; AcquisitionProgressTracker stops it.
        if (_step.value != Step.Connected) HotspotManager.stop()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val CONNECT_DISCOVERY_TIMEOUT_MS = 30_000L
        private const val ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 4_000L
    }
}
