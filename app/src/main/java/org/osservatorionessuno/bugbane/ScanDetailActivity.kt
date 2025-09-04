package org.osservatorionessuno.bugbane

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.io.File
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.screens.ScanDetailScreen

class ScanDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val acquisitionPath = intent.getStringExtra(EXTRA_ACQUISITION_PATH)
        val scanPath = intent.getStringExtra(EXTRA_SCAN_PATH)
        if (acquisitionPath == null || scanPath == null) {
            finish()
            return
        }
        val acquisitionDir = File(acquisitionPath)
        val scanFile = File(scanPath)
        enableEdgeToEdge()
        setContent {
            Theme {
                ScanDetailContent(acquisitionDir, scanFile)
            }
        }
    }

    companion object {
        const val EXTRA_ACQUISITION_PATH = "acquisition_dir"
        const val EXTRA_SCAN_PATH = "scan_file"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailContent(acquisitionDir: File, scanFile: File) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_details_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
            ScanDetailScreen(acquisitionDir, scanFile)
        }
    }
}
