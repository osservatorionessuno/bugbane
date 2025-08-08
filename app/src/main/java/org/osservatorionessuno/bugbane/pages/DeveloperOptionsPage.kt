package org.osservatorionessuno.bugbane.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle

import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.ConfigurationManager

object DeveloperOptionsPage {
    @Composable
    fun create(onNext: () -> Unit): SlideshowPageData {
        val context = LocalContext.current

        fun shouldSkip(): Boolean {
            return ConfigurationManager.isDeveloperOptionsEnabled(context)
        }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

        // Listen for when we come back to this screen
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && shouldSkip()) {
                    onNext()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        fun handleNext() {
            // User is returning to the page, so we don't need to open the settings
            if (shouldSkip()) {
                onNext()
                return
            }

            ConfigurationManager.openDeviceSettings(context)
        }

        return SlideshowPageData(
            title = stringResource(R.string.slideshow_developer_title),
            description = stringResource(R.string.slideshow_developer_description),
            icon = Icons.Default.Settings,
            buttonText = stringResource(R.string.slideshow_developer_button),
            onClick = { handleNext() },
            shouldSkip = { shouldSkip() }
        )
    }
} 