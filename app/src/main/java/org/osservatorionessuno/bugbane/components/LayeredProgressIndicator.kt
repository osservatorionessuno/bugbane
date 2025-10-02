package org.osservatorionessuno.bugbane.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LayeredProgressIndicator(
    totalModules: Int = 0,
    completedModules: Int = 0,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    continuousColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            strokeWidth = strokeWidth,
            color = continuousColor.copy(alpha = 0.4f),
            trackColor = backgroundColor.copy(alpha = 0.4f)
        )
        
        if (totalModules > 0) {
            CircularProgressIndicator(
                progress = { (completedModules / totalModules.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.size(size),
                strokeWidth = strokeWidth,
                color = progressColor,
                trackColor = Color.Transparent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("$completedModules / $totalModules")
        }
    }
}
