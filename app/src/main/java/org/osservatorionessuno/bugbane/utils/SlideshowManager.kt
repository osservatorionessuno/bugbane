package org.osservatorionessuno.bugbane.utils

import android.content.Context

object SlideshowManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_HAS_SEEN_HOMEPAGE = "has_seen_homepage"
    
    fun hasSeenHomepage(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_SEEN_HOMEPAGE, false)
    }
    
    fun markHomepageAsSeen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_SEEN_HOMEPAGE, true).apply()
    }
    
    fun resetHomepageState(context: Context, force: Boolean = false) {
        if (!hasSeenHomepage(context) || force) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_HAS_SEEN_HOMEPAGE, false).apply()
        }
    }   
} 