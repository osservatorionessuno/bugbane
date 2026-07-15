package org.osservatorionessuno.bugbane.components

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault.PasswordPromptKind

/** Minimum acquisition-password length. It exists to resist an offline Argon2id
 *  search, so a short password would defeat its purpose. */
const val MIN_ACQUISITION_PASSWORD_LENGTH = 10

/**
 * Full-screen, tutorial-styled "set a password" step shown after the first
 * analysis of an acquisition (see the detail screen). Behaviour by [kind]:
 *
 *  - [PasswordPromptKind.MANDATORY] — no secure lock: the password is the only
 *    protection and cannot be skipped.
 *  - [PasswordPromptKind.TEE_ENCOURAGED] — strongly encouraged; skippable behind
 *    a warning that the screen lock then carries all the protection.
 *  - [PasswordPromptKind.SE_OPTIONAL] — an optional extra factor on top of the
 *    fingerprint; freely declined.
 *
 * [onResolved] is called once the step is done (password set, or dismissed where
 * allowed) so the host can hide this overlay and refresh.
 */
@Composable
fun SetAcquisitionPasswordScreen(
    kind: PasswordPromptKind,
    onResolved: () -> Unit,
    descriptionOverride: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }

    val tooShort = password.isNotEmpty() && password.length < MIN_ACQUISITION_PASSWORD_LENGTH
    val mismatch = confirmation.isNotEmpty() && confirmation != password
    val valid = password.length >= MIN_ACQUISITION_PASSWORD_LENGTH && confirmation == password

    val description = descriptionOverride ?: when (kind) {
        PasswordPromptKind.MANDATORY -> stringResource(R.string.set_password_mandatory_description)
        PasswordPromptKind.TEE_ENCOURAGED -> stringResource(R.string.set_password_tee_description)
        PasswordPromptKind.SE_OPTIONAL -> stringResource(R.string.set_password_se_description)
        PasswordPromptKind.NONE -> ""
    }

    fun submit() {
        working = true
        scope.launch {
            try {
                when (kind) {
                    PasswordPromptKind.MANDATORY ->
                        AcquisitionIdentityVault.sealPendingWithPassphrase(context, password.toByteArray())
                    PasswordPromptKind.TEE_ENCOURAGED ->
                        AcquisitionIdentityVault.migrateTeeAuthToPassphrase(context, password.toByteArray())
                    PasswordPromptKind.SE_OPTIONAL ->
                        AcquisitionIdentityVault.addStrongBoxPassphrase(context, password.toByteArray())
                    PasswordPromptKind.NONE -> Unit
                }
                onResolved()
            } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                // biometric prompt dismissed (SE/TEE re-wrap) — stay so the user can retry
            } catch (e: Exception) {
                Log.e("SetAcquisitionPassword", "Setting acquisition password failed", e)
                Toast.makeText(context, R.string.set_password_error, Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }

    fun dismiss() {
        AcquisitionIdentityVault.setPasswordPromptDismissed(context)
        onResolved()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .padding(bottom = 20.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = stringResource(R.string.set_password_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.set_password_field_label)) },
                supportingText = {
                    if (tooShort) Text(stringResource(R.string.set_password_too_short, MIN_ACQUISITION_PASSWORD_LENGTH))
                },
                singleLine = true,
                isError = tooShort,
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = { Text(stringResource(R.string.set_password_confirm_label)) },
                supportingText = {
                    if (mismatch) Text(stringResource(R.string.set_password_mismatch))
                },
                singleLine = true,
                isError = mismatch,
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind == PasswordPromptKind.SE_OPTIONAL) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.set_password_se_extra_prompts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                enabled = valid && !working,
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        text = stringResource(R.string.set_password_set_button),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }

            when (kind) {
                PasswordPromptKind.SE_OPTIONAL ->
                    TextButton(onClick = { dismiss() }, enabled = !working, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.set_password_skip_button))
                    }
                PasswordPromptKind.TEE_ENCOURAGED ->
                    TextButton(onClick = { showSkipDialog = true }, enabled = !working, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = stringResource(R.string.set_password_skip_link),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                else -> Unit // MANDATORY: no way out but setting one
            }
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text(stringResource(R.string.set_password_skip_tee_title)) },
            text = { Text(stringResource(R.string.set_password_skip_tee_message)) },
            confirmButton = {
                TextButton(onClick = { showSkipDialog = false; dismiss() }) {
                    Text(stringResource(R.string.set_password_skip_tee_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text(stringResource(R.string.set_password_skip_tee_cancel))
                }
            },
        )
    }
}
