package com.unraiddesk.app.ui.docker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.core.network.DockerContainer
import com.unraiddesk.app.ui.shell.OfflineStatusBanner
import com.unraiddesk.app.ui.theme.LocalSemanticColors

@Composable
fun DockerScreen(
    uiState: DockerUiState,
    pendingCriticalAction: PendingCriticalAction?,
    onConfirmCriticalAction: () -> Unit,
    onDismissCriticalAction: () -> Unit,
    onRefresh: () -> Unit,
    onContainerAction: (DockerContainer, ContainerActionType) -> Unit,
    onToggleCritical: (String, Boolean) -> Unit,
    onCreateOrUpdateFolder: (Long?, String, Set<String>) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onToggleFolderExpanded: (Long, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingFolder by remember { mutableStateOf<DockerFolderGroup?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var logsTarget by remember { mutableStateOf<DockerContainer?>(null) }

    pendingCriticalAction?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissCriticalAction,
            title = { Text("Confirm ${pending.action.name.lowercase()} critical container") },
            text = {
                Text(
                    "\"${pending.container.name}\" is marked as a critical container. " +
                        "Are you sure you want to ${pending.action.name.lowercase()} it?"
                )
            },
            confirmButton = {
                Button(onClick = onConfirmCriticalAction) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCriticalAction) {
                    Text("Cancel")
                }
            }
        )
    }

    when (uiState) {
        is DockerUiState.Loading -> {
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
                            .height(88.dp)
                    ) {}
                }
            }
        }

        is DockerUiState.Error -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Unable to Load Docker", style = MaterialTheme.typography.titleLarge)
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

        is DockerUiState.Content -> {
            if (showCreateFolder) {
                FolderEditorDialog(
                    initialFolderName = "",
                    initialSelectedContainers = emptySet(),
                    allContainers = uiState.allContainers,
                    onSave = { name, selected ->
                        onCreateOrUpdateFolder(null, name, selected)
                        showCreateFolder = false
                    },
                    onDismiss = { showCreateFolder = false }
                )
            }

            editingFolder?.let { group ->
                FolderEditorDialog(
                    initialFolderName = group.folder.name,
                    initialSelectedContainers = group.containers.map { it.name }.toSet(),
                    allContainers = uiState.allContainers,
                    onSave = { name, selected ->
                        onCreateOrUpdateFolder(group.folder.id, name, selected)
                        editingFolder = null
                    },
                    onDismiss = { editingFolder = null }
                )
            }

            logsTarget?.let { target ->
                ContainerLogsSheet(
                    container = target,
                    onOpenWebGuiDocker = {
                        onOpenUrl("${uiState.serverBaseUrl}/Docker")
                    },
                    onDismiss = { logsTarget = null }
                )
            }

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
                                text = "Docker (${uiState.allContainers.size})",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Row {
                                IconButton(onClick = { showCreateFolder = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.CreateNewFolder,
                                        contentDescription = "Create Docker folder"
                                    )
                                }
                                IconButton(onClick = onRefresh) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "Refresh containers"
                                    )
                                }
                            }
                        }
                    }

                    // Prominent 1-click Unraid WebGUI (/Docker) & Apps (/Apps) deep links
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onOpenUrl("${uiState.serverBaseUrl}/Docker") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text("Unraid Docker WebGUI")
                            }
                            FilledTonalButton(
                                onClick = { onOpenUrl("${uiState.serverBaseUrl}/Apps") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text("Unraid Apps")
                            }
                        }
                    }

                    if (uiState.folders.isEmpty() && uiState.allContainers.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "No Docker containers found",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Install apps from the Unraid Community Applications (/Apps) WebGUI.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    items(uiState.folders, key = { "folder_${it.folder.id}" }) { group ->
                        DockerFolderCard(
                            group = group,
                            busyIds = uiState.busyContainerIds,
                            criticalNames = uiState.criticalContainerNames,
                            onToggleExpand = {
                                onToggleFolderExpanded(group.folder.id, !group.folder.isExpanded)
                            },
                            onEdit = { editingFolder = group },
                            onDelete = { onDeleteFolder(group.folder.id) },
                            onContainerAction = onContainerAction,
                            onToggleCritical = onToggleCritical,
                            onOpenWebUi = onOpenUrl,
                            onOpenLogs = { logsTarget = it }
                        )
                    }

                    if (uiState.ungroupedContainers.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Ungrouped Containers (${uiState.ungroupedContainers.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (uiState.folders.isEmpty()) {
                                    TextButton(onClick = { showCreateFolder = true }) {
                                        Text("Create Folder")
                                    }
                                }
                            }
                        }

                        items(uiState.ungroupedContainers, key = { it.id }) { container ->
                            ContainerRowCard(
                                container = container,
                                isBusy = container.id in uiState.busyContainerIds,
                                isCritical = container.name in uiState.criticalContainerNames,
                                onAction = { action -> onContainerAction(container, action) },
                                onToggleCritical = { onToggleCritical(container.name, it) },
                                onOpenWebUi = { container.webUiUrl?.let(onOpenUrl) },
                                onOpenLogs = { logsTarget = container }
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
private fun DockerFolderCard(
    group: DockerFolderGroup,
    busyIds: Set<String>,
    criticalNames: Set<String>,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onContainerAction: (DockerContainer, ContainerActionType) -> Unit,
    onToggleCritical: (String, Boolean) -> Unit,
    onOpenWebUi: (String) -> Unit,
    onOpenLogs: (DockerContainer) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (group.folder.isExpanded) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ExpandMore
                        },
                        contentDescription = if (group.folder.isExpanded) "Collapse folder" else "Expand folder"
                    )
                    Column {
                        Text(text = group.folder.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${group.runningCount}/${group.containers.size} running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(imageVector = Icons.Outlined.Edit, contentDescription = "Edit folder")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete folder")
                    }
                }
            }

            if (group.folder.isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    group.containers.forEach { container ->
                        ContainerRowCard(
                            container = container,
                            isBusy = container.id in busyIds,
                            isCritical = container.name in criticalNames,
                            onAction = { action -> onContainerAction(container, action) },
                            onToggleCritical = { onToggleCritical(container.name, it) },
                            onOpenWebUi = { container.webUiUrl?.let(onOpenWebUi) },
                            onOpenLogs = { onOpenLogs(container) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerRowCard(
    container: DockerContainer,
    isBusy: Boolean,
    isCritical: Boolean,
    onAction: (ContainerActionType) -> Unit,
    onToggleCritical: (Boolean) -> Unit,
    onOpenWebUi: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val statusColor = if (container.isRunning) semantic.running else semantic.error

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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column {
                        Text(text = container.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = container.status.ifBlank { container.state },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .padding(2.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        if (container.isRunning) {
                            IconButton(onClick = { onAction(ContainerActionType.STOP) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Stop,
                                    contentDescription = "Stop ${container.name}"
                                )
                            }
                            IconButton(onClick = { onAction(ContainerActionType.RESTART) }) {
                                Icon(
                                    imageVector = Icons.Outlined.RestartAlt,
                                    contentDescription = "Restart ${container.name}"
                                )
                            }
                        } else {
                            IconButton(onClick = { onAction(ContainerActionType.START) }) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "Start ${container.name}"
                                )
                            }
                        }
                    }

                    if (!container.webUiUrl.isNullOrBlank()) {
                        IconButton(onClick = onOpenWebUi) {
                            Icon(
                                imageVector = Icons.Outlined.OpenInBrowser,
                                contentDescription = "Open ${container.name} WebUI"
                            )
                        }
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = "View ${container.name} logs"
                        )
                    }
                    IconButton(onClick = { onToggleCritical(!isCritical) }) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = if (isCritical) {
                                "Unmark ${container.name} as critical"
                            } else {
                                "Mark ${container.name} as critical"
                            },
                            tint = if (isCritical) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
