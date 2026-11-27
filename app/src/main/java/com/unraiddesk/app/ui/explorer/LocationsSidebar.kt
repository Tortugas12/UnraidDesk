package com.unraiddesk.app.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.files.vfs.StorageProtocol

internal fun Modifier.onSecondaryClick(key: Any, onRightClick: () -> Unit): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onRightClick()
                }
            }
        }
    }

internal fun mountIcon(mount: StorageMount): ImageVector = when {
    mount.isUnraidShare -> Icons.Outlined.Dns
    mount.protocol == StorageProtocol.LOCAL -> Icons.Outlined.Smartphone
    mount.protocol == StorageProtocol.SAF -> Icons.Outlined.Cloud
    else -> Icons.Outlined.Storage
}

@Composable
fun LocationsSidebar(
    mounts: List<StorageMount>,
    selectedMountId: String,
    showHiddenLocations: Boolean,
    isCompactOverlay: Boolean,
    onSelectMount: (String) -> Unit,
    onConnectServer: () -> Unit,
    onCollapse: () -> Unit,
    onTogglePin: (String) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onResetName: (String) -> Unit,
    onToggleShowHidden: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp
) {
    var renameTarget by remember { mutableStateOf<StorageMount?>(null) }

    renameTarget?.let { mount ->
        RenameLocationDialog(
            mount = mount,
            onConfirm = { newName ->
                onRename(mount.id, newName)
                renameTarget = null
            },
            onReset = {
                onResetName(mount.id)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    val hiddenCount = mounts.count { it.isHidden }
    val partitioned = remember(mounts, showHiddenLocations) {
        partitionLocations(mounts, showHiddenLocations)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Locations",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onConnectServer) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Connect storage or cloud"
                        )
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = if (isCompactOverlay) {
                                Icons.Outlined.Close
                            } else {
                                Icons.AutoMirrored.Outlined.MenuOpen
                            },
                            contentDescription = if (isCompactOverlay) {
                                "Close locations"
                            } else {
                                "Collapse locations"
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Body
            if (partitioned.isEmpty && hiddenCount > 0) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "All locations are hidden",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { onToggleShowHidden(true) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Show hidden")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (partitioned.pinned.isNotEmpty()) {
                        item(key = "header_pinned") {
                            SectionHeader(title = "Pinned")
                        }
                        items(partitioned.pinned, key = { "pinned_${it.id}" }) { mount ->
                            LocationRow(
                                mount = mount,
                                isSelected = mount.id == selectedMountId,
                                onSelect = { onSelectMount(mount.id) },
                                onTogglePin = { onTogglePin(mount.id) },
                                onRequestRename = { renameTarget = mount },
                                onSetHidden = { hidden -> onSetHidden(mount.id, hidden) }
                            )
                        }
                        if (partitioned.others.isNotEmpty()) {
                            item(key = "divider_pinned") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }

                    if (partitioned.others.isNotEmpty()) {
                        if (partitioned.pinned.isNotEmpty()) {
                            item(key = "header_locations") {
                                SectionHeader(title = "Locations")
                            }
                        }
                        items(partitioned.others, key = { it.id }) { mount ->
                            LocationRow(
                                mount = mount,
                                isSelected = mount.id == selectedMountId,
                                onSelect = { onSelectMount(mount.id) },
                                onTogglePin = { onTogglePin(mount.id) },
                                onRequestRename = { renameTarget = mount },
                                onSetHidden = { hidden -> onSetHidden(mount.id, hidden) }
                            )
                        }
                    }
                }
            }

            // Footer: Show hidden toggle
            if (hiddenCount > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleShowHidden(!showHiddenLocations) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (showHiddenLocations) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (showHiddenLocations) {
                            "Hide hidden ($hiddenCount)"
                        } else {
                            "Show hidden ($hiddenCount)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocationRow(
    mount: StorageMount,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestRename: () -> Unit,
    onSetHidden: (Boolean) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box {
        Surface(
            color = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (mount.isHidden) 0.6f else 1f)
                .onSecondaryClick(mount.id) { menuExpanded = true }
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = mountIcon(mount),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = mount.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (mount.isPinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = "Pinned",
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (mount.isHidden) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = "Hidden",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (mount.isPinned) "Unpin" else "Pin to top") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.PushPin, contentDescription = null)
                },
                onClick = {
                    menuExpanded = false
                    onTogglePin()
                }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                },
                onClick = {
                    menuExpanded = false
                    onRequestRename()
                }
            )
            DropdownMenuItem(
                text = { Text(if (mount.isHidden) "Unhide location" else "Hide location") },
                leadingIcon = {
                    Icon(
                        imageVector = if (mount.isHidden) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = null
                    )
                },
                onClick = {
                    menuExpanded = false
                    onSetHidden(!mount.isHidden)
                }
            )
        }
    }
}

@Composable
private fun RenameLocationDialog(
    mount: StorageMount,
    onConfirm: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(mount.id, mount.label) { mutableStateOf(mount.label) }
    val trimmed = value.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mount.customLabel != null) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Reset to default (${mount.defaultLabel})")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
