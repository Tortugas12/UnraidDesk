package com.unraiddesk.app.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.ui.theme.TabularNumericStyle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsView(
    entries: List<FileEntry>,
    selectedPaths: Set<String>,
    sortField: SortField,
    sortAscending: Boolean,
    isDesktop: Boolean,
    isExpanded: Boolean,
    onSortChange: (SortField) -> Unit,
    onSelectEntry: (FileEntry, Boolean) -> Unit,
    onOpenEntry: (FileEntry) -> Unit,
    onRenameEntry: (FileEntry) -> Unit,
    onCopyEntry: (FileEntry) -> Unit,
    onMoveEntry: (FileEntry) -> Unit,
    onDeleteEntry: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val haptic = LocalHapticFeedback.current
    val hasActiveSelection = selectedPaths.isNotEmpty()
    val isPhoneTouch = !isDesktop && !isExpanded

    Column(modifier = modifier.fillMaxSize()) {
        if (isPhoneTouch) {
            PhoneSortBar(
                sortField = sortField,
                sortAscending = sortAscending,
                onSortChange = onSortChange
            )
        } else {
            TableSortHeader(
                sortField = sortField,
                sortAscending = sortAscending,
                onSortChange = onSortChange
            )
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { entry ->
                val isSelected = entry.path in selectedPaths
                var menuExpanded by remember { mutableStateOf(false) }

                val rowBackground = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                val rowClickModifier = Modifier
                    .fillMaxWidth()
                    .background(rowBackground)
                    .combinedClickable(
                        onClick = {
                            when (resolveEntryTapAction(isDesktop, hasActiveSelection)) {
                                EntryTapAction.OPEN -> onOpenEntry(entry)
                                EntryTapAction.TOGGLE_SELECTION -> onSelectEntry(entry, false)
                            }
                        },
                        onDoubleClick = if (shouldEnableDoubleClick(isDesktop)) {
                            { onOpenEntry(entry) }
                        } else {
                            null
                        },
                        onLongClick = {
                            when (resolveEntryLongPressAction(isDesktop, hasActiveSelection)) {
                                EntryLongPressAction.OPEN_CONTEXT_MENU -> menuExpanded = true
                                EntryLongPressAction.TOGGLE_SELECTION -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelectEntry(entry, false)
                                }
                            }
                        }
                    )

                Box {
                    if (isPhoneTouch) {
                        PhoneTouchRow(
                            entry = entry,
                            isSelected = isSelected,
                            showSelectionIndicator = hasActiveSelection,
                            formattedDate = formatEntryDate(entry.modifiedMillis, dateFormat),
                            onOpenMenu = { menuExpanded = true },
                            modifier = rowClickModifier
                        )
                    } else {
                        TableEntryRow(
                            entry = entry,
                            isDesktop = isDesktop,
                            isSelected = isSelected,
                            showSelectionIndicator = !isDesktop && hasActiveSelection,
                            formattedDate = formatEntryDate(entry.modifiedMillis, dateFormat),
                            onOpenMenu = { menuExpanded = true },
                            modifier = rowClickModifier
                        )
                    }

                    EntryContextMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onOpen = { menuExpanded = false; onOpenEntry(entry) },
                        onRename = { menuExpanded = false; onRenameEntry(entry) },
                        onCopy = { menuExpanded = false; onCopyEntry(entry) },
                        onMove = { menuExpanded = false; onMoveEntry(entry) },
                        onDelete = { menuExpanded = false; onDeleteEntry(entry) },
                        onCopyPath = { menuExpanded = false; onCopyPath(entry) }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun PhoneSortBar(
    sortField: SortField,
    sortAscending: Boolean,
    onSortChange: (SortField) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                SortField.NAME to "Name",
                SortField.MODIFIED to "Modified",
                SortField.TYPE to "Type",
                SortField.SIZE to "Size"
            ).forEach { (field, label) ->
                val active = sortField == field
                FilterChip(
                    selected = active,
                    onClick = { onSortChange(field) },
                    label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = if (active) {
                        {
                            Icon(
                                imageVector = if (sortAscending) {
                                    Icons.Outlined.ArrowUpward
                                } else {
                                    Icons.Outlined.ArrowDownward
                                },
                                contentDescription = if (sortAscending) "Sorted ascending" else "Sorted descending",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun TableSortHeader(
    sortField: SortField,
    sortAscending: Boolean,
    onSortChange: (SortField) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SortHeaderButton(
                label = "Name",
                active = sortField == SortField.NAME,
                ascending = sortAscending,
                onClick = { onSortChange(SortField.NAME) },
                modifier = Modifier.weight(2.2f)
            )
            SortHeaderButton(
                label = "Modified",
                active = sortField == SortField.MODIFIED,
                ascending = sortAscending,
                onClick = { onSortChange(SortField.MODIFIED) },
                modifier = Modifier.weight(1.2f)
            )
            SortHeaderButton(
                label = "Type",
                active = sortField == SortField.TYPE,
                ascending = sortAscending,
                onClick = { onSortChange(SortField.TYPE) },
                modifier = Modifier.weight(0.8f)
            )
            SortHeaderButton(
                label = "Size",
                active = sortField == SortField.SIZE,
                ascending = sortAscending,
                onClick = { onSortChange(SortField.SIZE) },
                modifier = Modifier.weight(0.9f)
            )
        }
    }
}

@Composable
private fun PhoneTouchRow(
    entry: FileEntry,
    isSelected: Boolean,
    showSelectionIndicator: Boolean,
    formattedDate: String,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = if (entry.isDirectory) {
        if (formattedDate == "--") "Folder" else "Folder · $formattedDate"
    } else {
        val ext = entry.name.substringAfterLast('.', "File").uppercase(Locale.US)
        val size = formatSize(entry.sizeBytes)
        if (formattedDate == "--") "$ext · $size" else "$ext · $size · $formattedDate"
    }

    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showSelectionIndicator) {
            SelectionIndicatorIcon(isSelected = isSelected)
        }
        Icon(
            imageVector = if (entry.isDirectory) {
                Icons.Outlined.Folder
            } else {
                Icons.Outlined.Description
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(32.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "Actions for ${entry.name}",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun TableEntryRow(
    entry: FileEntry,
    isDesktop: Boolean,
    isSelected: Boolean,
    showSelectionIndicator: Boolean,
    formattedDate: String,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowModifier = if (isDesktop) {
        modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        modifier
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(2.2f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 8.dp else 12.dp)
        ) {
            if (showSelectionIndicator) {
                SelectionIndicatorIcon(isSelected = isSelected)
            }
            Icon(
                imageVector = if (entry.isDirectory) {
                    Icons.Outlined.Folder
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(if (isDesktop) 18.dp else 28.dp)
            )
            Text(
                text = entry.name,
                style = if (isDesktop) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formattedDate,
            style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1.2f)
        )
        Text(
            text = if (entry.isDirectory) {
                "Folder"
            } else {
                entry.name.substringAfterLast('.', "File").uppercase(Locale.US)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(0.8f)
        )
        Row(
            modifier = Modifier.weight(0.9f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (entry.isDirectory) "--" else formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumericStyle),
                maxLines = 1
            )
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(if (isDesktop) 24.dp else 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Actions for ${entry.name}",
                    modifier = Modifier.size(if (isDesktop) 16.dp else 22.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectionIndicatorIcon(isSelected: Boolean) {
    Icon(
        imageVector = if (isSelected) {
            Icons.Outlined.CheckCircle
        } else {
            Icons.Outlined.RadioButtonUnchecked
        },
        contentDescription = if (isSelected) "Selected" else "Not selected",
        tint = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(24.dp)
    )
}

@Composable
fun EntryContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Open") }, onClick = onOpen)
        DropdownMenuItem(text = { Text("Rename (F2)") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Copy to Other Pane (F5)") }, onClick = onCopy)
        DropdownMenuItem(text = { Text("Move to Other Pane (F6)") }, onClick = onMove)
        DropdownMenuItem(text = { Text("Copy Path") }, onClick = onCopyPath)
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Delete (Del)") }, onClick = onDelete)
    }
}

@Composable
private fun SortHeaderButton(
    label: String,
    active: Boolean,
    ascending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (active) {
            Icon(
                imageVector = if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                contentDescription = if (ascending) "Sorted ascending" else "Sorted descending",
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(14.dp)
            )
        }
    }
}

private fun formatEntryDate(modifiedMillis: Long, dateFormat: DateFormat): String =
    if (modifiedMillis > 0L) dateFormat.format(Date(modifiedMillis)) else "--"

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}
