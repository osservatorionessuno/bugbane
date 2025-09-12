package org.osservatorionessuno.bugbane

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.muntashirakon.adb.PRNGFixes
import androidx.work.*
import org.osservatorionessuno.libmvt.common.IndicatorsUpdates
import org.osservatorionessuno.bugbane.workers.IndicatorsUpdateWorker
import java.util.concurrent.TimeUnit
import org.osservatorionessuno.bugbane.components.AppTopBar
import org.osservatorionessuno.bugbane.components.MergedTopBar
import org.osservatorionessuno.bugbane.components.NavigationTabs
import org.osservatorionessuno.bugbane.screens.ScanScreen
import org.osservatorionessuno.bugbane.screens.AcquisitionsScreen
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.AppState
import org.osservatorionessuno.bugbane.utils.ConfigurationViewModel
import org.osservatorionessuno.bugbane.utils.SlideshowManager
import org.osservatorionessuno.bugbane.utils.ViewModelFactory

private const val TAG = "MainActivity"
class MainActivity : ComponentActivity() {

    private val configViewModel : ConfigurationViewModel by lazy {
        ViewModelFactory.get(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PRNGFixes.apply()

        // Fetch indicators on first launch and schedule daily background updates
        setupIndicatorsUpdates()

        enableEdgeToEdge()
        setContent {
            Theme {
                val appState = configViewModel.configurationState.collectAsStateWithLifecycle()
                val appProgress: State<SlideshowManager.AppProgress> = configViewModel.appManager.appProgress.collectAsStateWithLifecycle()

                if (appProgress.value.hasCompletedOnboarding) {
                    MainContent()
                } else {
                    // Avoid flicker before the slideshow while compose is calculating the appstate
                    Box(modifier = Modifier.fillMaxSize())

                    LaunchedEffect(appState.value) {
                        // Permissions slideshow
                        val startPage = (appState.value.step)
                        val intent = Intent(this@MainActivity, SlideshowActivity::class.java)
                            .putExtra("startPage", startPage)
                        startActivity(intent)
                    }
                }
            }
        }

        configViewModel.adbManager.watchCommandOutput().observe(this) { output ->
            // TODO
            Toast.makeText(applicationContext, output, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Command output: $output")
        }
    }

    private fun setupIndicatorsUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Kick an immediate, one-time run if nothing has been downloaded yet
        val updates = IndicatorsUpdates(filesDir.toPath(), null)
        if (updates.latestUpdate == 0L) {
            val now = OneTimeWorkRequestBuilder<IndicatorsUpdateWorker>()
                .setConstraints(constraints)
                .addTag("IndicatorsUpdate") // common tag for querying later if you want
                .build()

            WorkManager.getInstance(this).enqueueUniqueWork(
                "IndicatorsUpdateInitial",
                ExistingWorkPolicy.KEEP,
                now
            )
        }

        // Schedule daily periodic job (unique)
        val periodic = PeriodicWorkRequestBuilder<IndicatorsUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .addTag("IndicatorsUpdate")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "IndicatorsUpdatePeriodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        Log.i("MainActivity", "Scheduled daily indicator update worker")
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    // Detect if we're in landscape mode
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    // Sync tab selection with pager
    val selectedTabIndex by remember { derivedStateOf { pagerState.currentPage } }
    
    Scaffold(
        topBar = {
            if (isLandscape) {
                // Landscape mode: merged top bar
                MergedTopBar(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { tabIndex ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(tabIndex)
                        }
                    },
                    onSettingsClick = {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            } else {
                // Portrait mode: separate top bar and navigation tabs
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
                    0 -> ScanScreen()
                    1 -> AcquisitionsScreen()
                }
            }
        }
    }
}

