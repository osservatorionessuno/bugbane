package org.osservatorionessuno.bugbane.utils

// All permissions-related states should be defined here.
// Ordering/requisites are defined in the ConfigurationViewModel
sealed class AppState(val index: Int) {
    object DeviceUnsupported : AppState(0)
    object NeedWelcomeScreen: AppState(1)
    object NeedWifi : AppState(2)
    object NeedNotificationConfiguration : AppState(3)
    object NeedDeveloperOptions : AppState(4)
    object NeedWirelessDebugging : AppState(5)
    // sub-states in ADB pairing flow (waiting to pair, mdns, etc) are handled by AdbManager
    object NeedAdbPairingService : AppState(6)
    //object NeedAdbConnectService : ConfigurationState(6)
    // We've connected to ADB, but we've never completed the onboarding screen ("Get started")
    object AdbConnectedFinishOnboarding : AppState(7)
    object AdbConnected : AppState(8)

    companion object {
        fun valuesInOrder(): List<AppState> = listOf(
            DeviceUnsupported,
            NeedWelcomeScreen,
            NeedWifi,
            NeedNotificationConfiguration,
            NeedDeveloperOptions,
            NeedWirelessDebugging,
            NeedAdbPairingService,
//            NeedAdbConnectService,
            AdbConnectedFinishOnboarding,
            AdbConnected
        )
        // Error states can adjust the theme's UI
        fun isErrorState(state: AppState): Boolean {
            return (state is DeviceUnsupported)
        }
    }
}
