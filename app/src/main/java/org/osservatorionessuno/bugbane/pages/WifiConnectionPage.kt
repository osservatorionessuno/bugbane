package org.osservatorionessuno.bugbane.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.ConfigurationManager

object WifiConnectionPage {
    @Composable
    fun create(onNext: () -> Unit): SlideshowPageData {
        val context = LocalContext.current

        fun shouldSkip(): Boolean {
            return ConfigurationManager.isConnectedToWifi(context)
        }

        fun handleNext() {
            ConfigurationManager.openWifiSettings(context)
        }

        return SlideshowPageData(
            title = stringResource(R.string.slideshow_wifi_title),
            description = stringResource(R.string.slideshow_wifi_description),
            icon = Icons.Filled.Wifi,
            buttonText = stringResource(R.string.slideshow_wifi_button),
            onClick = { handleNext() },
            shouldSkip = { shouldSkip() }
        )
    }
}