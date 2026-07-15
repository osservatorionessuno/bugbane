package org.osservatorionessuno.bugbane.components

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault.PasswordPromptKind

private const val TAG = "AcquisitionProtection"

/**
 * Onboarding / recovery step for establishing the acquisition encryption
 * identity, styled to match [SlideshowPage]. Offers the protection appropriate
 * to the device:
 *
 *  - a secure lock + hardware keystore → one biometric/credential confirmation
 *    locks the key to the device (StrongBox when available, otherwise the TEE);
 *  - no secure lock → "set a screen lock" (deep-links to the system flow, then
 *    re-checks on resume) so the biometric option becomes available;
 *  - either way → "set a password instead", an Argon2id-sealed identity that
 *    survives a future lock removal.
 *
 * The same screen serves post-invalidation recovery (see
 * [AcquisitionIdentityVault.isRecoveryPending]); there it leads with the lost-key
 * explanation. Calls [onProtected] once an identity exists.
 */
@Composable
fun AcquisitionProtectionPage(onProtected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    val recovery = remember { AcquisitionIdentityVault.isRecoveryPending(context) }
    val hardwareKeystore = remember { AcquisitionIdentityVault.hasHardwareKeystore() }

    // Re-read on resume: the user may have just added a screen lock via the button
    // below, which turns the biometric option on without any state change here.
    var deviceSecure by remember { mutableStateOf(AcquisitionIdentityVault.isDeviceSecure(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceSecure = AcquisitionIdentityVault.isDeviceSecure(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Password branch reuses the full "set a password" screen; back returns here.
    if (showPassword) {
        BackHandler { showPassword = false }
        SetAcquisitionPasswordScreen(
            kind = PasswordPromptKind.MANDATORY,
            descriptionOverride = stringResource(R.string.set_password_chooser_description),
            onResolved = onProtected,
        )
        return
    }

    fun protect() {
        working = true
        scope.launch {
            try {
                // Prefer the secure element; fall back to the TEE if StrongBox is
                // unavailable *or* rejects the key for any reason, so a flaky
                // secure element can't strand the user on this step.
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

    fun openScreenLockSettings() {
        // Directly opens the system "set a screen lock" flow. On return, the resume
        // observer re-reads deviceSecure and the biometric option appears.
        runCatching {
            context.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))
        }.onFailure { Log.e(TAG, "Couldn't open screen-lock settings", it) }
    }

    // The one recommended action for this device: lock the key to a fingerprint if
    // possible, else re-secure the device first, else fall back to a password. The
    // password is always available as a secondary choice, so it's only the primary
    // when neither of the others applies.
    val canBiometric = deviceSecure && hardwareKeystore
    val primaryLabel: Int
    val primaryAction: () -> Unit
    when {
        canBiometric -> { primaryLabel = R.string.slideshow_protection_biometric_button; primaryAction = ::protect }
        !deviceSecure -> { primaryLabel = R.string.protection_set_screen_lock; primaryAction = ::openScreenLockSettings }
        else -> { primaryLabel = R.string.protection_use_password; primaryAction = { showPassword = true } }
    }
    val passwordIsSecondary = canBiometric || !deviceSecure

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
            text = stringResource(
                if (recovery) R.string.acquisition_identity_lost_title else R.string.slideshow_protection_title
            ),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Text(
            text = stringResource(
                if (recovery) R.string.protection_recovery_description
                else R.string.slideshow_protection_biometric_description
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = !working,
            onClick = primaryAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ),
        ) {
            // Only the biometric action runs asynchronously (working); the others
            // just launch a screen and leave the button idle.
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(
                    text = stringResource(primaryLabel),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
        }

        if (passwordIsSecondary) {
            TextButton(
                onClick = { showPassword = true },
                enabled = !working,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.protection_use_password))
            }
        }
    }
}
