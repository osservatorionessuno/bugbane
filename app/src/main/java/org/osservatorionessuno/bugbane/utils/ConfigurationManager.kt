package org.osservatorionessuno.bugbane.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

private const val PREFS_NAME = "app_prefs"
private const val KEY_HAS_SEEN_HOMEPAGE = "has_seen_homepage"

object ConfigurationManager {

    fun openDeviceSettings(context: Context) {
        // Open the developer options settings
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (ignored: Exception) {
        }
    }

    fun openDeveloperOptions(context: Context) {
        // Open the developer options settings
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    fun openWifiSettings(context: Context) {
        // Open Wi-Fi settings
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    fun openWirelessDebugging(context: Context) {
        // Open wireless debugging settings
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            val bundle = Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            }
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        }
        try {
            context.startActivity(settingsIntent)
        } catch (e: Exception) {
        }
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

    fun needWelcomeScreen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_SEEN_HOMEPAGE, false)
    }


    fun wirelessDebuggingIntent(): Intent {
        // Open wireless debugging settings
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            val bundle = Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            }
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        }
        return settingsIntent
    }

    fun launchIntentByState(context: Context, state: AppState) {
        val intent = when (state) {
            AppState.NeedWifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
            AppState.NeedNotificationConfiguration -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            AppState.NeedDeveloperOptions -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            AppState.NeedWirelessDebugging -> wirelessDebuggingIntent()

            // TODO: anything else that needs an activity launched?
            else -> null
        }
        intent?.let {
            try {
                context.startActivity(it)
            } catch (e: Exception) {
            }
        }
    }
}
