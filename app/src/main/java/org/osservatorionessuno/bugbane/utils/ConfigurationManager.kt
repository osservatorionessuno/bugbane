package org.osservatorionessuno.bugbane.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.content.Intent
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// Manages phone settings (App-specific settings belong in SlideshowManager)
object ConfigurationManager {

    fun openDeveloperOptions(context: Context) {
        // Open the developer options settings
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    // Can just do this via the manifest, but including here is ~harmless
    fun isSupportedDevice() : Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        } else if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return false
    }

    fun isConnectedToWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return try {            
            val developerOptionsEnabled = isDeveloperOptionsEnabled(context)
            val adbEnabled = isAdbEnabled(context)

            developerOptionsEnabled && adbEnabled
        } catch (e: Exception) {
            Log.e("ConfigurationManager", "Error checking wireless debugging status", e)
            false
        }
    }
    
    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            Log.e("ConfigurationManager", "Error checking developer options status", e)
            false
        }
    }
    
    fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            Log.e("ConfigurationManager", "Error checking ADB status", e)
            false
        }
    }
}
