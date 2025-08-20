package org.osservatorionessuno.bugbane.components

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osservatorionessuno.bugbane.R

data class SlideshowPageData(
    val title: String,
    val description: String,
    val icon: ImageVector? = null,
    val buttonText: String? = null,
    val onClick: (() -> Unit)? = null,
    val shouldSkip: (() -> Boolean)? = null,
    val shouldContinue: Boolean = true
)

@Composable
fun SlideshowPage(page: SlideshowPageData) {
    val context = LocalContext.current
    
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