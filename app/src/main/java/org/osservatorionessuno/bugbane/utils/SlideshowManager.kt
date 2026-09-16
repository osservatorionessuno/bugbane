package org.osservatorionessuno.bugbane.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osservatorionessuno.bugbane.MainActivity
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault


/** Chosen once on the role slide; only clearing the app data changes it. */
enum class Role { USER, ANALYST }

object SlideshowManager {
    private lateinit var appContext: Context
    data class AppProgress(
        val hasCompletedOnboarding: Boolean,
        val hasSeenWelcomeScreen: Boolean,
        val hasAckedAdbWarning: Boolean,
        val hasAckedBetaWarning: Boolean,
        val hasAcquisitionProtection: Boolean,
        val role: Role? = null,
    ) {
        val isAnalyst: Boolean get() = role == Role.ANALYST
    }
    private var _appProgress: MutableStateFlow<AppProgress> = MutableStateFlow(AppProgress(false, false, false, false, true))
    var appProgress: StateFlow<AppProgress> = _appProgress.asStateFlow()
    private lateinit var sharedPrefs: SharedPreferences
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                Keys.KEY_HAS_SEEN_HOMEPAGE, Keys.KEY_HAS_SEEN_WELCOME_SCREEN, Keys.KEY_ACKED_ADB_WARNING, Keys.KEY_BETA_WARNING_ACKNOWLEDGED, Keys.KEY_ROLE -> {
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
            // Alias state survives a data clear.
            applyLauncherIcon(role() == Role.ANALYST)
        }
    }

    /**
     * Enable the launcher alias for the role. Disabling an alias removes the task rooted
     * on it, so a new task is started from the other alias first (the app restarts).
     */
    private fun applyLauncherIcon(analyst: Boolean) {
        val pm = appContext.packageManager
        // Namespace, not the flavor's application id.
        val namespace = MainActivity::class.java.name.removeSuffix("MainActivity")
        val wanted = ComponentName(appContext, namespace + if (analyst) "LauncherAnalyst" else "Launcher")
        val other = ComponentName(appContext, namespace + if (analyst) "Launcher" else "LauncherAnalyst")
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(appContext.packageName)
        val enabled = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.name }
        if (enabled == listOf(wanted.className)) return
        Log.i("SlideshowManager", "switching launcher icon to ${wanted.shortClassName}")
        pm.setComponentEnabledSetting(wanted, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        appContext.startActivity(
            Intent.makeMainActivity(wanted)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        pm.setComponentEnabledSetting(other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    fun registerListener() {
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    fun checkState() {
        // Installs onboarded before the role slide were users.
        val role = role() ?: if (hasSeenHomepage()) Role.USER.also { setRole(it) } else null
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
            hasAcquisitionProtection = if (role == Role.ANALYST) {
                analystProtectionSatisfied()
            } else {
                AcquisitionIdentityVault.isInitialized(appContext) ||
                    (!AcquisitionIdentityVault.onboardingUsesBiometric(appContext) &&
                        !AcquisitionIdentityVault.isRecoveryPending(appContext))
            },
            role = role,
        )
        if (_appProgress.value != newState) {
            Log.d("SlideshowManager", "update appprogress (onboardcomplete=${newState.hasCompletedOnboarding}, welcomecomplete=${newState.hasSeenWelcomeScreen}, protection=${newState.hasAcquisitionProtection})")
            _appProgress.value = newState
        }
    }

    /** Both factors, or the password alone where the keystore can't gate a key. */
    private fun analystProtectionSatisfied(): Boolean {
        val tier = AcquisitionIdentityVault.tier(appContext) ?: return false
        if (!tier.usesPassphrase) return false
        return tier.usesBiometric || !AcquisitionIdentityVault.hasHardwareKeystore()
    }

    fun role(): Role? = sharedPrefs.getString(Keys.KEY_ROLE, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }

    fun setRole(role: Role) {
        if (role() != null) return
        sharedPrefs.edit { putString(Keys.KEY_ROLE, role.name) }
        applyLauncherIcon(role == Role.ANALYST)
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

    const val KEY_ROLE = "role"
}