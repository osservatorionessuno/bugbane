package org.osservatorionessuno.bugbane.components

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.osservatorionessuno.bugbane.R
import kotlin.system.exitProcess

private const val BETA_COUNTDOWN_SECONDS = 10

/**
 * Beta gate, shown as a slideshow page on beta builds right after the welcome
 * screen. The continue button unlocks after a countdown; the deadline is a
 * wall-clock timestamp kept in rememberSaveable so rotation resumes it.
 * Styled to match [SlideshowPage].
 *
 * [onAcknowledge] proceeds with onboarding (the user accepts the risk).
 */
@Composable
fun BetaWarningPage(onAcknowledge: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val deadlineMs = rememberSaveable {
        System.currentTimeMillis() + BETA_COUNTDOWN_SECONDS * 1000L
    }
    var remainingSec by remember {
        mutableIntStateOf(
            ((deadlineMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            val leftMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            remainingSec = ((leftMs + 999) / 1000).toInt()
            if (leftMs == 0L) break
            delay(leftMs.coerceAtMost(1000L))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 24.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        Text(
            text = stringResource(R.string.beta_warning_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.beta_warning_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        val githubUrl = stringResource(R.string.beta_warning_github_url)
        Text(
            text = githubUrl.removePrefix("https://"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable { uriHandler.openUri(githubUrl) }
                .padding(4.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAcknowledge,
            enabled = remainingSec == 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (remainingSec == 0) stringResource(R.string.beta_warning_understand) else "$remainingSec",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
        }

        OutlinedButton(
            onClick = {
                (context as? Activity)?.finishAffinity()
                exitProcess(0)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.beta_warning_quit))
        }
    }
}
