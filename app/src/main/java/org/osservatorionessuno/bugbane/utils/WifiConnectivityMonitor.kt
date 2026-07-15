package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "WifiConnectivityMonitor"

object WifiConnectivityMonitor {

    private lateinit var connectivityManager: ConnectivityManager

    private val _wifiState = MutableStateFlow(false)
    val wifiState: StateFlow<Boolean> = _wifiState.asStateFlow()

    fun initialize(appContext: Context) {
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Watch Wi-Fi by transport, not the default network: an internet-less Wi-Fi
        // isn't the default network while mobile data is on, so the default-network
        // callback would miss it.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // available != connected
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isConnected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

            if (_wifiState.value != isConnected) {
                Log.d(TAG, "wifi connection state ${_wifiState.value} -> $isConnected")
                _wifiState.value = isConnected
            }
        }

        override fun onLost(network: Network) {
            if (_wifiState.value) {
                Log.d(TAG, "wifi lost")
                _wifiState.value = false
            }
        }
    }

    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (ex: IllegalArgumentException) {
            Log.i(TAG, "NetworkCallback already unregistered")
        }
    }

    // The callback keeps this current; activeNetwork can't be used because an
    // internet-less Wi-Fi isn't the active network when mobile data is on.
    fun checkCurrentWifiConnected(): Boolean = _wifiState.value
}
