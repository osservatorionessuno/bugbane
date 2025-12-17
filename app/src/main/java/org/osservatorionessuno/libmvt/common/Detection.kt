package org.osservatorionessuno.libmvt.common

enum class AlertLevel(val level: Int) {
    LOG(5), // Something to report but not a real alert
    INFO(4),
    LOW(3),
    MEDIUM(2),
    HIGH(1),
    CRITICAL(0)
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
