package org.osservatorionessuno.bugbane.utils

import android.content.Context
import org.osservatorionessuno.libmvt.common.StringResolver


/**
 * A simple string resolver for Android.
 * This resolver is used to resolve libmvt strings from the Android resources.
 */
class AndroidStringResolver(
    private val context: Context
) : StringResolver {

    override fun get(name: String): String {
        val resId = context.resources.getIdentifier(name, "string", context.packageName)
        return if (resId != 0) {
            context.getString(resId)
        } else {
            ""
        }
    }
}