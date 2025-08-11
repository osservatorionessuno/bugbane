package org.osservatorionessuno.bugbane

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osservatorionessuno.bugbane.components.SlideshowPage
import org.osservatorionessuno.bugbane.pages.*
import org.osservatorionessuno.bugbane.ui.theme.Theme
import org.osservatorionessuno.bugbane.utils.AdbViewModel

class SlideshowActivity : ComponentActivity() {
    private val viewModel: AdbViewModel by viewModels()
    private val totalPages = 5

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
                    onSlideshowComplete = {
                        restartMainActivity()
                    },
                    totalPages = totalPages
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
    onSlideshowComplete: () -> Unit,
    totalPages: Int
) {
    val pagerState = rememberPagerState(pageCount = { totalPages })
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val coroutineScope = rememberCoroutineScope()
    
    val goToNextPage = {
        coroutineScope.launch {
            pagerState.animateScrollToPage(currentPage + 1)
        }
    }

    val slideshowPages = listOf(
        WelcomePage.create { goToNextPage() },
        NotificationPermissionPage.create { goToNextPage() },
        DeveloperOptionsPage.create { goToNextPage() },
        WirelessDebuggingPage.create { goToNextPage() },
        FinalPage.create(onSlideshowComplete)
    )

    // Check if current page should be skipped and automatically advance
    LaunchedEffect(currentPage) {
        val currentPageData = slideshowPages.getOrNull(currentPage)
        if (currentPageData?.shouldSkip?.invoke() == true) {
            goToNextPage()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentPage) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val currentPageData = slideshowPages.getOrNull(currentPage)
                if (currentPageData?.shouldSkip?.invoke() == true) {
                    goToNextPage()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
            slideshowPages.forEachIndexed { index, _ ->
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
            SlideshowPage(page = slideshowPages[pageIndex])
        }
    }
}

 