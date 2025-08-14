package org.osservatorionessuno.bugbane

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import io.github.muntashirakon.adb.PRNGFixes
import org.osservatorionessuno.bugbane.components.AppTopBar
import org.osservatorionessuno.bugbane.components.NavigationTabs
import org.osservatorionessuno.bugbane.screens.ScanScreen
import org.osservatorionessuno.bugbane.screens.AcquisitionsScreen
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.SlideshowManager
import org.osservatorionessuno.bugbane.utils.AdbViewModel
import org.osservatorionessuno.bugbane.utils.AdbPairingService
import org.osservatorionessuno.bugbane.utils.ConfigurationManager

class MainActivity : ComponentActivity() {
    private val viewModel: AdbViewModel by viewModels()
    private var setLacksPermissionsCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PRNGFixes.apply()
        
        // Observers
        viewModel.watchConnectAdb().observe(this) { isConnected ->
            if (!isConnected) {
                setLacksPermissionsCallback?.invoke(true)
            }
        }

        viewModel.watchAskPairAdb().observe(this) { resetPairing ->
            if (resetPairing) {
                setLacksPermissionsCallback?.invoke(true)
            }
        }
        
        viewModel.watchCommandOutput().observe(this) { output ->
            // TODO: blibla
            Toast.makeText(this@MainActivity, output.toString(), Toast.LENGTH_SHORT).show()
            Log.d("COMMAND OUTPUT", output.toString())
        }

        // Try auto-connecting
        viewModel.autoConnect()

        if (!ConfigurationManager.isNotificationPermissionGranted(this) || !ConfigurationManager.isWirelessDebuggingEnabled(this)) {
            setLacksPermissionsCallback?.invoke(true)
        }

        if (!SlideshowManager.hasSeenHomepage(this)) {
            // On first start, run the SlideshowActivity manually
            SlideshowActivity.start(this)
        }

        enableEdgeToEdge()
        setContent {
            Theme {
                MainContent { callback ->
                    setLacksPermissionsCallback = callback
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainContent(onSetLacksPermissionsCallback: ((Boolean) -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    var lacksPermissions by remember { mutableStateOf(false) }
    
    // Sync tab selection with pager
    val selectedTabIndex by remember { derivedStateOf { pagerState.currentPage } }
    
    // Function to set lacks permissions state
    fun setLacksPermissions(lacks: Boolean) {
        lacksPermissions = lacks
    }
    
    // Provide the callback to the parent
    LaunchedEffect(Unit) {
        onSetLacksPermissionsCallback { lacks ->
            setLacksPermissions(lacks)
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    onSettingsClick = {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    }
                )
                NavigationTabs(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { tabIndex ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(tabIndex)
                        }
                    }
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                when (pageIndex) {
                    0 -> ScanScreen(
                        lacksPermissions = lacksPermissions,
                        onLacksPermissionsChange = { setLacksPermissions(it) }
                    )
                    1 -> AcquisitionsScreen()
                }
            }
        }
    }
}