package org.osservatorionessuno.libmvt.common

enum class AlertLevel {
    LOG, // Something to report but not a real alert
    INFORMATIONAL,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class Detection(
        val level: AlertLevel,
        val type: IndicatorType,
        val ioc: String,
        val context: String
) {
    override fun toString(): String {
        return "Detection(level=$level, type=$type, ioc='$ioc', context='$context')"
    }
}
