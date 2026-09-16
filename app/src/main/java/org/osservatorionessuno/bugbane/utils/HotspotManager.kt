package org.osservatorionessuno.bugbane.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osservatorionessuno.cadb.AdbQrCredentials

private const val TAG = "HotspotManager"

/**
 * Network the scanned device joins: a Wi-Fi Direct group owned by this phone (plain
 * WPA2 to the client, no internet, named `DIRECT-bb-bugbane-<random>` so its trace on
 * the target is attributable), or a local-only hotspot where Wi-Fi Direct is missing.
 * The group outlives the foreground, so [stop] must be called when the scan ends.
 */
object HotspotManager {
    sealed class HotspotState {
        object Inactive : HotspotState()
        object Starting : HotspotState()
        data class Active(val ssid: String?, val passphrase: String?, val wifiDirect: Boolean) : HotspotState()
        data class Error(val reason: Int?) : HotspotState()
    }

    private lateinit var appContext: Context
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Inactive)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            p2p = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        }
    }

    fun wifiDirectSupported(): Boolean =
        p2p != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    // Needs NEARBY_WIFI_DEVICES (33+) or ACCESS_FINE_LOCATION, and Wi-Fi on.
    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value is HotspotState.Starting || _state.value is HotspotState.Active) return
        _state.value = HotspotState.Starting
        if (!wifiDirectSupported()) {
            startLocalOnlyHotspot()
            return
        }
        val p2p = p2p!!
        val channel = channel ?: p2p.initialize(appContext, Looper.getMainLooper()) {
            Log.w(TAG, "p2p channel lost")
            this.channel = null
            clear()
        }.also { channel = it }
        registerReceiver()
        val config = WifiP2pConfig.Builder()
            // "DIRECT-xy" prefix is mandated by the standard.
            .setNetworkName("DIRECT-bb-bugbane-" + AdbQrCredentials.randomString(6))
            .setPassphrase(AdbQrCredentials.randomString(16))
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .enablePersistentMode(false)
            .build()
        val create = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "group requested") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed, reason $reason")
                _state.value = HotspotState.Error(reason)
            }
        }
        // A leftover group makes createGroup fail with BUSY.
        p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = p2p.createGroup(channel, config, create)
            override fun onFailure(reason: Int) = p2p.createGroup(channel, config, create)
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val info = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
            val group = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
            if (info?.groupFormed == true && info.isGroupOwner && group != null) {
                if (_state.value is HotspotState.Starting) {
                    Log.d(TAG, "group ${group.networkName} up on ${group.`interface`}")
                    _state.value = HotspotState.Active(group.networkName, group.passphrase, wifiDirect = true)
                }
            } else if (_state.value is HotspotState.Active) {
                Log.d(TAG, "group gone")
                clear()
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    private fun startLocalOnlyHotspot() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val config = res.softApConfiguration
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        config.wifiSsid?.toString()?.removeSurrounding("\"")
                    } else {
                        @Suppress("DEPRECATION")
                        config.ssid
                    }
                    Log.d(TAG, "local-only hotspot started")
                    _state.value = HotspotState.Active(ssid, config.passphrase, wifiDirect = false)
                }

                override fun onStopped() {
                    Log.d(TAG, "local-only hotspot stopped")
                    reservation = null
                    clear()
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "local-only hotspot failed, reason $reason")
                    reservation = null
                    _state.value = HotspotState.Error(reason)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "startLocalOnlyHotspot failed: $e")
            _state.value = HotspotState.Error(null)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { reservation?.close() }.onFailure { Log.w(TAG, "error closing hotspot: $it") }
        reservation = null
        val p2p = p2p
        val channel = channel
        if (p2p != null && channel != null && _state.value !is HotspotState.Inactive) {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "group removed") }
                override fun onFailure(reason: Int) { Log.w(TAG, "removeGroup failed, reason $reason") }
            })
        }
        clear()
    }

    private fun clear() {
        _state.value = HotspotState.Inactive
    }

}
