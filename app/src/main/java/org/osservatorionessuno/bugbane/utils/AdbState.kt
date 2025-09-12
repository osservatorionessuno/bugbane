package org.osservatorionessuno.bugbane.utils

// AdbStates represent the adb connection status.
// AdbManager emits a flow of AdbStates. Other components can listen for
// errorStates or successStates, then recalculate their own state if needed
// (see ConfigurationViewModel).
enum class AdbState(val index: Int) {
    RequisitesMissing(0), // AppStates < AppState.NeedWirelessDebuggingAndPair
    ReadyToPair(1),
    ErrorPair(2),
    ReadyToConnect(3),
    ErrorConnect(4),
    ConnectedIdle(5), // AppStates >= AppState.AdbConnected
    ConnectedAcquiring(6),
    ErrorAcquisition(7);


    companion object {

        // An error requiring user interaction. Right now we don't differentiate
        // between pairing and connection errors, we just ask user to re-pair
        fun errorStates(): List<AdbState> = listOf(
            RequisitesMissing,
            ErrorPair,
            ErrorAcquisition,
        )

        // A successful ADB connection state
        fun successStates(): List<AdbState> = listOf(
            ConnectedIdle,
            ConnectedAcquiring,
        )
    }
}