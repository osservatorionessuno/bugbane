package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object SlideshowManager {
    private lateinit var appContext: Context
    data class AppProgress(val hasCompletedOnboarding: Boolean, val hasSeenWelcomeScreen: Boolean)
    private var _appProgress: MutableStateFlow<AppProgress> = MutableStateFlow(AppProgress(false, false))
    var appProgress: StateFlow<AppProgress> = _appProgress.asStateFlow()
    private lateinit var sharedPrefs: SharedPreferences
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                Keys.KEY_HAS_SEEN_HOMEPAGE, Keys.KEY_HAS_SEEN_WELCOME_SCREEN -> {
                    checkState()
                }
            }
        }

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            sharedPrefs =
                appContext.getSharedPreferences(Keys.PREFS_NAME, Context.MODE_PRIVATE)
            // initial values
            checkState()
            registerListener()
        }
    }


    fun registerListener() {
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    fun checkState() {
        val newState = AppProgress(
            hasCompletedOnboarding = sharedPrefs.getBoolean(
                Keys.KEY_HAS_SEEN_HOMEPAGE,
                false
            ),
            hasSeenWelcomeScreen = sharedPrefs.getBoolean(
                Keys.KEY_HAS_SEEN_WELCOME_SCREEN,
                false
            )
        )
        if (_appProgress.value != newState) {
            Log.d("SlideshowManager", "update appprogress (onboardcomplete=${newState.hasCompletedOnboarding}, welcomecomplete=${newState.hasSeenWelcomeScreen})")
            _appProgress.value = newState
        }
    }

    fun hasSeenHomepage(): Boolean {
        return sharedPrefs.getBoolean(Keys.KEY_HAS_SEEN_HOMEPAGE, false)
    }

    fun markHomepageAsSeen() {
        sharedPrefs.edit { putBoolean(Keys.KEY_HAS_SEEN_HOMEPAGE, true) }
    }

    fun resetHomepageState(force: Boolean = false) {
        if (!hasSeenHomepage() || force) {
            sharedPrefs.edit { putBoolean(Keys.KEY_HAS_SEEN_HOMEPAGE, false) }
        }
    }

    fun canSkipWelcomeScreen(): Boolean {
        return sharedPrefs.getBoolean(Keys.KEY_HAS_SEEN_WELCOME_SCREEN, false)
    }

    fun setHasSeenWelcomeScreen() {
        sharedPrefs.edit { putBoolean(Keys.KEY_HAS_SEEN_WELCOME_SCREEN, true) }
    }


    fun cleanup() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)

    }
}

object Keys {
    const val PREFS_NAME = "app_prefs"

    // Skips the logo/splashscreen page after the first onboarding flow
    const val KEY_HAS_SEEN_WELCOME_SCREEN = "has_seen_welcome_screen"

    // Skips the "Get Started" page after the first onboarding flow
    const val KEY_HAS_SEEN_HOMEPAGE = "has_seen_homepage"
}