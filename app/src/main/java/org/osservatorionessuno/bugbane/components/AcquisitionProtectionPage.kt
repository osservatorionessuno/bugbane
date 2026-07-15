package org.osservatorionessuno.bugbane.components

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault

private const val TAG = "AcquisitionProtection"

/**
 * Onboarding step shown only on devices with a hardware keystore and a secure
 * lock: one biometric/credential confirmation locks the acquisition encryption
 * key to the device (StrongBox when available, otherwise the TEE). Styled to
 * match [SlideshowPage]. Calls [onProtected] once the identity exists.
 */
@Composable
fun AcquisitionProtectionPage(onProtected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun protect() {
        working = true
        scope.launch {
            try {
                // Prefer the secure element; fall back to the TEE if StrongBox is
                // unavailable *or* rejects the key for any reason, so a flaky
                // secure element can't strand the user on this mandatory step.
                val strongBoxWorked = AcquisitionIdentityVault.strongBoxAvailable(context) &&
                    runCatching { AcquisitionIdentityVault.setupStrongBox(context) }
                        .onFailure { if (it is AcquisitionIdentityVault.UserAuthenticationException) throw it }
                        .isSuccess
                if (!strongBoxWorked) {
                    AcquisitionIdentityVault.setupTeeAuth(context)
                }
                onProtected()
            } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                // prompt dismissed — stay on the page so the user can retry
            } catch (e: Exception) {
                Log.e(TAG, "Acquisition protection setup failed", e)
                Toast.makeText(context, R.string.slideshow_protection_error, Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 24.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )

        Text(
            text = stringResource(R.string.slideshow_protection_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Text(
            text = stringResource(R.string.slideshow_protection_biometric_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = !working,
            onClick = { protect() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ),
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(
                    text = stringResource(R.string.slideshow_protection_biometric_button),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
