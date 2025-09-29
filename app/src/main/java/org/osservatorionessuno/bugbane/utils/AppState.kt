package org.osservatorionessuno.bugbane.utils

// AppStates represent the high-level device configuration and permissions status;
// state requisites are defined in the ViewModel (see ConfigurationViewModel).
// An AppState requires user interaction to change to a different state.
private const val EXCLUDED_STEP = 999
enum class AppState(val step: Int) {
    NeedWelcomeScreen(0),
    NeedNotificationPermission(1),
    DeviceUnsupported(1), // Alternative to step 1: device isn't compatible
    NeedWifi(2),
    NeedDeveloperOptions(3),

    // Step 4: ADB/Wireless ADB/Pairing
    NeedWirelessDebuggingAndPair(4),
    NeedWirelessDebugging(4),

    // Step 5: ADB connection attempt
    AdbConnecting(5),
    AdbConnectedFinishOnboarding(5),
    AdbConnected(5),
    TryAutoConnect(5),
    AdbConnectionError(5),

    // Not part of our slideshow
    AdbScanning(EXCLUDED_STEP);

    companion object {
        // Error states have different UI implications
        fun isErrorState(state: AppState): Boolean {
            return (state in arrayOf(DeviceUnsupported, AdbConnectionError))
        }
        fun distinctSteps(): Int {
            return entries
                .map { it.step }
                .filterNot { it == EXCLUDED_STEP }
                .toSet()
                .size
        }
    }
}
