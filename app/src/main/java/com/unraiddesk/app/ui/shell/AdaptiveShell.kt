package com.unraiddesk.app.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.ui.theme.LocalSemanticColors

enum class AppDestination(
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("Dashboard", Icons.Outlined.Dashboard),
    SHARES("Shares", Icons.Outlined.PieChart),
    DOCKER("Docker", Icons.Outlined.Dns),
    FILES("Files", Icons.Outlined.Folder),
    SETTINGS("Settings", Icons.Outlined.Settings)
}

@Composable
fun OfflineStatusBanner(
    lastUpdatedText: String,
    reason: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiOff,
                    contentDescription = "Offline",
                    tint = semantic.offline
                )
                Column {
                    Text(
                        text = "Offline · Last updated $lastUpdatedText",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (!reason.isNullOrBlank()) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
fun AdaptiveShell(
    selectedDestination: AppDestination,
    onSelectDestination: (AppDestination) -> Unit,
    captionTabs: List<CaptionTabItem>,
    activeCaptionTabId: String,
    onSelectCaptionTab: (String) -> Unit,
    onCloseCaptionTab: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewCaptionTab: (() -> Unit)? = null,
    content: @Composable (isExpanded: Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp

        if (isExpanded) {
            Column(modifier = Modifier.fillMaxSize()) {
                DesktopCaptionTabBar(
                    tabs = captionTabs,
                    activeTabId = activeCaptionTabId,
                    onSelectTab = onSelectCaptionTab,
                    onCloseTab = onCloseCaptionTab,
                    onNewTab = onNewCaptionTab
                )
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        AppDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = dest == selectedDestination,
                                onClick = { onSelectDestination(dest) },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        content(true)
                    }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = dest == selectedDestination,
                                onClick = { onSelectDestination(dest) },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    content(false)
                }
            }
        }
    }
}
