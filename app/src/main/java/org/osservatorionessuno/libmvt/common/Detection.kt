package org.osservatorionessuno.libmvt.common

data class Detection(
        val type: IndicatorType,
        val ioc: String,
        val context: String
)
