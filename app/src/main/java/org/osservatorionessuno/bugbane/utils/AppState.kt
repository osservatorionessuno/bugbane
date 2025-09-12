package org.osservatorionessuno.bugbane.utils

// AppStates represent the high-level device configuration and permissions status;
// state ordering/requisites are defined in the ViewModel (see ConfigurationViewModel).
// An AppState requires user interaction to change to a different state.
enum class AppState(val index: Int) {
    DeviceUnsupported(8),
    NeedWelcomeScreen(0), // one-time consent
    NeedNotificationConfiguration(1),
    NeedWifi(2),
    NeedDeveloperOptions(3),
    NeedWirelessDebuggingAndPair(4), // sub-states are handled by AdbManager
//    NeedAdbPairingService(5),
    AdbConnectedFinishOnboarding(6), // one-time onboarding complete
    AdbConnected(7);

    companion object {
        fun valuesInOrder(): List<AppState> = listOf(
            NeedWelcomeScreen,
            NeedNotificationConfiguration,
            NeedWifi,
            NeedDeveloperOptions,
            NeedWirelessDebuggingAndPair,
//            NeedAdbPairingService,
            AdbConnectedFinishOnboarding,
            AdbConnected,
            DeviceUnsupported,
            )
        // Error states have different UI implications
        fun isErrorState(state: AppState): Boolean {
            return (state == AppState.DeviceUnsupported)
        }
    }
}
