package org.osservatorionessuno.bugbane.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.osservatorionessuno.bugbane.R

data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationTabs(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        TabItem(stringResource(R.string.main_nav_scan), Icons.Default.Search),
        TabItem(stringResource(R.string.main_nav_acquisitions), Icons.Default.Folder)
    )
    
    TabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        divider = { },
        indicator = { tabPositions ->
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier
                    .tabIndicatorOffset(tabPositions[selectedTabIndex])
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(2.5.dp)
                    ),
                color = MaterialTheme.colorScheme.onPrimary,
                height = 3.dp
            )
        }
    ) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabSelected(index) },
                text = { 
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                        )
                    ) 
                },
                icon = { 
                    Icon(
                        imageVector = tab.icon, 
                        contentDescription = tab.title,
                        modifier = Modifier.size(24.dp)
                    ) 
                }
            )
        }
    }
} 