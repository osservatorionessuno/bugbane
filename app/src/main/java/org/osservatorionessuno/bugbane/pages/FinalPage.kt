package org.osservatorionessuno.bugbane.pages

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.SlideshowPageData
import org.osservatorionessuno.bugbane.utils.SlideshowManager

object FinalPage {
    @Composable
    fun create(onComplete: () -> Unit): SlideshowPageData {
        val context = LocalContext.current
        
        fun handleNext(context: Context) {
            SlideshowManager.markHomepageAsSeen(context)
            onComplete()
        }
        return SlideshowPageData(
            title = stringResource(R.string.slideshow_ready_title),
            description = stringResource(R.string.slideshow_ready_description),
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            buttonText = stringResource(R.string.slideshow_ready_button),
            onClick = { handleNext(context) },
            shouldSkip = { SlideshowManager.hasSeenHomepage(context) }
        )
    }
} 