package com.unraiddesk.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.ui.shell.OfflineStatusBanner
import com.unraiddesk.app.ui.theme.LocalSemanticColors
import com.unraiddesk.app.ui.theme.TabularNumericStyle
import java.util.Locale

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        is DashboardUiState.Loading -> DashboardLoadingSkeleton(modifier = modifier)
        is DashboardUiState.Error -> DashboardErrorState(
            message = uiState.message,
            onRetry = onRefresh,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        is DashboardUiState.Content -> DashboardContent(
            state = uiState,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState.Content,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val snapshot = state.snapshot
    val array = snapshot.array
    val arrayFraction = if (array.totalBytes > 0L) {
        (array.usedBytes.toFloat() / array.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val allDisks = array.disks + array.caches

    Column(modifier = modifier.fillMaxSize()) {
        if (state.isOffline) {
            OfflineStatusBanner(
                lastUpdatedText = formatRelativeMinutes(state.lastUpdatedMillis),
                reason = state.offlineReason,
                onRetry = onRefresh,
                onOpenSettings = onOpenSettings
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = state.serverName,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        val subtitle = listOfNotNull(
                            snapshot.info.distro.takeIf { it.isNotBlank() },
                            snapshot.info.release.takeIf { it.isNotBlank() },
                            snapshot.info.cpuBrand.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh dashboard"
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "CPU Load",
                        value = String.format(Locale.US, "%.1f%%", snapshot.metrics.cpuPercent),
                        subtitle = if (snapshot.info.cpuCores > 0) {
                            "${snapshot.info.cpuCores}C / ${snapshot.info.cpuThreads}T"
                        } else {
                            "Processor"
                        },
                        progress = (snapshot.metrics.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f),
                        accent = if (snapshot.metrics.cpuPercent > 85.0) semantic.warning else semantic.running,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Memory",
                        value = String.format(Locale.US, "%.1f%%", snapshot.metrics.ramPercent),
                        subtitle = if (snapshot.metrics.ramTotalBytes > 0L) {
                            "${formatBytes(snapshot.metrics.ramUsedBytes)} / ${formatBytes(snapshot.metrics.ramTotalBytes)}"
                        } else {
                            "RAM"
                        },
                        progress = (snapshot.metrics.ramPercent / 100.0).toFloat().coerceIn(0f, 1f),
                        accent = if (snapshot.metrics.ramPercent > 90.0) semantic.warning else semantic.running,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Storage,
                                    contentDescription = null
                                )
                                Text(
                                    text = "Array Health",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            StatusPill(
                                label = array.state,
                                color = if (array.state.equals("STARTED", ignoreCase = true)) {
                                    semantic.running
                                } else {
                                    semantic.warning
                                }
                            )
                        }
                        LinearProgressIndicator(
                            progress = { arrayFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Used: ${formatBytes(array.usedBytes)} / ${formatBytes(array.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle)
                            )
                            Text(
                                text = "Parity: ${array.parityCheckStatus.status}",
                                style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Disks & Caches (${allDisks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (allDisks.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No disk telemetry reported by server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(allDisks, key = { "${it.isCache}_${it.id}" }) { disk ->
                    DiskRowCard(disk = disk)
                }
            }

            item { Box(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(text = title, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.merge(TabularNumericStyle)
            )
            LinearProgressIndicator(
                progress = { progress },
                color = accent,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiskRowCard(disk: ArrayDisk) {
    val semantic = LocalSemanticColors.current
    val isHealthy = disk.status.contains("OK", ignoreCase = true) ||
        disk.status.equals("ACTIVE", ignoreCase = true)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isHealthy) semantic.running else semantic.warning)
                    )
                    Column {
                        Text(
                            text = if (disk.isCache) "${disk.name} (Cache)" else disk.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = disk.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    val tempText = disk.tempCelsius?.let { "$it°C" } ?: "--"
                    Text(
                        text = tempText,
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumericStyle)
                    )
                    Text(
                        text = "R:${disk.numReads} W:${disk.numWrites}",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DashboardLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(4) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
            ) {}
        }
    }
}

@Composable
private fun DashboardErrorState(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Unable to Connect to Unraid",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onOpenSettings) { Text("Open Server Settings") }
        }
    }
}

private fun formatRelativeMinutes(updatedAtMillis: Long): String {
    val diffMin = ((System.currentTimeMillis() - updatedAtMillis) / 60_000L).coerceAtLeast(0L)
    return if (diffMin == 0L) "just now" else "${diffMin}m ago"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}
