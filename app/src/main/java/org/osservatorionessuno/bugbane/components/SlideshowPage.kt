package org.osservatorionessuno.bugbane.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    val shouldSkip: (() -> Boolean)? = null
)

@Composable
fun SlideshowPage(page: SlideshowPageData) {
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        page.onClick?.let { onClick ->
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text(
                    text = page.buttonText ?: stringResource(R.string.slideshow_welcome_button),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
} 