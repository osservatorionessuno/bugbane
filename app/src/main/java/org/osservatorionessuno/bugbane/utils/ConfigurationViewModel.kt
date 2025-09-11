package org.osservatorionessuno.bugbane.utils

import android.app.Application
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class ConfigurationViewModel (
    private val application: Application

) : ViewModel() {

    private val _configurationState = MutableStateFlow<AppState>(AppState.NeedWifi)
    val configurationState: StateFlow<AppState> = _configurationState.asStateFlow()

    // Only need the context, but don't pass it in directly since Android complains about
    // memory leaks.
    private val appContext = application.applicationContext // todo: check with strictmode

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            // TODO: this is a bit hacky, but it polls for active state- it's not required
            while (isActive) {
                val newState = checkState()
                if (_configurationState.value != newState) {
                    _configurationState.value = newState
                }
                delay(1000)
            }
        }
    }

    fun checkState(): AppState {
        // Get the "best" state - all requisites/logic enforced here and order matters.
        // If a user has completed the welcome screen, is connected to wifi and has an
        // active adb connection, skip the other checks.
        // If a user is missing one of those, they will need pairing flow again.

        // TODO: This can be defined in the manifest if it's just about API level
        if (!ConfigurationManager.isSupportedDevice()) return AppState.DeviceUnsupported

        // Consent page
        if (!SlideshowManager.canSkipWelcomeScreen(appContext)) return AppState.NeedWelcomeScreen

        // See if we're already connected. If yes but it's the first time, we're in state
        // AdbConnectedFinishOnboarding, otherwise state AdbConnected
        // (TODO - check if need wifi check explicitly or not)
        // also TODO: ContentObserver (https://developer.android.com/reference/android/database/ContentObserver)
        val adbConnected = ConfigurationManager.isAdbEnabled(appContext)
        if (adbConnected && SlideshowManager.hasSeenHomepage(appContext)) return AppState.AdbConnected
        if (adbConnected && !SlideshowManager.hasSeenHomepage(appContext)) return AppState.AdbConnectedFinishOnboarding

        // Notifications aren't strictly needed unless we're not connected to adb
        if (!ConfigurationManager.isNotificationPermissionGranted(appContext)) return AppState.NeedNotificationConfiguration

        // Wifi, developer options, wireless debugging
        if (!ConfigurationManager.isConnectedToWifi(appContext)) return AppState.NeedWifi
        if (!ConfigurationManager.isDeveloperOptionsEnabled(appContext)) return AppState.NeedDeveloperOptions
        if (!ConfigurationManager.isWirelessDebuggingEnabled(appContext)) return AppState.NeedWirelessDebugging

        // If we get here, we need and are ready for the ADB pairing wizard flow (todo handled by another viewmodel?)
        return AppState.NeedAdbPairingService
    }
}
