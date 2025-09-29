package org.osservatorionessuno.bugbane.utils

// AdbStates represent the adb connection status.
// AdbManager emits a flow of AdbStates. Other components can listen for
// errorStates or successStates, then recalculate their own state if needed
// (see ConfigurationViewModel).
enum class AdbState(val index: Int) {
    RequisitesMissing(0), // AppStates < AppState.NeedWirelessDebugging
    Ready(1),
    Connecting(2),
    ConnectedIdle(3), // AppStates >= AppState.AdbConnected
    ConnectedAcquiring(4),
    Cancelled(5),
    ErrorConnect(6),
    ErrorAcquisition(7),
    Initial(8); // Initializing state

    companion object {

        // An error requiring user interaction. Right now we don't differentiate
        // between pairing and connection errors, we just ask user to re-pair
        fun errorStates(): List<AdbState> = listOf(
            ErrorAcquisition,
            ErrorConnect
        )

        // A successful ADB connection state
        fun successStates(): List<AdbState> = listOf(
            ConnectedIdle,
            ConnectedAcquiring,
        )
    }
}