package com.unraiddesk.app.ui.shares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.ui.shell.OfflineStatusBanner
import com.unraiddesk.app.ui.theme.TabularNumericStyle
import java.util.Locale

@Composable
fun SharesScreen(
    uiState: SharesUiState,
    onRefresh: () -> Unit,
    onOpenInFiles: (shareName: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        is SharesUiState.Loading -> {
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
                            .height(104.dp)
                    ) {}
                }
            }
        }

        is SharesUiState.Error -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Unable to Load Shares", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = uiState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRefresh) { Text("Retry") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Open Server Settings") }
                }
            }
        }

        is SharesUiState.Content -> {
            Column(modifier = modifier.fillMaxSize()) {
                if (uiState.isOffline) {
                    val mins = ((System.currentTimeMillis() - uiState.lastUpdatedMillis) / 60_000L).coerceAtLeast(0L)
                    OfflineStatusBanner(
                        lastUpdatedText = if (mins == 0L) "just now" else "${mins}m ago",
                        reason = uiState.offlineReason,
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
                            Text(
                                text = "User Shares (${uiState.shares.size})",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            IconButton(onClick = onRefresh) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Refresh shares"
                                )
                            }
                        }
                    }

                    if (uiState.shares.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "No Unraid shares found",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Create shares in the Unraid WebGUI or refresh to query again.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(uiState.shares, key = { it.share.name }) { item ->
                            ShareCard(
                                item = item,
                                onOpenInFiles = { onOpenInFiles(item.share.name) }
                            )
                        }
                    }

                    item { Box(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ShareCard(
    item: ShareRowItem,
    onOpenInFiles: () -> Unit
) {
    val share = item.share
    val usedFraction = if (share.sizeBytes > 0L) {
        (share.usedBytes.toFloat() / share.sizeBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = share.name, style = MaterialTheme.typography.titleMedium)
                    if (share.comment.isNotBlank()) {
                        Text(
                            text = share.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onOpenInFiles) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Open in Files")
                }
            }

            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(share.usedBytes)} / ${formatBytes(share.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle)
                )
                Text(
                    text = "${formatBytes(share.freeBytes)} free",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.throughput?.let { tp ->
                Text(
                    text = String.format(
                        Locale.US,
                        "%s · R: %.1f ops/s · W: %.1f ops/s",
                        tp.scopeLabel,
                        tp.readsPerSec,
                        tp.writesPerSec
                    ),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
