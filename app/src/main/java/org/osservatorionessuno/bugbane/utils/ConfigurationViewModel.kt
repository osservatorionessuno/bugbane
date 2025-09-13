package org.osservatorionessuno.bugbane.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.MainActivity
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TAG = "ConfigurationViewModel"

/** ViewModel that holds and emits AppState, the overall state flow,
 * and manages transitions between states.
 * States are not inherently ordered or aware of their position in the StateFlow;
 * state flow is controlled via:
 *  ConfigurationViewModel::getState() (runs checks and returns current state)
 *  ConfiguationViewModel::checkUpdateState() (emits that value in a StateFlow), and
 *  ConfigurationViewModel::onChangeStateRequest() defines transition ("next")
 *  behaviour for each state.
 *
 * A single ConfigurationViewModel instance is used and is scoped to the application's
 * lifecycle (all Context references are to Application Context). This means the
 * ViewModel is responsible for registering and de-registering services/listeners.
 */
@OptIn(ExperimentalAtomicApi::class)
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
                WifiConnectivityMonitor(application)
            )
        }
    }

    // AppState is MutableFlow collected for UI listeners
    private val _configurationState = MutableStateFlow<AppState>(AppState.NeedWelcomeScreen)
    val configurationState: StateFlow<AppState> = _configurationState.asStateFlow()

    private val hasTriedAutoConnect = AtomicBoolean(false)

    init {
        // Set up listeners for states that can affect AppState
        observeAdbConfiguration()
        observeWifiConnectivity()
        observeAppState()
    }
    internal fun observeAdbConfiguration() {
        viewModelScope.launch {
            // Use main checkUpdate method
            adbManager.adbState.collect { adbState ->
                checkUpdateState()
            }
        }
    }
    internal fun observeWifiConnectivity() {
        viewModelScope.launch {
            // Use main checkUpdate method
            wifiConnectivityMonitor.wifiState.collect { isConnected ->
                Log.d(TAG, "Wifi connectivity change, tell ADB manager")
                adbManager.checkState()
            }
        }
    }

    // Handle transition states that should require no user interaction (autoconnect)
    internal fun observeAppState() {
        viewModelScope.launch {
            configurationState.collect { appState ->
                if (appState == AppState.TryAutoConnect && !hasTriedAutoConnect.load()) {
                    Log.d(TAG, "Try autoconnect")
                    adbManager.autoConnect()
                    hasTriedAutoConnect.store(true)
                } else if (appState !in arrayOf(AppState.AdbConnecting, AppState.TryAutoConnect)) {
                    hasTriedAutoConnect.store(false)
                }
                checkUpdateState()
            }
        }
    }


    internal fun checkState(): AppState {
        // Get the "best" state - requisites/logic enforced here.
        // If a user has completed the welcome screen, is connected to wifi and has an
        // active adb connection, skip the other checks.
        // If a user is missing one of those, they will need pairing flow again.
        // Check only: don't introduce side-effects here.

        // TODO: This can be defined in the manifest if it's just about API level
        val isOnboarding = !SlideshowManager.hasSeenHomepage(appContext)
        if (!ConfigurationManager.isSupportedDevice()) return AppState.DeviceUnsupported
        if (!SlideshowManager.canSkipWelcomeScreen(appContext)) return AppState.NeedWelcomeScreen
        val isConnectedToWifi = wifiConnectivityMonitor.wifiState.value

        // adbConnected -> we're connected, connected+scanning, or connected for the first time (connected finish onboarding).
        // Don't just rely on adb, since it's async and may lag to report its status
        val isWirelessDebug = ConfigurationManager.isAdbEnabled(appContext) && isConnectedToWifi

        if (isWirelessDebug && adbManager.adbState.value == AdbState.ConnectedIdle && !isOnboarding) return AppState.AdbConnected
        if (isWirelessDebug && adbManager.adbState.value == AdbState.ConnectedIdle && isOnboarding) return AppState.AdbConnectedFinishOnboarding
        if (isWirelessDebug && adbManager.adbState.value == AdbState.ConnectedAcquiring) return AppState.AdbScanning

        // Are we trying to connect already?
        if (adbManager.adbState.value == AdbState.Connecting) return AppState.AdbConnecting

        // If we get here, we may need to go through the pairing workflow again, so ensure prereqs are met
        if (!ConfigurationManager.isNotificationPermissionGranted(appContext)) return AppState.NeedNotificationConfiguration
        if (!isConnectedToWifi) return AppState.NeedWifi
        if (!ConfigurationManager.isDeveloperOptionsEnabled(appContext)) return AppState.NeedDeveloperOptions
        if (!isWirelessDebug || hasTriedAutoConnect.load()) return AppState.NeedWirelessDebuggingAndPair

        Log.d(TAG, "checkState: isAdbEnabled=${ConfigurationManager.isAdbEnabled(appContext)} adbManager ${adbManager.adbState.value}")
        return AppState.TryAutoConnect
    }

    fun checkUpdateState() {
        // StateFlow doesn't emit duplicates, so this is fine
        val newState = checkState()
        Log.d(TAG, "checkUpdateState: $newState")
        _configurationState.value = newState
    }

    // Handle state transition (user-initiated)
    fun onChangeStateRequest(currentState: AppState) {
        Log.d(TAG, "onChangeRequest from $currentState" )
        when (currentState) {
            AppState.DeviceUnsupported -> {
                (appContext as? Activity)?.finishAffinity()
            }
            AppState.NeedWelcomeScreen -> {
                // Clicked "I understand," nothing else to do
                SlideshowManager.setHasSeenWelcomeScreen(appContext)
                checkUpdateState()
            }
            AppState.NeedWifi, AppState.NeedNotificationConfiguration, AppState.NeedDeveloperOptions -> {
                getIntentForAppState(currentState)?.let { it ->
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(it)
                }
            }
            AppState.NeedWirelessDebuggingAndPair -> {
                getIntentForAppState(currentState)?.let { it ->
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(it)
                }
                adbManager.startAdbPairingService()
            }
            AppState.AdbConnectedFinishOnboarding -> {
                SlideshowManager.markHomepageAsSeen(appContext)
                checkUpdateState()
            }
            else -> { // Should be unreachable
                Log.w(TAG, "$currentState not handled by onChangeStateRequest")
            }
        }
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
