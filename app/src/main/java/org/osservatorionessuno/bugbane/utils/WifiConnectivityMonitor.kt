package org.osservatorionessuno.bugbane.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Monitor for changes in wifi connectivity
class WifiConnectivityMonitor(context: Context) {
    private val appContext = context.applicationContext

    private val _wifiState = MutableStateFlow(isWifiEnabled())
    val wifiState: StateFlow<Boolean> = _wifiState.asStateFlow()

    private fun hasWifiPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                _wifiState.value = state == WifiManager.WIFI_STATE_ENABLED
            }
        }
    }

    fun registerWifiMonitor() {
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (hasWifiPermissions()) {
            appContext.registerReceiver(wifiReceiver, filter)
        }
    }

    fun cleanup() {
        try {
            appContext.unregisterReceiver(wifiReceiver)
        }
        catch (ex: IllegalArgumentException) {
            // Unregistered already?
            Log.i(appContext.packageName, "WifiConnectivityMonitor cleanup failed (already unregistered?)")
        }
    }

    private fun isWifiEnabled(): Boolean {
        if (hasWifiPermissions()) {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifiManager.isWifiEnabled
        }
        return false // TODO
    }
}
