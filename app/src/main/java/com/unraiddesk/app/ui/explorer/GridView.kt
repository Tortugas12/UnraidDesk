package com.unraiddesk.app.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.StorageProtocol

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridView(
    entries: List<FileEntry>,
    selectedPaths: Set<String>,
    isDesktop: Boolean,
    onSelectEntry: (FileEntry, Boolean) -> Unit,
    onOpenEntry: (FileEntry) -> Unit,
    onRenameEntry: (FileEntry) -> Unit,
    onCopyEntry: (FileEntry) -> Unit,
    onMoveEntry: (FileEntry) -> Unit,
    onDeleteEntry: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val hasActiveSelection = selectedPaths.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (isDesktop) 132.dp else 156.dp),
        contentPadding = PaddingValues(if (isDesktop) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (isDesktop) 10.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 10.dp else 12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(entries, key = { it.path }) { entry ->
            val isSelected = entry.path in selectedPaths
            var menuExpanded by remember { mutableStateOf(false) }

            Box {
                Card(
                    border = if (isSelected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isDesktop) Modifier else Modifier.heightIn(min = 148.dp))
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
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(if (isDesktop) 10.dp else 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isImage = !entry.isDirectory && isImageFile(entry.name)
                            if (isImage && entry.protocol == StorageProtocol.LOCAL) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(entry.path)
                                        .size(256)
                                        .build(),
                                    contentDescription = entry.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (isDesktop) 72.dp else 88.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Outlined.Folder
                                    } else {
                                        Icons.Outlined.Description
                                    },
                                    contentDescription = entry.name,
                                    tint = if (entry.isDirectory) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(if (isDesktop) 48.dp else 64.dp)
                                )
                            }
                            Text(
                                text = entry.name,
                                style = if (isDesktop) {
                                    MaterialTheme.typography.bodySmall
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (!isDesktop && hasActiveSelection) {
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
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .size(22.dp)
                            )
                        }

                        if (!isDesktop) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "Actions for ${entry.name}",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
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
        }
    }
}

private fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}
