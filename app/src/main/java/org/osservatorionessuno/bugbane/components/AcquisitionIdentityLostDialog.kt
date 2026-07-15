package org.osservatorionessuno.bugbane.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.osservatorionessuno.bugbane.R

/**
 * Notifies the user that a screen-lock removal invalidated the acquisition key and
 * its acquisitions can no longer be decrypted, and offers to reset protection.
 *
 * [onReset] should discard the dead identity and route to re-establishing
 * protection (see [org.osservatorionessuno.bugbane.utils.AcquisitionRecovery]).
 * When [onDismiss] is null the dialog is non-dismissible — used where the identity
 * is already gone and there is nothing to go back to (the detail screen); pass a
 * handler where cancelling is reasonable (before starting a new acquisition).
 */
@Composable
fun AcquisitionIdentityLostDialog(onReset: () -> Unit, onDismiss: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(stringResource(R.string.acquisition_identity_lost_title)) },
        text = { Text(stringResource(R.string.acquisition_identity_lost_message)) },
        confirmButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.acquisition_identity_lost_reset))
            }
        },
        dismissButton = onDismiss?.let { dismiss ->
            {
                TextButton(onClick = dismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
