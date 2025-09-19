package org.osservatorionessuno.bugbane.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.MainActivity
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TAG = "ConfigurationViewModel"

/** ViewModel that holds and emits AppState and manages transitions between states.
 * States are not inherently ordered or aware of their position; ordering is managed
 * via ConfigurationViewModel::getState() and ConfigurationViewModel::onChangeStateRequest()
 * defines transition ("next") behaviour for each state.
 *
 * State-checking is done by manager classes and should happen async (coroutine) when possible.
 * These managers implement listeners and must be registered and de-registered on cleanup.
 *
 * A single ConfigurationViewModel instance is used and is scoped to the application's
 * lifecycle (all Context references are to Application Context).
 */
@OptIn(ExperimentalAtomicApi::class)
class ConfigurationViewModel private constructor(
    val appContext: Context,
    val adbManager: AdbManager,
    val appManager: SlideshowManager = SlideshowManager,
    val wifiConnectivityMonitor: WifiConnectivityMonitor = WifiConnectivityMonitor,
    val configurationManager: ConfigurationManager = ConfigurationManager,
) : ViewModel() {

    // There Can Be Only One (see factory below)
    companion object {
        fun create(application: Application): ConfigurationViewModel {
            val appContext = application.applicationContext
            ConfigurationManager.initialize(appContext)
            SlideshowManager.initialize(appContext)
            WifiConnectivityMonitor.initialize(appContext)
            return ConfigurationViewModel(
                appContext,
                AdbManager(appContext)
            )
        }
    }

    // UI listeners collect AppState
    private val _configurationState = MutableStateFlow<AppState>(AppState.NeedWelcomeScreen)
    val configurationState: StateFlow<AppState> = _configurationState.asStateFlow()

    // Some states take a while to broadcast a result; don't confuse the user while waiting
    val _needsAsyncStateResult: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val needsAsyncStateResult: StateFlow<Boolean> = _needsAsyncStateResult.asStateFlow()

    private val autoConnectAttempts = AtomicInt(0)
    private val _MAX_AUTOCONNECT_ATTEMPTS = 2

    init {
        observeCombinedState()
        observeAppState()
    }

    // Kotlin complained about combining Flow<Boolean> with Flow<Any?>,
    // so use a wrapper class for all the boolean values, and then combine
    // SettingsState, AppPogress, AdbState
    data class SettingsState(
        val notificationsEnabled: Boolean,
        val developerOptionsEnabled: Boolean,
        val adbEnabled: Boolean,
        val wirelessDebuggingEnabled: Boolean,
        val wifiConnected: Boolean
    )

    /**
     * Merge all the stateflow objects and recalculate using checkState()
     * when one of our components posts an update, then (synchronously)
     * emit new AppState.
     */
    private fun observeCombinedState() {
        viewModelScope.launch {
            val settingsFlow = combine(configurationManager.notificationsEnabled,
                configurationManager.developerOptionsEnabled,
                configurationManager.adbEnabled,
                configurationManager.wirelessDebuggingEnabled,
                wifiConnectivityMonitor.wifiState,
            ) { notifications, devOpts, adbEnabled, wirelessDebug, wifiConnected ->
                SettingsState(notifications, devOpts, adbEnabled, wirelessDebug, wifiConnected) }

            combine(settingsFlow, adbManager.adbState, appManager.appProgress) { settings, adbState, appProgress ->
                checkState(
                    settings.notificationsEnabled,
                    settings.developerOptionsEnabled,
                    settings.adbEnabled,
                    settings.wirelessDebuggingEnabled,
                    settings.wifiConnected,
                    adbState,
                    appProgress)
            }.distinctUntilChanged()
                .collect { newState ->
                    Log.d(TAG, "New appState $newState")
                    _configurationState.value = newState
                }
        }
    }

    /**
     * Determine the AppState - all requisites/state logic enforced here.
     *
     * If a user has completed the welcome screen, is connected to wifi and has an
     * active adb wireless debugging connection, skip the other checks.
     * If a user has previously connected to ADB, try to reconnect (autoconnect)
     * as long as the pre-requisites are met. Otherwise, they will need pairing flow again.
     *
     * Check only: don't introduce side-effects here.
     */
    private fun checkState(
        notificationsEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        adbEnabled: Boolean,
        wirelessDebuggingEnabled: Boolean,
        isConnectedToWifi: Boolean,
        adbState: AdbState,
        appProgress: SlideshowManager.AppProgress,
    ): AppState {
        // TODO: This can be defined in the manifest if it's just about API level
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return AppState.DeviceUnsupported
        if (!appProgress.hasSeenWelcomeScreen) return AppState.NeedWelcomeScreen

        if (wirelessDebuggingEnabled && adbEnabled) {
            if (adbState == AdbState.ConnectedIdle && !appProgress.hasCompletedOnboarding) return AppState.AdbConnectedFinishOnboarding
            if (adbState == AdbState.ConnectedIdle) return AppState.AdbConnected
            if (adbState == AdbState.ConnectedAcquiring) return AppState.AdbScanning
            if (adbState == AdbState.Connecting) return AppState.AdbConnecting

            if (appProgress.hasCompletedOnboarding && autoConnectAttempts.load() < _MAX_AUTOCONNECT_ATTEMPTS) return AppState.TryAutoConnect
            // Wireless debugging is on, but our preconditions weren't met.
            // Maybe we failed autoconnect, or maybe we're connecting for the first time and need to pair first.
            Log.i(TAG, "checkState: wirelessDebug true, adbState $adbState, hasTriedAutoconnect=${autoConnectAttempts.load()}")
        }

        // We need to go through some part of the pairing flow
        // (The order here informs the onboarding order)
        if (!notificationsEnabled) return AppState.NeedNotificationPermission
        if (!isConnectedToWifi) return AppState.NeedWifi
        if (!developerOptionsEnabled) return AppState.NeedDeveloperOptions
        if ((!wirelessDebuggingEnabled || !adbEnabled) && !appProgress.hasCompletedOnboarding) return AppState.NeedWirelessDebuggingAndPair

        if ((!wirelessDebuggingEnabled || !adbEnabled)) {
            // Wireless adb or adb are off, but we've connected before. Enable wireless adb. Then autoconnect will be attempted.
            Log.d(TAG, "Wireless adb is disabled, but we have previously connected successfully.")
            return AppState.NeedWirelessDebugging
        }

        // We don't have a past connection, but nothing else is wrong. We can try autoconnect.
        if (autoConnectAttempts.load() < _MAX_AUTOCONNECT_ATTEMPTS) return AppState.TryAutoConnect

        // We did not want to get here. That means no other conditions were met and autoconnect failed once.
        Log.w(TAG, "checkState: default to re-pairing and debug connection logic.")
        Log.d(TAG, "adbState=$adbState, notifications=$notificationsEnabled, wifi=$isConnectedToWifi, devOpts=$developerOptionsEnabled, has past adb connection=${appProgress.hasCompletedOnboarding}, tried autoconnect=${autoConnectAttempts.load()}")
        return AppState.NeedWirelessDebuggingAndPair
    }

    /**
     * Observe AppState and manage automatic state transitions here.
     */
    private fun observeAppState() {
        viewModelScope.launch {
            configurationState.collect { appState ->
                // Reset async waiting flag, since we received a new state
                _needsAsyncStateResult.value = false

                if (appState == AppState.TryAutoConnect && autoConnectAttempts.fetchAndAdd(1) < _MAX_AUTOCONNECT_ATTEMPTS) {
                    Log.d(TAG, "Auto-connect to ADB (attempt ${autoConnectAttempts.load()} / $_MAX_AUTOCONNECT_ATTEMPTS)")
                    adbManager.autoConnect()
                } else if (appState !in arrayOf(
                        AppState.AdbConnecting,
                        AppState.TryAutoConnect,
                        AppState.NeedWirelessDebugging,
                    )
                ) {
                    Log.d(TAG, "State $appState, reset autoconnection attempts to 0")
                    autoConnectAttempts.store(0)
                }
            }
        }
    }

    fun onChangeStateRequest(currentState: AppState) {
        Log.d(TAG, "onChangeRequest from $currentState")
        when (currentState) {
            AppState.DeviceUnsupported -> {
                (appContext as? Activity)?.finishAffinity()
            }

            AppState.NeedWelcomeScreen -> {
                appManager.setHasSeenWelcomeScreen()
            }

            AppState.NeedWifi,
            AppState.NeedNotificationPermission,
            AppState.NeedDeveloperOptions -> {
                _needsAsyncStateResult.value = true

                getIntentForAppState(currentState)?.let {
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(it)
                }
            }

            AppState.NeedWirelessDebuggingAndPair -> {
                // transitory state
                _needsAsyncStateResult.value = true

                getIntentForAppState(currentState)?.let {
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(it)
                }
                adbManager.startAdbPairingService()
            }
            AppState.NeedWirelessDebugging -> {
                // transitory state
                _needsAsyncStateResult.value = true

                // Just open settings, don't launch a new pairing service.
                // Once wireless debugging is re-enabled the state will be updated and autoconnect will be attempted
                getIntentForAppState(currentState)?.let {
                    it.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(it)
                }
            }
            AppState.AdbConnectedFinishOnboarding -> {
                appManager.markHomepageAsSeen()
                _needsAsyncStateResult.value = true
            }

            else -> {
                Log.w(TAG, "$currentState not handled by onChangeStateRequest")
            }
        }
    }

    private fun getIntentForAppState(state: AppState): Intent? {
        return when (state) {
            AppState.NeedWifi -> Intent(Settings.ACTION_WIFI_SETTINGS)

            AppState.NeedNotificationPermission -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            }

            AppState.NeedDeveloperOptions -> developerOptionsIntent()

            AppState.NeedWirelessDebuggingAndPair, AppState.NeedWirelessDebugging -> wirelessDebuggingIntent()

            AppState.AdbConnectedFinishOnboarding -> Intent(appContext, MainActivity::class.java).apply {
                addFlags(FLAG_ACTIVITY_CLEAR_TOP)
            }

            else -> null
        }
    }

    private fun wirelessDebuggingIntent(): Intent {
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            })
        }
    }

    private fun developerOptionsIntent(): Intent {
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        return Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, "my_device_info_pref_screen")
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, "build_number")
            })
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            configurationManager.checkAll()
            wifiConnectivityMonitor.checkCurrentWifiConnected()
            adbManager.checkState()
            appManager.checkState()
        }
    }



    override fun onCleared() {
        super.onCleared()
        wifiConnectivityMonitor.cleanup()
        adbManager.cleanup()
        configurationManager.cleanup()
        appManager.cleanup()
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
