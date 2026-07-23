package org.osservatorionessuno.bugbane.utils

// AppStates represent the high-level device configuration and permissions status;
// state requisites are defined in the ViewModel (see ConfigurationViewModel).
// An AppState requires user interaction to change to a different state.
private const val EXCLUDED_STEP = 999
enum class AppState(val step: Int) {
    NeedWelcomeScreen(0),
    // Ghost states share the next real step's dot, so conditional pages that most
    // users never see add no extra progress dot.
    // Beta builds only, right after the welcome screen.
    NeedBetaWarning(1),
    // Only on devices still exposed to the wireless-ADB bypass (CVE-2026-0073).
    NeedAdbVulnerabilityWarning(1),
    NeedNotificationPermission(1),
    DeviceUnsupported(1), // Alternative to step 1: device isn't compatible
    // Lock the acquisition encryption key to the device (one fingerprint). Only
    // shown on devices with a hardware keystore and a secure lock; otherwise the
    // password is asked after the first acquisition instead.
    NeedAcquisitionProtection(2),
    NeedWifi(3),
    NeedDeveloperOptions(4),

    // Step 5: ADB/Wireless ADB/Pairing
    NeedWirelessDebuggingAndPair(5),
    NeedWirelessDebugging(5),

    // Step 6: ADB connection attempt
    AdbConnecting(6),
    AdbConnectedFinishOnboarding(6),
    AdbConnected(6),
    TryAutoConnect(6),
    AdbConnectionError(6),

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
