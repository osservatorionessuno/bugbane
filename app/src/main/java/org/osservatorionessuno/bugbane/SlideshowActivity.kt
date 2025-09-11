package org.osservatorionessuno.bugbane

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.osservatorionessuno.bugbane.components.SlideshowPage
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.AdbManager
import org.osservatorionessuno.bugbane.utils.AppState
import org.osservatorionessuno.bugbane.utils.ConfigurationManager
import org.osservatorionessuno.bugbane.utils.ConfigurationViewModel
import org.osservatorionessuno.bugbane.utils.SlideshowManager

class SlideshowActivity : ComponentActivity() {
    private val viewModel = AdbManager(application)
    private val configViewModel: ConfigurationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Exit application when back is pressed during slideshow
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Exit the application
                finishAffinity()
            }
        })

        viewModel.watchConnectAdb().observe(this) { isConnected ->
            // Successfully connected to ADB, skip ahead
            if (isConnected) {
                restartMainActivity()
            }
        }
        // Try auto-connecting
        viewModel.autoConnect()
        
        enableEdgeToEdge()
        setContent {
            Theme {
                SlideshowScreen(
                    configViewModel,
                    onSlideshowComplete = {
                        restartMainActivity()
                    }
                )
            }
        }
    }

    private fun restartMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SlideshowActivity::class.java)
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowScreen(
    viewModel: ConfigurationViewModel,
    onSlideshowComplete: () -> Unit,
) {
    val allStates = AppState.valuesInOrder().filter { it != AppState.AdbConnected }
    val permissionState by viewModel.configurationState.collectAsState()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { allStates.size })

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val coroutineScope = rememberCoroutineScope()

    suspend fun updatePager(permissionState: AppState) {
        val currentIndex = allStates.indexOf(permissionState)
        if (currentIndex in allStates.indices) {
            pagerState.animateScrollToPage(currentIndex)
        } else if (permissionState == AppState.AdbConnected) {
            onSlideshowComplete()
        }
    }

    // Skip screens already satisfied
    LaunchedEffect(permissionState) {
        updatePager(permissionState)
    }

    // todo: test onResume then remove this block
//    val lifecycleOwner = LocalLifecycleOwner.current
//    DisposableEffect(lifecycleOwner, currentPage) {
//        val observer = LifecycleEventObserver { _, event ->
//            if (event == Lifecycle.Event.ON_RESUME) {
//                updatePager(permissionState)
//            }
//        }
//        lifecycleOwner.lifecycle.addObserver(observer)
//        onDispose {
//            lifecycleOwner.lifecycle.removeObserver(observer)
//        }
//    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Page indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            allStates.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (currentPage == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Horizontal pager (swipe disabled)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false
        ) { pageIndex ->
            val state = allStates[pageIndex]
            SlideshowPage(
                state = state,
                onClickContinue = { SlideshowManager::handleOnContinue }
            ) // todo
        }
    }
}


 