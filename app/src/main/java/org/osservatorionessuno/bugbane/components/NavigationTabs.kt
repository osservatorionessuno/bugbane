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
import androidx.compose.foundation.clickable

data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationTabs(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    isLandscape: Boolean = false,
    isAcquisitionsTabDisabled: Boolean = false // Temporary: Disable acquisitions tab while scanning
) {
    val tabs = listOf(
        TabItem(stringResource(R.string.main_nav_scan), Icons.Default.Search),
        TabItem(stringResource(R.string.main_nav_acquisitions), Icons.Default.Folder)
    )
    
    if (isLandscape) {
        // Landscape mode: horizontal tabs without background
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = selectedTabIndex == index
                val isDisabled = index == 1 && isAcquisitionsTabDisabled // Temporary: Disable acquisitions tab (index 1)
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(enabled = !isDisabled) { onTabSelected(index) }
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (isDisabled) 0.4f else 1f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (isDisabled) 0.4f else 1f)
                    )
                }
            }
        }
    } else {
        // Portrait mode: full-width tab row
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
                val isDisabled = index == 1 && isAcquisitionsTabDisabled // Temporary: Disable acquisitions tab (index 1)
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { onTabSelected(index) },
                    enabled = !isDisabled,
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
} 