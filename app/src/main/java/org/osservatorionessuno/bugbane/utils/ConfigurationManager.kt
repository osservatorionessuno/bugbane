package org.osservatorionessuno.bugbane.utils

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ConfigurationManager"

/**
 * Observe device configuration (developer mode, ADB, wireless debug) and emit values as StateFlow.
 * Also observes state of notification permissions, but those can't be subscribed to except in
 * a Composable function (with rememberPermissionState), so expose a handler to invoke from
 * Composable.
 *
 * Content observers observe in a coroutine off the main thread, and emit updates on the main
 * thread so that viewmodel can react.
 *
 * Requires initialization and cleanup due to registering ContentObservers.
 */
object ConfigurationManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentResolver get() = appContext.contentResolver
    private var developerOptsObserver: ContentObserver? = null
    private var wirelessDebugObserver: ContentObserver? = null
    private var adbObserver: ContentObserver? = null
    private val _developerOptionsEnabled = MutableStateFlow(false)
    val developerOptionsEnabled: StateFlow<Boolean> = _developerOptionsEnabled.asStateFlow()
    private val _wirelessDebuggingEnabled = MutableStateFlow(false)
    val wirelessDebuggingEnabled: StateFlow<Boolean> = _wirelessDebuggingEnabled.asStateFlow()
    private val _adbEnabled = MutableStateFlow(false)
    val adbEnabled: StateFlow<Boolean> = _adbEnabled.asStateFlow()
    private val _notificationsEnabled = MutableStateFlow(false)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            registerObservers()
            // First set of values
            checkAll()
        } else {
            Log.e(TAG, "Failed to initialize ConfigurationManager")
            // TODO: raise?
        }
    }

    /**
     * Register ContentObservers for specific system settings/preferences
     * (https://developer.android.com/reference/kotlin/android/database/ContentObserver)
     */
    private fun registerObservers() {
        developerOptsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = developerOptionsCheck()
        }.also {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                false,
                it
            )
        }

        wirelessDebugObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = wirelessDebugCheck()
        }.also {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"),
                false,
                it
            )
        }

        adbObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = adbObserverCheck()
        }.also {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false,
                it
            )
        }
    }

    // Run all checks
    fun checkAll() {
        developerOptionsCheck()
        wirelessDebugCheck()
        notificationsCheck()
        adbObserverCheck()
    }

    private fun developerOptionsCheck() {
        scope.launch {
            val enabled = Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
            _developerOptionsEnabled.emit(enabled)
        }
    }

    // A bit dirty:
    // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/provider/Settings.java;l=13465?q=adb_wifi_enabled&ss=android%2Fplatform%2Fsuperproject%2Fmain
    private fun wirelessDebugCheck() {
        scope.launch {
            val enabled = Settings.Global.getInt(
                contentResolver,
                "adb_wifi_enabled", 0
            ) == 1
            _wirelessDebuggingEnabled.emit(enabled)
        }
    }

    private fun adbObserverCheck() {
        scope.launch {
            val enabled =
                Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            _adbEnabled.emit(enabled)
        }
    }

    fun notificationsCheck() {
        val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        _notificationsEnabled.value = enabled
    }

    fun openDeveloperOptions(context: Context) {
        // Open the developer options settings
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }

    fun cleanup() {
        developerOptsObserver?.let {
            contentResolver.unregisterContentObserver(it)
            developerOptsObserver = null
        }

        wirelessDebugObserver?.let {
            contentResolver.unregisterContentObserver(it)
            wirelessDebugObserver = null
        }

        adbObserver?.let {
            contentResolver.unregisterContentObserver(it)
            adbObserver = null
        }

        scope.cancel()
    }

    // To make notifications permission also emit StateFLow, a Composable needs to
    // use rememberPermissionsState and invoke this handler when the value changes.
    // (See SlideshowScreen)
    fun setHasNotificationPermission(granted: Boolean) {
        _notificationsEnabled.value = granted
    }
}
