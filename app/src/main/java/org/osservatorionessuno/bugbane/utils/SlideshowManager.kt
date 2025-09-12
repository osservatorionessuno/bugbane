package org.osservatorionessuno.bugbane.utils

import android.content.Context

// Manages app-specific onboarding preferences in the onboarding slideshow
object SlideshowManager {
    const val PREFS_NAME = "app_prefs"

    // Skips the logo/splashscreen page after the first onboarding flow
    const val KEY_HAS_SEEN_WELCOME_SCREEN = "has_seen_welcome_screen"

    // Skips the "Get Started" page after the first onboarding flow
    const val KEY_HAS_SEEN_HOMEPAGE = "has_seen_homepage"

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
    
    fun canSkipWelcomeScreen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_SEEN_WELCOME_SCREEN, false)
    }

    fun setHasSeenWelcomeScreen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_SEEN_WELCOME_SCREEN, true).apply()
    }
}