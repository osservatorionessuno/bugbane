package org.osservatorionessuno.bugbane.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.AppState
import androidx.compose.material3.MaterialTheme

data class SlideshowPageData(
    val title: String,
    val description: String,
    val icon: ImageVector? = null,
    val buttonText: String? = null
)

@Composable
fun getSlideshowScreenContent(state: AppState): SlideshowPageData {
    when (state) {
        AppState.DeviceUnsupported -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_welcome_title),
            description = stringResource(R.string.slideshow_unsupported_version),
            icon = ImageVector.Companion.vectorResource(R.drawable.ic_bugbane_zoom),
            buttonText = stringResource(R.string.slideshow_button_exit),
        )
        AppState.NeedWelcomeScreen -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_welcome_title),
            description = stringResource(R.string.slideshow_welcome_description),
            icon = ImageVector.Companion.vectorResource(R.drawable.ic_bugbane_zoom),
            buttonText = stringResource(R.string.slideshow_welcome_button),
        )
        AppState.NeedWifi -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_wifi_title),
            description = stringResource(R.string.slideshow_wifi_description),
            icon = Icons.Filled.Wifi,
            buttonText = stringResource(R.string.slideshow_wifi_button),
        )
        AppState.NeedNotificationPermission -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_notification_title),
            description = stringResource(R.string.slideshow_notification_description),
            icon = Icons.Default.Notifications,
            buttonText = stringResource(R.string.slideshow_notification_button),
        )
        AppState.NeedDeveloperOptions -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_developer_title),
            description = stringResource(R.string.slideshow_developer_description),
            icon = Icons.Default.Settings,
            buttonText = stringResource(R.string.slideshow_button_text_enable),
        )
        AppState.AdbConnectedFinishOnboarding -> return SlideshowPageData(
            title = stringResource(R.string.slideshow_ready_title),
            description = stringResource(R.string.slideshow_ready_description),
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            buttonText = stringResource(R.string.slideshow_ready_button)
        )
        AppState.NeedWirelessDebuggingAndPair -> return SlideshowPageData(title = stringResource(R.string.slideshow_wireless_and_pair_title),
            description = stringResource(R.string.slideshow_wireless_and_pair_description),
            icon = Icons.Filled.Build,
            buttonText = stringResource(R.string.slideshow_wireless_and_pair_button),
        )
        AppState.TryAutoConnect, AppState.AdbConnecting, -> return SlideshowPageData(title = stringResource(R.string.notification_channel_adb_pairing), //todo
            description = stringResource(R.string.notification_adb_pairing_working_title),
            icon = Icons.Filled.Build,
            buttonText = "please wait", //todo
        )
        AppState.NeedWirelessDebugging -> return SlideshowPageData(title = stringResource(R.string.slideshow_wireless_title),
            description = stringResource(R.string.slideshow_wireless_description),
            icon = Icons.Filled.Build,
            buttonText = stringResource(R.string.slideshow_button_text_enable),
        )
        // Should be unreachable
        else ->  return SlideshowPageData(title = stringResource(R.string.slideshow_wireless_and_pair_title),
            description = stringResource(R.string.slideshow_wireless_and_pair_description),
            icon = Icons.Filled.Build,
            buttonText = stringResource(R.string.slideshow_wireless_and_pair_button),
        )
    }
}


@Composable
fun SlideshowPage(state: AppState, onClickContinue: (() -> Unit)) {
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
            color = if (!AppState.isErrorState(state)) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            enabled = (state != AppState.AdbConnecting && state != AppState.TryAutoConnect),
            onClick = onClickContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!AppState.isErrorState(state)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
                contentColor = if (!AppState.isErrorState(state)) {
                    androidx.compose.ui.graphics.Color.White
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
        ) {
            page.buttonText?.let { buttonText ->
                Text(text = buttonText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}