package org.osservatorionessuno.bugbane.components

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.AppState
import org.osservatorionessuno.bugbane.utils.ConfigurationManager

data class SlideshowPageData(
    val title: String,
    val description: String,
    val icon: ImageVector? = null,
    val buttonText: String? = null,
    val onClick: (() -> Unit)? = null, // todo launching activity
//    val shouldSkip: (() -> Boolean)? = null,
    val shouldContinue: Boolean = true
)

@Composable
fun getSlideshowScreenContent(state: AppState): SlideshowPageData {
    when (state) {
        AppState.NeedWelcomeScreen -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_welcome_title),
            description = stringResource(R.string.slideshow_welcome_description),
            icon = ImageVector.Companion.vectorResource(R.drawable.ic_bugbane_zoom),
            buttonText = "",
        )
        AppState.NeedWifi -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_wifi_title),
            description = stringResource(R.string.slideshow_wifi_description),
            icon = Icons.Filled.Wifi,
            buttonText = stringResource(R.string.slideshow_wifi_button),
        )
        AppState.NeedNotificationConfiguration -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_notification_title),
            description = stringResource(R.string.slideshow_notification_description),
            icon = Icons.Default.Notifications,
            buttonText = stringResource(R.string.slideshow_notification_button),
        )
        AppState.NeedDeveloperOptions -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_developer_title),
            description = stringResource(R.string.slideshow_developer_description),
            icon = Icons.Default.Settings,
            buttonText = stringResource(R.string.slideshow_developer_button),
        )
        AppState.NeedWirelessDebugging -> return SlideshowPageData(title = stringResource(R.string.slideshow_wireless_title),
            description = stringResource(R.string.slideshow_wireless_description),
            icon = Icons.Filled.Build,
            buttonText = stringResource(R.string.slideshow_wireless_button),
        )
        // TODO
//        ConfigurationState.NeedAdbPairingService -> SlideshowScreenContent(title = stringResource(R.string.slideshow_wireless_title),
//            description = stringResource(R.string.slideshow_wireless_description),
//            icon = Icons.Filled.Build,
//            buttonText = "Pairing in progress..."
//        )
        AppState.AdbConnected -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_ready_title),
            description = stringResource(R.string.slideshow_ready_description),
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            buttonText = stringResource(R.string.slideshow_ready_button)
        )
        // TODO: probably pairing, but checks can be better
        else -> return getSlideshowScreenContent(AppState.NeedAdbPairingService) //TODO
    }
    // Unreachable?
}


@Composable
fun SlideshowPage(state: AppState, onClickContinue: (() -> Unit)?) {
    val context = LocalContext.current
    val page = getSlideshowScreenContent(state)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        page.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .padding(bottom = 24.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        }

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = if (page.shouldContinue) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        page.onClick?.let { onClick ->
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (page.shouldContinue) {
                        onClick()
                    } else {
                        (context as? Activity)?.let { activity ->
                            //activity.moveTaskToBack(true)
                            activity.finishAffinity()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (page.shouldContinue) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                    contentColor = if (page.shouldContinue) {
                        androidx.compose.ui.graphics.Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
            ) {
                Text(
                    text = if (page.shouldContinue) {
                        page.buttonText ?: stringResource(R.string.slideshow_welcome_button)
                    } else {
                        "Exit"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
} 