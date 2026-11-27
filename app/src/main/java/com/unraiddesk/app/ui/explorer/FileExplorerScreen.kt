package com.unraiddesk.app.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.files.transfer.TransferJobState
import com.unraiddesk.app.files.transfer.TransferStatus
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.StorageProtocol

@Composable
fun FileExplorerScreen(
    uiState: ExplorerUiState,
    transferJobs: List<TransferJobState>,
    isExpanded: Boolean,
    isDesktop: Boolean,
    focusPathBarSignal: Int,
    renameSelectedSignal: Int,
    onSelectMount: (String) -> Unit,
    onNavigateTo: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleDualPane: () -> Unit,
    onSelectPane: (PaneSlot) -> Unit,
    onViewModeChange: (ExplorerViewMode) -> Unit,
    onSortChange: (SortField) -> Unit,
    onSelectEntry: (FileEntry, Boolean) -> Unit,
    onOpenQuickLook: (FileEntry?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameEntry: (FileEntry, String) -> Unit,
    onDeleteEntries: (List<FileEntry>) -> Unit,
    onCopyToOpposite: () -> Unit,
    onMoveToOpposite: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onClearCompletedTransfers: () -> Unit,
    onAddCustomNetworkMount: (
        protocol: StorageProtocol,
        label: String,
        hostOrUrl: String,
        port: Int,
        shareOrPath: String,
        username: String,
        password: String,
        useTls: Boolean
    ) -> Unit,
    onPickSafFolder: () -> Unit,
    onTogglePinLocation: (String) -> Unit,
    onSetLocationHidden: (String, Boolean) -> Unit,
    onRenameLocation: (String, String) -> Unit,
    onResetLocationName: (String) -> Unit,
    onSetShowHiddenLocations: (Boolean) -> Unit,
    onToggleExpandedSidebar: () -> Unit,
    onNavigateBackOrUp: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showConnectDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var pendingDeleteEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var compactSidebarOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            compactSidebarOpen = false
        }
    }

    val activeTab = uiState.focusedTab
    val activeMount = uiState.mounts.firstOrNull { it.id == activeTab.mountId }
    val selectedEntries = remember(activeTab.entries, activeTab.selectedPaths) {
        activeTab.entries.filter { it.path in activeTab.selectedPaths }
    }

    // Scoped BackHandlers in LIFO priority order (outermost -> innermost so innermost wins):
    // 1. Navigate back in history / step up to parent
    // 2. Clear active file selection
    // 3. Dismiss compact Locations sidebar overlay
    BackHandler(enabled = activeTab.canNavigateBackOrUp) {
        onNavigateBackOrUp()
    }
    BackHandler(enabled = activeTab.selectedPaths.isNotEmpty()) {
        onClearSelection()
    }
    BackHandler(enabled = !isExpanded && compactSidebarOpen) {
        compactSidebarOpen = false
    }
    var pathInput by remember(activeTab.id, activeTab.currentPath) {
        mutableStateOf(activeTab.currentPath)
    }
    val pathFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusPathBarSignal) {
        if (focusPathBarSignal > 0) {
            runCatching { pathFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(renameSelectedSignal) {
        if (renameSelectedSignal > 0) {
            val selected = activeTab.entries.firstOrNull { it.path in activeTab.selectedPaths }
            if (selected != null) {
                renameTarget = selected
            }
        }
    }

    if (showConnectDialog) {
        ConnectServerDialog(
            onConnectNetwork = onAddCustomNetworkMount,
            onPickSafFolder = onPickSafFolder,
            onDismiss = { showConnectDialog = false }
        )
    }

    if (showNewFolderDialog) {
        SingleInputDialog(
            title = "New Folder",
            label = "Folder Name",
            initialValue = "",
            onConfirm = { name ->
                onCreateFolder(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    renameTarget?.let { entry ->
        SingleInputDialog(
            title = "Rename",
            label = "New Name",
            initialValue = entry.name,
            onConfirm = { newName ->
                onRenameEntry(entry, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    if (pendingDeleteEntries.isNotEmpty()) {
        val count = pendingDeleteEntries.size
        AlertDialog(
            onDismissRequest = { pendingDeleteEntries = emptyList() },
            title = { Text("Delete $count ${if (count == 1) "item" else "items"}?") },
            text = {
                Text(
                    if (count == 1) {
                        "Are you sure you want to delete \"${pendingDeleteEntries.first().name}\"?"
                    } else {
                        "Are you sure you want to delete $count selected items?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteEntries(pendingDeleteEntries)
                        pendingDeleteEntries = emptyList()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntries = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.quickLookEntry != null && uiState.quickLookProvider != null) {
        QuickLookDialog(
            entry = uiState.quickLookEntry,
            provider = uiState.quickLookProvider,
            onDismiss = { onOpenQuickLook(null) }
        )
    }

    val isSidebarOpen = if (isExpanded) !uiState.isExpandedSidebarCollapsed else compactSidebarOpen
    val toolbarButtonModifier = if (isExpanded) Modifier else Modifier.size(36.dp)
    val toolbarIconModifier = if (isExpanded) Modifier else Modifier.size(20.dp)

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isExpanded) 8.dp else 4.dp,
                        vertical = if (isExpanded) 6.dp else 4.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isExpanded) 4.dp else 2.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isExpanded) {
                            onToggleExpandedSidebar()
                        } else {
                            compactSidebarOpen = !compactSidebarOpen
                        }
                    },
                    modifier = toolbarButtonModifier
                ) {
                    Icon(
                        imageVector = if (isSidebarOpen) {
                            Icons.AutoMirrored.Outlined.MenuOpen
                        } else {
                            Icons.Outlined.Menu
                        },
                        contentDescription = if (isSidebarOpen) "Collapse locations" else "Show locations",
                        modifier = toolbarIconModifier
                    )
                }
                IconButton(
                    onClick = onNavigateBackOrUp,
                    enabled = activeTab.canNavigateBackOrUp,
                    modifier = toolbarButtonModifier
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        modifier = toolbarIconModifier
                    )
                }
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    singleLine = true,
                    placeholder = { Text("/") },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(pathFocusRequester),
                    trailingIcon = {
                        TextButton(
                            onClick = { onNavigateTo(pathInput) },
                            contentPadding = PaddingValues(horizontal = if (isExpanded) 10.dp else 6.dp)
                        ) {
                            Text("Go")
                        }
                    }
                )
                IconButton(
                    onClick = { showNewFolderDialog = true },
                    modifier = toolbarButtonModifier
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = "New Folder",
                        modifier = toolbarIconModifier
                    )
                }
                IconButton(
                    onClick = {
                        val next = if (activeTab.viewMode == ExplorerViewMode.DETAILS) {
                            ExplorerViewMode.GRID
                        } else {
                            ExplorerViewMode.DETAILS
                        }
                        onViewModeChange(next)
                    },
                    modifier = toolbarButtonModifier
                ) {
                    Icon(
                        imageVector = if (activeTab.viewMode == ExplorerViewMode.DETAILS) {
                            Icons.Outlined.GridView
                        } else {
                            Icons.AutoMirrored.Outlined.ViewList
                        },
                        contentDescription = "Toggle Details/Grid View",
                        modifier = toolbarIconModifier
                    )
                }
                if (isExpanded) {
                    IconButton(onClick = onToggleDualPane) {
                        Icon(
                            imageVector = Icons.Outlined.Splitscreen,
                            contentDescription = "Toggle Dual-Pane Commander (F3)"
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = toolbarButtonModifier
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh directory",
                        modifier = toolbarIconModifier
                    )
                }
            }
        }
        HorizontalDivider()

        // Contextual multi-select action bar on phone/tablet touch devices
        if (!isDesktop && selectedEntries.isNotEmpty()) {
            SelectionActionBar(
                selectedCount = selectedEntries.size,
                canRename = selectedEntries.size == 1,
                isDualPane = uiState.isDualPane,
                onClearSelection = onClearSelection,
                onSelectAll = onSelectAll,
                onRename = { selectedEntries.firstOrNull()?.let { renameTarget = it } },
                onCopyToOpposite = onCopyToOpposite,
                onMoveToOpposite = onMoveToOpposite,
                onRequestDelete = { pendingDeleteEntries = selectedEntries }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Compact active-location cue bar
        if (!isExpanded && activeMount != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { compactSidebarOpen = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = mountIcon(activeMount),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = activeMount.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Locations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "Open locations",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (isExpanded) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedVisibility(
                    visible = !uiState.isExpandedSidebarCollapsed,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut()
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        LocationsSidebar(
                            mounts = uiState.mounts,
                            selectedMountId = activeTab.mountId,
                            showHiddenLocations = uiState.showHiddenLocations,
                            isCompactOverlay = false,
                            onSelectMount = onSelectMount,
                            onConnectServer = { showConnectDialog = true },
                            onCollapse = onToggleExpandedSidebar,
                            onTogglePin = onTogglePinLocation,
                            onSetHidden = onSetLocationHidden,
                            onRename = onRenameLocation,
                            onResetName = onResetLocationName,
                            onToggleShowHidden = onSetShowHiddenLocations,
                            modifier = Modifier
                                .width(232.dp)
                                .fillMaxHeight()
                        )
                        VerticalDivider()
                    }
                }

                ExplorerMainContent(
                    uiState = uiState,
                    transferJobs = transferJobs,
                    isDesktop = isDesktop,
                    isExpanded = isExpanded,
                    onNavigateTo = onNavigateTo,
                    onRefresh = onRefresh,
                    onSelectPane = onSelectPane,
                    onSortChange = onSortChange,
                    onSelectEntry = onSelectEntry,
                    onOpenQuickLook = onOpenQuickLook,
                    onRequestRename = { renameTarget = it },
                    onCopyToOpposite = onCopyToOpposite,
                    onMoveToOpposite = onMoveToOpposite,
                    onDeleteEntries = onDeleteEntries,
                    onCopyPath = { clipboard.setText(AnnotatedString(it.path)) },
                    onCancelTransfer = onCancelTransfer,
                    onClearCompletedTransfers = onClearCompletedTransfers,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ExplorerMainContent(
                    uiState = uiState,
                    transferJobs = transferJobs,
                    isDesktop = isDesktop,
                    isExpanded = isExpanded,
                    onNavigateTo = onNavigateTo,
                    onRefresh = onRefresh,
                    onSelectPane = onSelectPane,
                    onSortChange = onSortChange,
                    onSelectEntry = onSelectEntry,
                    onOpenQuickLook = onOpenQuickLook,
                    onRequestRename = { renameTarget = it },
                    onCopyToOpposite = onCopyToOpposite,
                    onMoveToOpposite = onMoveToOpposite,
                    onDeleteEntries = onDeleteEntries,
                    onCopyPath = { clipboard.setText(AnnotatedString(it.path)) },
                    onCancelTransfer = onCancelTransfer,
                    onClearCompletedTransfers = onClearCompletedTransfers,
                    modifier = Modifier.fillMaxSize()
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = compactSidebarOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                compactSidebarOpen = false
                            }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = compactSidebarOpen,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it }
                ) {
                    LocationsSidebar(
                        mounts = uiState.mounts,
                        selectedMountId = activeTab.mountId,
                        showHiddenLocations = uiState.showHiddenLocations,
                        isCompactOverlay = true,
                        onSelectMount = { mountId ->
                            onSelectMount(mountId)
                            compactSidebarOpen = false
                        },
                        onConnectServer = {
                            compactSidebarOpen = false
                            showConnectDialog = true
                        },
                        onCollapse = { compactSidebarOpen = false },
                        onTogglePin = onTogglePinLocation,
                        onSetHidden = onSetLocationHidden,
                        onRename = onRenameLocation,
                        onResetName = onResetLocationName,
                        onToggleShowHidden = onSetShowHiddenLocations,
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .widthIn(min = 240.dp, max = 280.dp)
                            .fillMaxWidth(0.78f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    canRename: Boolean,
    isDualPane: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: () -> Unit,
    onCopyToOpposite: () -> Unit,
    onMoveToOpposite: () -> Unit,
    onRequestDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Clear selection"
                )
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Outlined.SelectAll,
                    contentDescription = "Select all"
                )
            }
            if (canRename) {
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Rename selected"
                    )
                }
            }
            if (isDualPane) {
                IconButton(onClick = onCopyToOpposite) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy to opposite pane"
                    )
                }
                IconButton(onClick = onMoveToOpposite) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                        contentDescription = "Move to opposite pane"
                    )
                }
            }
            IconButton(onClick = onRequestDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete selected",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ExplorerMainContent(
    uiState: ExplorerUiState,
    transferJobs: List<TransferJobState>,
    isDesktop: Boolean,
    isExpanded: Boolean,
    onNavigateTo: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectPane: (PaneSlot) -> Unit,
    onSortChange: (SortField) -> Unit,
    onSelectEntry: (FileEntry, Boolean) -> Unit,
    onOpenQuickLook: (FileEntry) -> Unit,
    onRequestRename: (FileEntry) -> Unit,
    onCopyToOpposite: () -> Unit,
    onMoveToOpposite: () -> Unit,
    onDeleteEntries: (List<FileEntry>) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onClearCompletedTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (uiState.isDualPane && uiState.secondaryTab != null) {
                DualPaneLayout(
                    activePane = uiState.activePane,
                    primaryTitle = "${uiState.activeTab.title}: /${uiState.activeTab.currentPath}",
                    secondaryTitle = "${uiState.secondaryTab.title}: /${uiState.secondaryTab.currentPath}",
                    onSelectPane = onSelectPane,
                    onCopyToOpposite = onCopyToOpposite,
                    onMoveToOpposite = onMoveToOpposite,
                    primaryContent = {
                        PaneContent(
                            tab = uiState.activeTab,
                            isDesktop = isDesktop,
                            isExpanded = isExpanded,
                            onNavigateTo = onNavigateTo,
                            onRefresh = onRefresh,
                            onSortChange = onSortChange,
                            onSelectEntry = onSelectEntry,
                            onOpenQuickLook = onOpenQuickLook,
                            onRenameEntry = onRequestRename,
                            onCopyEntry = {
                                onSelectEntry(it, true)
                                onCopyToOpposite()
                            },
                            onMoveEntry = {
                                onSelectEntry(it, true)
                                onMoveToOpposite()
                            },
                            onDeleteEntry = { onDeleteEntries(listOf(it)) },
                            onCopyPath = onCopyPath
                        )
                    },
                    secondaryContent = {
                        PaneContent(
                            tab = uiState.secondaryTab,
                            isDesktop = isDesktop,
                            isExpanded = isExpanded,
                            onNavigateTo = onNavigateTo,
                            onRefresh = onRefresh,
                            onSortChange = onSortChange,
                            onSelectEntry = onSelectEntry,
                            onOpenQuickLook = onOpenQuickLook,
                            onRenameEntry = onRequestRename,
                            onCopyEntry = {
                                onSelectEntry(it, true)
                                onCopyToOpposite()
                            },
                            onMoveEntry = {
                                onSelectEntry(it, true)
                                onMoveToOpposite()
                            },
                            onDeleteEntry = { onDeleteEntries(listOf(it)) },
                            onCopyPath = onCopyPath
                        )
                    }
                )
            } else {
                PaneContent(
                    tab = uiState.activeTab,
                    isDesktop = isDesktop,
                    isExpanded = isExpanded,
                    onNavigateTo = onNavigateTo,
                    onRefresh = onRefresh,
                    onSortChange = onSortChange,
                    onSelectEntry = onSelectEntry,
                    onOpenQuickLook = onOpenQuickLook,
                    onRenameEntry = onRequestRename,
                    onCopyEntry = {
                        onSelectEntry(it, true)
                        onCopyToOpposite()
                    },
                    onMoveEntry = {
                        onSelectEntry(it, true)
                        onMoveToOpposite()
                    },
                    onDeleteEntry = { onDeleteEntries(listOf(it)) },
                    onCopyPath = onCopyPath
                )
            }
        }

        if (transferJobs.isNotEmpty()) {
            TransferProgressDrawer(
                jobs = transferJobs,
                onCancel = onCancelTransfer,
                onClearCompleted = onClearCompletedTransfers
            )
        }
    }
}

@Composable
private fun PaneContent(
    tab: ExplorerTab,
    isDesktop: Boolean,
    isExpanded: Boolean,
    onNavigateTo: (String) -> Unit,
    onRefresh: () -> Unit,
    onSortChange: (SortField) -> Unit,
    onSelectEntry: (FileEntry, Boolean) -> Unit,
    onOpenQuickLook: (FileEntry) -> Unit,
    onRenameEntry: (FileEntry) -> Unit,
    onCopyEntry: (FileEntry) -> Unit,
    onMoveEntry: (FileEntry) -> Unit,
    onDeleteEntry: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit
) {
    when {
        tab.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        tab.errorMessage != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = tab.errorMessage, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Retry")
                }
            }
        }
        tab.entries.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Folder is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        tab.viewMode == ExplorerViewMode.GRID -> {
            GridView(
                entries = tab.entries,
                selectedPaths = tab.selectedPaths,
                isDesktop = isDesktop,
                onSelectEntry = onSelectEntry,
                onOpenEntry = { entry ->
                    if (entry.isDirectory) onNavigateTo(entry.path) else onOpenQuickLook(entry)
                },
                onRenameEntry = onRenameEntry,
                onCopyEntry = onCopyEntry,
                onMoveEntry = onMoveEntry,
                onDeleteEntry = onDeleteEntry,
                onCopyPath = onCopyPath
            )
        }
        else -> {
            DetailsView(
                entries = tab.entries,
                selectedPaths = tab.selectedPaths,
                sortField = tab.sortField,
                sortAscending = tab.sortAscending,
                isDesktop = isDesktop,
                isExpanded = isExpanded,
                onSortChange = onSortChange,
                onSelectEntry = onSelectEntry,
                onOpenEntry = { entry ->
                    if (entry.isDirectory) onNavigateTo(entry.path) else onOpenQuickLook(entry)
                },
                onRenameEntry = onRenameEntry,
                onCopyEntry = onCopyEntry,
                onMoveEntry = onMoveEntry,
                onDeleteEntry = onDeleteEntry,
                onCopyPath = onCopyPath
            )
        }
    }
}

@Composable
private fun TransferProgressDrawer(
    jobs: List<TransferJobState>,
    onCancel: (String) -> Unit,
    onClearCompleted: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transfers (${jobs.size})",
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(onClick = onClearCompleted) {
                    Text("Clear Finished")
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                items(jobs, key = { it.id }) { job ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${if (job.isMove) "Move" else "Copy"}: ${job.fileName} (${job.status.name})",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            LinearProgressIndicator(
                                progress = { job.progressFraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                            )
                        }
                        if (job.status == TransferStatus.RUNNING || job.status == TransferStatus.QUEUED) {
                            TextButton(onClick = { onCancel(job.id) }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
