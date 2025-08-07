package org.osservatorionessuno.bugbane.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.R

object NotificationPermissionPage {
    @Composable
    fun create(onNext: () -> Unit): SlideshowPageData {
        val context = LocalContext.current
        
        // Prepare a permission launcher to request the permission
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onNext()
            }
        }

        fun shouldSkip(): Boolean {
            return ConfigurationManager.isNotificationPermissionGranted(context)
        }

        fun handleNext() {
            Log.d("NotificationPermissionPage", "handleNext called")
            // Request the permission when the button is clicked
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return SlideshowPageData(
            title = stringResource(R.string.slideshow_notification_title),
            description = stringResource(R.string.slideshow_notification_description),
            icon = Icons.Default.Notifications,
            buttonText = stringResource(R.string.slideshow_notification_button),
            onClick = { handleNext() },
            shouldSkip = { shouldSkip() }
        )
    }
} 