package org.osservatorionessuno.bugbane.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.MainActivity

class ConfigurationViewModel private constructor(
    val appContext: Context,
    // adb state: needs pair, paired, scanning, etc
    val adbManager: AdbManager,
    // wifi connectivity listener
    val wifiConnectivityMonitor: WifiConnectivityMonitor,
    ) : ViewModel() {

    // There Can Be Only One (see factory below)
    companion object {
        fun create(application: Application): ConfigurationViewModel {
            return ConfigurationViewModel(
                application.applicationContext,
                AdbManager(application.applicationContext),
                WifiConnectivityMonitor(application.applicationContext)
            )
        }
    }

    // AppState is MutableFlow collected for UI listeners
    private val _configurationState = MutableStateFlow<AppState>(AppState.NeedWelcomeScreen)
    val configurationState: StateFlow<AppState> = _configurationState.asStateFlow()

    private val adbPairingReceiver =
        AdbPairingResultReceiver(
            onSuccess = {
                Log.d("Bugbane", "paired successfully")
                _configurationState.value = AppState.AdbConnected
                stopAdbPairingService()
            },
            onFailure = { errorMessage ->
                // TODO (should be an adbManager state)
                Log.e("Bugbane", "failed pairing attempt")
                _configurationState.value = AppState.NeedWirelessDebuggingAndPair
                stopAdbPairingService()
            }
        )

    init {
        // Set up listeners for states that can affect AppState
        observeAdbConfiguration()
        observeWifiConnectivity()
    }
    internal fun observeAdbConfiguration() {
        viewModelScope.launch {
            adbManager.adbState.collect { adbState -> checkUpdateState()
            }
        }
    }
    internal fun observeWifiConnectivity() {
        viewModelScope.launch {
            wifiConnectivityMonitor.wifiState.collect { isConnected ->
                if (!isConnected) {
                    // Could also directly set _appState to AppState.NeedsWifi,
                    // but better to keep all the state logic in one place,
                    // especially for evolving logic (eg looking at past acquisitions)
                    checkUpdateState()
                }
            }
        }

    }

    internal fun checkState(): AppState {
        // Get the "best" state - requisites/logic enforced here.
        // If a user has completed the welcome screen, is connected to wifi and has an
        // active adb connection, skip the other checks.
        // If a user is missing one of those, they will need pairing flow again.
        // Note: User could also scan an prior acquisition, where permissions aren't needed,
        // but that is independent of the device's permissions/states, so address it in UI.

        // TODO: This can be defined in the manifest if it's just about API level
        if (!ConfigurationManager.isSupportedDevice()) return AppState.DeviceUnsupported

        if (!SlideshowManager.canSkipWelcomeScreen(appContext)) return AppState.NeedWelcomeScreen

        // TODO: ContentObserver? (https://developer.android.com/reference/android/database/ContentObserver)
        val adbConnected = ConfigurationManager.isAdbEnabled(appContext) && adbManager.adbState.value in AdbState.successStates()
        if (adbConnected && SlideshowManager.hasSeenHomepage(appContext)) return AppState.AdbConnected
        if (adbConnected && !SlideshowManager.hasSeenHomepage(appContext)) return AppState.AdbConnectedFinishOnboarding

        // If we get here, we need to go through the pairing workflow again, so ensure prereqs are met
        if (!ConfigurationManager.isNotificationPermissionGranted(appContext)) return AppState.NeedNotificationConfiguration
        if (!ConfigurationManager.isConnectedToWifi(appContext)) return AppState.NeedWifi
        if (!ConfigurationManager.isDeveloperOptionsEnabled(appContext)) return AppState.NeedDeveloperOptions

        if (!ConfigurationManager.isWirelessDebuggingEnabled(appContext) ||!ConfigurationManager.isAdbEnabled(appContext)) return AppState.NeedWirelessDebuggingAndPair

        // TODO: could do NeedAdbPairingService here (autoconnect optimization) if the above check could tell if we have a saved connection
        return AppState.NeedWirelessDebuggingAndPair
    }

    fun checkUpdateState() {
        // StateFlow doesn't emit duplicates, so this is fine
        _configurationState.value = checkState()
    }

    // Handle state transition (user-initiated)
    fun onChangeStateRequest(currentState: AppState) {
        when (currentState) {
            AppState.DeviceUnsupported -> {
                (appContext as? Activity)?.finishAffinity()
            }
            AppState.NeedWelcomeScreen -> {
                // Clicked "I understand," nothing else to do
                SlideshowManager.setHasSeenWelcomeScreen(appContext)
            }
            AppState.NeedWifi, AppState.NeedNotificationConfiguration, AppState.NeedDeveloperOptions -> {
                getIntentForAppState(currentState)?.let { it ->
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(it)
                }
            }
            AppState.NeedWirelessDebuggingAndPair -> {
                getIntentForAppState(currentState)?.let { it ->
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(it)
                }
                startAdbPairingService()
            }
            AppState.AdbConnectedFinishOnboarding -> {
                SlideshowManager.markHomepageAsSeen(appContext)
            }
            else -> { // Should be unreachable
                Log.e(appContext.packageName, "$currentState not handled")
            }
        }
        checkUpdateState()
    }

    internal fun stopAdbPairingService() {
        adbPairingReceiver.let { it ->
            try {
                appContext.unregisterReceiver(it)
            } catch (e: Exception) {
                // TODO
                Log.w("Bugbane", "Error unregistering adbBroadcastreceiver")
            }
        }
    }

    internal fun startAdbPairingService() {
        // Create BroadcastReceiver for pairing results.
        Log.d("Bugbane", "Start pairing service...")
        // TODO: AdbManager should handle all the pairing code and report state back directly

        val filter = IntentFilter(AdbPairingService.ACTION_PAIRING_RESULT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // This broadcast is internal to the app, so keep it private
            appContext.registerReceiver(adbPairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Pre-Android 13: old two-argument API
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(adbPairingReceiver, filter)
        }
        //        ConfigurationManager.openWirelessDebugging(appContext)

        // Start ADB pairing service
        val pairingIntent = AdbPairingService.startIntent(appContext)
        try {
            appContext.startForegroundService(pairingIntent)
        } catch (ignored: Throwable) {
            appContext.startService(pairingIntent)
        }
        // (Wait for pairing result, then update state and stop the service)
    }

    internal fun getIntentForAppState(state: AppState): Intent? {
        return when (state) {
            AppState.NeedWifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
            AppState.NeedNotificationConfiguration -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            }
            AppState.NeedDeveloperOptions -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            AppState.NeedWirelessDebuggingAndPair -> wirelessDebuggingIntent()
            AppState.AdbConnectedFinishOnboarding -> {
                val restartIntent = Intent(appContext, MainActivity::class.java)
                restartIntent.addFlags(FLAG_ACTIVITY_CLEAR_TOP)
            }
            else -> null
        }
    }
    internal fun wirelessDebuggingIntent(): Intent {
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

    override fun onCleared() {
        super.onCleared()
        // Unregister listeners/close sockets
        wifiConnectivityMonitor.cleanup()
        adbManager.cleanup()
        stopAdbPairingService()
    }
}

// Enforce singleton pattern with viewModel:
// - We genuinely want to sync AppState across varying activities
// - We don't want multiple instances of adbManager
// - We hold applicationContext and avoid holding ui references that could cause memory leaks
object ViewModelFactory {

    @Volatile
    private var instance: ConfigurationViewModel? = null

    fun get(application: Application): ConfigurationViewModel {
        return instance ?: synchronized(this) {
            instance ?: ConfigurationViewModel.create(application).also { instance = it }
        }
    }
}
