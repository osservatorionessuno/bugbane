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
                delay(5000)
            }
        }
    }

    private fun checkState(): AppState {
        // Get the "best" state - all requisites/logic enforced here.
        // If a user has completed the welcome screen, is connected to
        // wifi and has an active adb connection, skip the other checks.
        // If a user is missing one of those, they will need to pair again,
        // so check for notifications enabled, etc
        val needWelcomeScreen = ConfigurationManager.needWelcomeScreen(appContext)
        if (needWelcomeScreen) return AppState.NeedWelcomeScreen

        val wifi = ConfigurationManager.isConnectedToWifi(appContext)
        if (!wifi) return AppState.NeedWifi

        val devOptions = ConfigurationManager.isDeveloperOptionsEnabled(appContext)
        if (!devOptions) return AppState.NeedDeveloperOptions

        // Skip other checks if we are already connected
        // TODO: on first view, show "Get started"; on later views, jump to acquisitions screen
        val adbConnected = ConfigurationManager.isAdbEnabled(appContext)
        if (adbConnected) return AppState.AdbConnected

        val notifications = ConfigurationManager.isNotificationPermissionGranted(appContext)
        if (!notifications) return AppState.NeedNotificationConfiguration

        val wirelessDebugging = ConfigurationManager.isWirelessDebuggingEnabled(appContext)
        if (!wirelessDebugging) return AppState.NeedWirelessDebugging

        // TODO: this can be better with watching adb connection as a state
        return AppState.NeedAdbPairingService
        // If we get here, we are probably pairing
    }
}
