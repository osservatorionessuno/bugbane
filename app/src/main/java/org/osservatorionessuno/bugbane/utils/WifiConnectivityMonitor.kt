package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.context

private const val TAG = "WifiConnectivityMonitor"

object WifiConnectivityMonitor {

    private lateinit var connectivityManager: ConnectivityManager

    private val _wifiState = MutableStateFlow(false)
    val wifiState: StateFlow<Boolean> = _wifiState.asStateFlow()

    fun initialize(appContext: Context) {
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Register the callback to track connectivity changes
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        // Initialize state by checking current connectivity
        _wifiState.value = checkCurrentWifiConnected()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // available != connected
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isConnected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

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

    fun checkCurrentWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
