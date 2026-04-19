package org.osservatorionessuno.bugbane.utils

import android.util.Log
import org.osservatorionessuno.bugbane.BuildConfig
import org.osservatorionessuno.libmvt.common.logging.LibmvtLogger
import org.osservatorionessuno.libmvt.common.logging.LogUtils

class AndroidLibmvtLogger : LibmvtLogger {

    private fun tagOrDefault(tag: String?): String =
        if (!tag.isNullOrBlank()) tag else "libmvt"

    override fun d(tag: String?, msg: String?) {
        if (msg == null) return
        Log.d(tagOrDefault(tag), msg)
    }

    override fun i(tag: String?, msg: String?) {
        if (msg == null) return
        Log.i(tagOrDefault(tag), msg)
    }

    override fun w(tag: String?, msg: String?) {
        if (msg == null) return
        Log.w(tagOrDefault(tag), msg)
    }

    override fun e(tag: String?, msg: String?, t: Throwable?) {
        if (msg == null && t == null) return
        Log.e(tagOrDefault(tag), msg ?: "", t)
    }
}

fun initLibmvtLogging() {
    LogUtils.setDebugEnabled(BuildConfig.DEBUG)
    LogUtils.setLogger(AndroidLibmvtLogger())
}