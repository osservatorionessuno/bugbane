package org.osservatorionessuno.bugbane

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.stringResource
import java.io.File
import org.osservatorionessuno.bugbane.components.SetAcquisitionPasswordScreen
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.screens.AcquisitionDetailScreen
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault.PasswordPromptKind

class AcquisitionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val dir = File(path)
        enableEdgeToEdge()
        setContent {
            Theme {
                AcquisitionContent(dir)
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "acquisition_dir"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquisitionContent(dir: File) {
    val context = LocalContext.current

    // The results stay viewable (and exportable) without a password. The "set a
    // password" step is only forced when leaving the screen: no-lock devices must
    // set one to keep the acquisition; secure element / TEE devices are asked once
    // and may decline. A non-NONE value shows the takeover and defers the exit.
    var exitPrompt by remember { mutableStateOf(PasswordPromptKind.NONE) }

    fun promptKindForExit(): PasswordPromptKind {
        val kind = AcquisitionIdentityVault.passwordPromptKind(context)
        return when (kind) {
            PasswordPromptKind.MANDATORY -> kind
            PasswordPromptKind.SE_OPTIONAL, PasswordPromptKind.TEE_ENCOURAGED ->
                if (AcquisitionIdentityVault.isPasswordPromptDismissed(context)) PasswordPromptKind.NONE else kind
            PasswordPromptKind.NONE -> PasswordPromptKind.NONE
        }
    }

    fun tryExit() {
        val kind = promptKindForExit()
        if (kind == PasswordPromptKind.NONE) {
            (context as? ComponentActivity)?.finish()
        } else {
            exitPrompt = kind
        }
    }

    // Intercept the system back so it goes through the same gate as the toolbar.
    BackHandler(enabled = exitPrompt == PasswordPromptKind.NONE) { tryExit() }
    // While the takeover is up, back is inert for the mandatory case (there is no
    // way out but setting one); optional/encouraged users use the Skip control.
    BackHandler(enabled = exitPrompt != PasswordPromptKind.NONE) { /* consume */ }

    // The password step, shown only on exit (see tryExit), fully *replaces* the
    // detail screen rather than overlaying it — so the detail screen's own dialogs
    // (unlock / passphrase) can't float above the takeover, and a running
    // auto-analysis isn't hidden behind an opaque surface with no way out.
    // Resolving it (password set, or declined where allowed) completes the exit.
    if (exitPrompt != PasswordPromptKind.NONE) {
        SetAcquisitionPasswordScreen(
            kind = exitPrompt,
            onResolved = {
                exitPrompt = PasswordPromptKind.NONE
                (context as? ComponentActivity)?.finish()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.acquisition_details_title)) },
                navigationIcon = {
                    IconButton(onClick = { tryExit() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AcquisitionDetailScreen(dir)
        }
    }
}
