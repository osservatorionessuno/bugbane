package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault


object SlideshowManager {
    private lateinit var appContext: Context
    data class AppProgress(
        val hasCompletedOnboarding: Boolean,
        val hasSeenWelcomeScreen: Boolean,
        val hasAckedAdbWarning: Boolean,
        val hasAckedBetaWarning: Boolean,
        val hasAcquisitionProtection: Boolean,
    )
    private var _appProgress: MutableStateFlow<AppProgress> = MutableStateFlow(AppProgress(false, false, false, false, true))
    var appProgress: StateFlow<AppProgress> = _appProgress.asStateFlow()
    private lateinit var sharedPrefs: SharedPreferences
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                Keys.KEY_HAS_SEEN_HOMEPAGE, Keys.KEY_HAS_SEEN_WELCOME_SCREEN, Keys.KEY_ACKED_ADB_WARNING, Keys.KEY_BETA_WARNING_ACKNOWLEDGED -> {
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
            ),
            hasAckedAdbWarning = sharedPrefs.getBoolean(
                Keys.KEY_ACKED_ADB_WARNING,
                false
            ),
            hasAckedBetaWarning = sharedPrefs.getBoolean(
                Keys.KEY_BETA_WARNING_ACKNOWLEDGED,
                false
            ),
            // Onboarding only creates the identity on devices that use the
            // fingerprint gate; others set a password after the first acquisition,
            // so the onboarding step is considered satisfied for them. The identity
            // files are the source of truth (not a preference). A pending recovery
            // (a prior identity was invalidated by lock removal) forces the step even
            // on a lock-less device, so the user re-establishes protection now.
            hasAcquisitionProtection = AcquisitionIdentityVault.isInitialized(appContext) ||
                (!AcquisitionIdentityVault.onboardingUsesBiometric(appContext) &&
                    !AcquisitionIdentityVault.isRecoveryPending(appContext)),
        )
        if (_appProgress.value != newState) {
            Log.d("SlideshowManager", "update appprogress (onboardcomplete=${newState.hasCompletedOnboarding}, welcomecomplete=${newState.hasSeenWelcomeScreen}, protection=${newState.hasAcquisitionProtection})")
            _appProgress.value = newState
        }
    }

    fun setAckedAdbWarning() {
        sharedPrefs.edit { putBoolean(Keys.KEY_ACKED_ADB_WARNING, true) }
    }

    fun setAckedBetaWarning() {
        sharedPrefs.edit { putBoolean(Keys.KEY_BETA_WARNING_ACKNOWLEDGED, true) }
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

    // Set once the user has acknowledged the beta warning slide (beta builds only)
    const val KEY_BETA_WARNING_ACKNOWLEDGED = "beta_warning_acknowledged"

    // Set once the user has acknowledged the ADB vulnerability warning
    const val KEY_ACKED_ADB_WARNING = "acked_adb_warning"
}