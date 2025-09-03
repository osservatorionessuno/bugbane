package org.osservatorionessuno.bugbane.utils

// All states should be defined here.
// Ordering/requisites are defined in the ConfigurationViewModel
sealed class AppState(val index: Int) {
    // Flow: Wifi, App Notification Perm, Dev Options, AdbConnection
    object NeedWelcomeScreen: AppState(0)
    object NeedWifi : AppState(1)
    object NeedNotificationConfiguration : AppState(2)
    object NeedDeveloperOptions : AppState(3)
    object NeedWirelessDebugging : AppState(4)
    object NeedAdbPairingService : AppState(5)
    //object NeedAdbConnectService : ConfigurationState(6)
    object NeedAdbConnection : AppState(6)
    object AdbConnected : AppState(7)

    companion object {
        fun valuesInOrder(): List<AppState> = listOf(
            NeedWelcomeScreen,
            NeedWifi,
            NeedNotificationConfiguration,
            NeedDeveloperOptions,
            NeedWirelessDebugging,
            NeedAdbPairingService,
//            NeedAdbConnectService,
            NeedAdbConnection,
            AdbConnected
        )
    }
}
