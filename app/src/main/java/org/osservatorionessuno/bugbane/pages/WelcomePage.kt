package org.osservatorionessuno.bugbane.pages

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.SlideshowManager

object WelcomePage {
    @Composable
    fun create(onNext: () -> Unit): SlideshowPageData {
        val context = LocalContext.current

        fun handleNext(context: Context) {
            onNext()
        }

        return SlideshowPageData(
            title = stringResource(R.string.slideshow_welcome_title),
            description = stringResource(R.string.slideshow_welcome_description),
            icon = ImageVector.vectorResource(id = R.drawable.ic_bugbane_zoom),
            onClick = { handleNext(context) },
            shouldSkip = { SlideshowManager.hasSeenHomepage(context) }
        )
    }
} 