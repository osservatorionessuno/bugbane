package org.osservatorionessuno.bugbane.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import org.osservatorionessuno.bugbane.BuildConfig
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.components.MIN_ACQUISITION_PASSWORD_LENGTH
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osservatorionessuno.bugbane.update.IndicatorStore
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.AndroidKeystoreKeyVault
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen() {
    val context = LocalContext.current

    val store = remember { IndicatorStore(context) }
    var indicatorVersion by remember { mutableStateOf<Int?>(null) }
    var lastUpdate by remember { mutableStateOf<Long?>(null) }
    var lastFetch by remember { mutableStateOf<Long?>(null) }
    var indicatorCount by remember { mutableStateOf<Long?>(null) }

    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    fun formatEpoch(epoch: Long?): String =
        if (epoch == null || epoch == 0L) "N/A" else formatter.format(Instant.ofEpochSecond(epoch))

    // Until an update has actually been adopted (version > 0), every field reads N/A.
    val everUpdated = (indicatorVersion ?: 0) > 0
    fun orNa(value: String): String = if (everUpdated) value else "N/A"

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val s = store.readState()
            indicatorVersion = s.version
            lastUpdate = s.lastUpdateEpoch
            lastFetch = s.lastCheckEpoch
            indicatorCount = s.objectCount.toLong()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.settings_indicators_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_indicators_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.settings_indicators_version, orNa((indicatorVersion ?: 0).toString())))
                Text(text = stringResource(R.string.settings_indicators_last_fetch, orNa(formatEpoch(lastFetch))))
                Text(text = stringResource(R.string.settings_indicators_last_update, orNa(formatEpoch(lastUpdate))))
                Text(text = stringResource(R.string.settings_indicators_count, orNa((indicatorCount?.toInt() ?: 0).toString())))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Only the passphrase tiers have a password to change.
        val passwordTier = remember {
            AcquisitionIdentityVault.tier(context)?.takeIf { it.usesPassphrase }
        }
        if (passwordTier != null) {
            ChangeAcquisitionPasswordCard()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Add/remove a protection factor (only where there's a fingerprint gate to
        // add a password to, or a two-factor setup to drop a factor from).
        ManageProtectionCard()
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {

                // Reset Slideshow Button
                Button(
                    onClick = {
                        ConfigurationManager.openDeveloperOptions(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_developer_options),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.settings_developer_options_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Button(
                    onClick = {
                        val intent = Intent(context, org.osservatorionessuno.bugbane.AboutActivity::class.java)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.settings_about_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Change the acquisition password. Only re-wraps the identity — acquisitions are
 * encrypted to the identity, not the password, so nothing is re-encrypted.
 */
@Composable
private fun ChangeAcquisitionPasswordCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_change_password_button),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_change_password_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }

    if (!showDialog) return

    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var wrongCurrent by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val tooShort = new.isNotEmpty() && new.length < MIN_ACQUISITION_PASSWORD_LENGTH
    val mismatch = confirmation.isNotEmpty() && confirmation != new
    val valid = current.isNotEmpty() && new.length >= MIN_ACQUISITION_PASSWORD_LENGTH && confirmation == new

    fun change() {
        working = true
        scope.launch {
            try {
                val changed = AcquisitionIdentityVault.withPasswordBytes(current) { cur ->
                    AcquisitionIdentityVault.withPasswordBytes(new) { neu ->
                        AcquisitionIdentityVault.changePassword(context, cur, neu)
                    }
                }
                if (changed) {
                    showDialog = false
                    Toast.makeText(context, R.string.settings_change_password_success, Toast.LENGTH_SHORT).show()
                } else {
                    wrongCurrent = true
                }
            } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                // biometric prompt dismissed — keep the dialog open, nothing changed
            } catch (_: Exception) {
                Toast.makeText(context, R.string.settings_change_password_error, Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) showDialog = false },
        title = { Text(stringResource(R.string.settings_change_password)) },
        text = {
            Column {
                PasswordField(current, { current = it; wrongCurrent = false }, stringResource(R.string.settings_change_password_current),
                    if (wrongCurrent) stringResource(R.string.acquisition_unlock_password_wrong) else null, !working)
                PasswordField(new, { new = it }, stringResource(R.string.settings_change_password_new),
                    if (tooShort) stringResource(R.string.set_password_too_short, MIN_ACQUISITION_PASSWORD_LENGTH) else null, !working)
                PasswordField(confirmation, { confirmation = it }, stringResource(R.string.set_password_confirm_label),
                    if (mismatch) stringResource(R.string.set_password_mismatch) else null, !working)
            }
        },
        confirmButton = {
            TextButton(onClick = { change() }, enabled = !working && valid) {
                if (working) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.settings_change_password_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }, enabled = !working) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private enum class ProtectionAction { ADD_PASSWORD, REMOVE_PASSWORD, REMOVE_FINGERPRINT }

/**
 * Add or remove a protection factor. Shown only where there's a fingerprint gate: a
 * biometric-only tier can add a password (→ two-factor), and a two-factor tier can
 * drop either factor — never both. [AcquisitionIdentityVault.removeFingerprint] leaves
 * a password that survives lock removal; [AcquisitionIdentityVault.removePassword]
 * leaves the fingerprint. The keypair is preserved, so acquisitions stay readable.
 * Also picks how the fingerprint gate confirms: a recent-unlock window or a fresh
 * prompt per operation ([AcquisitionIdentityVault.AuthMode]).
 */
@Composable
private fun ManageProtectionCard() {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val tier = remember(version) { AcquisitionIdentityVault.tier(context) }
    if (tier == null || !tier.usesBiometric) return

    var action by remember { mutableStateOf<ProtectionAction?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.settings_protection_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (!tier.usesPassphrase) {
                ProtectionActionRow(R.string.settings_add_password, R.string.settings_add_password_desc) {
                    action = ProtectionAction.ADD_PASSWORD
                }
            } else {
                ProtectionActionRow(R.string.settings_remove_password, R.string.settings_remove_password_desc) {
                    action = ProtectionAction.REMOVE_PASSWORD
                }
                Spacer(modifier = Modifier.height(12.dp))
                ProtectionActionRow(R.string.settings_remove_fingerprint, R.string.settings_remove_fingerprint_desc) {
                    action = ProtectionAction.REMOVE_FINGERPRINT
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            AuthModeSection(version)
        }
    }

    val close = { changed: Boolean -> action = null; if (changed) version++ }
    when (action) {
        ProtectionAction.ADD_PASSWORD ->
            AddPasswordDialog(onDone = close)
        ProtectionAction.REMOVE_PASSWORD ->
            ConfirmWithPasswordDialog(R.string.settings_remove_password,
                run = { AcquisitionIdentityVault.removePassword(context, it) }, onDone = close)
        ProtectionAction.REMOVE_FINGERPRINT ->
            ConfirmWithPasswordDialog(R.string.settings_remove_fingerprint,
                run = { AcquisitionIdentityVault.removeFingerprint(context, it) }, onDone = close)
        null -> {}
    }
}

/**
 * Choose how the fingerprint gate confirms ([AcquisitionIdentityVault.AuthMode]):
 * a recent unlock arming the keys for a window, or a fresh prompt per operation.
 * Switching re-wraps only the identity's outer layer under the current tier and
 * shows the prompt(s) the modes involved require.
 */
@Composable
private fun AuthModeSection(version: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(version) { mutableStateOf(AcquisitionIdentityVault.authMode(context)) }
    var working by remember { mutableStateOf(false) }
    if (mode == null) return

    fun switch(target: AcquisitionIdentityVault.AuthMode) {
        if (working || target == mode) return
        working = true
        scope.launch {
            try {
                if (AcquisitionIdentityVault.setAuthMode(context, target)) {
                    mode = target
                    Toast.makeText(context, R.string.settings_protection_updated, Toast.LENGTH_SHORT).show()
                }
            } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                // biometric prompt dismissed — nothing changed
            } catch (_: Exception) {
                Toast.makeText(context, R.string.settings_protection_error, Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }

    Text(
        text = stringResource(R.string.settings_auth_mode_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    AuthModeOption(
        title = stringResource(R.string.settings_auth_mode_window),
        desc = stringResource(R.string.settings_auth_mode_window_desc, AndroidKeystoreKeyVault.AUTH_WINDOW_SECONDS / 60),
        selected = mode == AcquisitionIdentityVault.AuthMode.WINDOW,
        enabled = !working,
    ) { switch(AcquisitionIdentityVault.AuthMode.WINDOW) }
    AuthModeOption(
        title = stringResource(R.string.settings_auth_mode_per_op),
        desc = stringResource(R.string.settings_auth_mode_per_op_desc),
        selected = mode == AcquisitionIdentityVault.AuthMode.PER_OPERATION,
        enabled = !working,
    ) { switch(AcquisitionIdentityVault.AuthMode.PER_OPERATION) }
}

@Composable
private fun AuthModeOption(title: String, desc: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ProtectionActionRow(titleRes: Int, descRes: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(descRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

/** Confirm a factor removal by entering the current acquisition password. */
@Composable
private fun ConfirmWithPasswordDialog(
    titleRes: Int,
    run: suspend (ByteArray) -> Boolean,
    onDone: (changed: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var wrongCurrent by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDone(false) },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                PasswordField(current, { current = it; wrongCurrent = false },
                    stringResource(R.string.settings_change_password_current),
                    if (wrongCurrent) stringResource(R.string.acquisition_unlock_password_wrong) else null, !working)
            }
        },
        confirmButton = {
            TextButton(enabled = !working && current.isNotEmpty(), onClick = {
                working = true
                scope.launch {
                    try {
                        if (AcquisitionIdentityVault.withPasswordBytes(current) { run(it) }) {
                            Toast.makeText(context, R.string.settings_protection_updated, Toast.LENGTH_SHORT).show()
                            onDone(true)
                        } else {
                            wrongCurrent = true
                        }
                    } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                        // biometric prompt dismissed — keep the dialog open
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.settings_protection_error, Toast.LENGTH_LONG).show()
                    } finally {
                        working = false
                    }
                }
            }) {
                if (working) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.settings_protection_confirm_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(false) }, enabled = !working) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Add a password to a biometric-only identity (→ two-factor). */
@Composable
private fun AddPasswordDialog(onDone: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val tooShort = new.isNotEmpty() && new.length < MIN_ACQUISITION_PASSWORD_LENGTH
    val mismatch = confirmation.isNotEmpty() && confirmation != new
    val valid = new.length >= MIN_ACQUISITION_PASSWORD_LENGTH && confirmation == new

    AlertDialog(
        onDismissRequest = { if (!working) onDone(false) },
        title = { Text(stringResource(R.string.settings_add_password)) },
        text = {
            Column {
                PasswordField(new, { new = it }, stringResource(R.string.settings_change_password_new),
                    if (tooShort) stringResource(R.string.set_password_too_short, MIN_ACQUISITION_PASSWORD_LENGTH) else null, !working)
                PasswordField(confirmation, { confirmation = it }, stringResource(R.string.set_password_confirm_label),
                    if (mismatch) stringResource(R.string.set_password_mismatch) else null, !working)
            }
        },
        confirmButton = {
            TextButton(enabled = !working && valid, onClick = {
                working = true
                scope.launch {
                    try {
                        if (AcquisitionIdentityVault.withPasswordBytes(new) { AcquisitionIdentityVault.addPassword(context, it) }) {
                            Toast.makeText(context, R.string.settings_protection_updated, Toast.LENGTH_SHORT).show()
                            onDone(true)
                        }
                    } catch (_: AcquisitionIdentityVault.UserAuthenticationException) {
                        // biometric prompt dismissed — keep the dialog open
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.settings_protection_error, Toast.LENGTH_LONG).show()
                    } finally {
                        working = false
                    }
                }
            }) {
                if (working) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.settings_add_password))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(false) }, enabled = !working) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, error: String?, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { if (error != null) Text(error) },
        singleLine = true,
        isError = error != null,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
} 