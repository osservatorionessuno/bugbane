package org.osservatorionessuno.bugbane.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

object SlideshowManager {
    private const val PREFS_NAME = "app_prefs"

    // Skips the logo/splashscreen page after the first onboarding flow
    private const val KEY_HAS_SEEN_WELCOME_SCREEN = "has_seen_welcome_screen"

    // Skips the "Get Started" page after the first onboarding flow
    private const val KEY_HAS_SEEN_HOMEPAGE = "has_seen_homepage"

    fun hasSeenHomepage(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_SEEN_HOMEPAGE, false)
    }
    
    private fun markHomepageAsSeen(context: Context) {
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

    private fun setHasSeenWelcomeScreen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_SEEN_WELCOME_SCREEN, true).apply()
    }

    // Desired "next" state for permissions flow slideshow handled here.
    // There are two types of slides: informational/consent slides (shown once) and
    // permissions slides (launch an activity via intent, shown as needed).
    fun handleOnContinue(context: Context, state: AppState, viewModel: ConfigurationViewModel) : Unit {
        // TODO: startActivityForResult?
        getIntentForAppState(context, state)?.let {it -> (context as? Activity)?.startActivity(it) } ?:
        when (state) {
            is AppState.DeviceUnsupported -> {
                (context as? Activity)?.finishAffinity()
            }
            is AppState.NeedWelcomeScreen -> {
                // Clicked "I understand," nothing else to do
                setHasSeenWelcomeScreen(context)
                viewModel.checkUpdateState()
            }
            else -> { // Should be unreachable
                Log.e(context.applicationContext.packageName, "$state not handled")
            }
        }
    }
    private fun getIntentForAppState(context: Context, state: AppState): Intent? {
        return when (state) {
            AppState.NeedWifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
            AppState.NeedNotificationConfiguration -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            AppState.NeedDeveloperOptions -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            AppState.NeedWirelessDebugging -> wirelessDebuggingIntent()
            else -> null
        }
    }
    private fun wirelessDebuggingIntent(): Intent {
        // Open wireless debugging settings
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            val bundle = Bundle().apply {
                putString(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            }
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        }
        return settingsIntent
    }
}
