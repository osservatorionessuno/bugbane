package org.osservatorionessuno.bugbane.pages

import android.os.Build
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.AdbPairingService
import org.osservatorionessuno.bugbane.utils.AdbPairingResultReceiver
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.R
import android.os.Bundle

object WirelessDebuggingPage {
    @Composable
    fun create(onNext: () -> Unit): SlideshowPageData {
        val context = LocalContext.current
        var isPairingInProgress by remember { mutableStateOf(false) }
        
        // Create BroadcastReceiver for pairing results
        val pairingReceiver = remember {
            AdbPairingResultReceiver(
                onSuccess = {
                    isPairingInProgress = false
                    onNext()
                },
                onFailure = { errorMessage ->
                    isPairingInProgress = false
                    // You could show an error message here if needed
                }
            )
        }

        DisposableEffect(context) {
            val filter = IntentFilter(AdbPairingService.ACTION_PAIRING_RESULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // This broadcast is internal to the app, so keep it private
                context.registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // Pre-Android 13: old two-argument API
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(pairingReceiver, filter)
            }

            onDispose {
                try {
                    context.unregisterReceiver(pairingReceiver)
                } catch (_: Exception) { }
            }
        }


        fun handleNext() {
            ConfigurationManager.openWirelessDebugging(context)

            // Start ADB pairing service
            val pairingIntent = AdbPairingService.startIntent(context)
            try {
                context.startForegroundService(pairingIntent)
                isPairingInProgress = true
            } catch (ignored: Throwable) {
                context.startService(pairingIntent)
                isPairingInProgress = true
            }
            
            // Don't call onNext() here - wait for pairing result
        }
        
        return SlideshowPageData(
            title = stringResource(R.string.slideshow_wireless_title),
            description = stringResource(R.string.slideshow_wireless_description),
            icon = Icons.Filled.Build,
            buttonText = if (isPairingInProgress) "Pairing..." else stringResource(R.string.slideshow_wireless_button),
            onClick = { handleNext() },
            shouldSkip = { false }
        )
    }
} 