package org.osservatorionessuno.bugbane.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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