package com.unraiddesk.app.ui.explorer

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.db.ShareMountDao
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.network.OkHttpFactory
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.files.transfer.ConflictPolicy
import com.unraiddesk.app.files.transfer.TransferJobState
import com.unraiddesk.app.files.transfer.TransferQueueManager
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.FileSystemProvider
import com.unraiddesk.app.files.vfs.FtpFileSystem
import com.unraiddesk.app.files.vfs.LocalFileSystem
import com.unraiddesk.app.files.vfs.PathGuard
import com.unraiddesk.app.files.vfs.SafFileSystem
import com.unraiddesk.app.files.vfs.SmbFileSystem
import com.unraiddesk.app.files.vfs.StorageProtocol
import com.unraiddesk.app.files.vfs.WebDavFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class ExplorerViewMode { DETAILS, GRID }
enum class SortField { NAME, MODIFIED, TYPE, SIZE }
enum class PaneSlot { PRIMARY, SECONDARY }

data class StorageMount(
    val id: String,
    val defaultLabel: String,
    val customLabel: String? = null,
    val protocol: StorageProtocol,
    val isUnraidShare: Boolean = false,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val provider: FileSystemProvider
) {
    val label: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: defaultLabel
}

internal data class PartitionedLocations(
    val pinned: List<StorageMount>,
    val others: List<StorageMount>
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
}

internal fun partitionLocations(
    mounts: List<StorageMount>,
    showHidden: Boolean
): PartitionedLocations {
    val visible = if (showHidden) mounts else mounts.filterNot { it.isHidden }
    val (pinned, others) = visible.partition { it.isPinned }
    return PartitionedLocations(pinned = pinned, others = others)
}

internal fun findUnraidShareMount(
    mounts: List<StorageMount>,
    shareName: String
): StorageMount? {
    val exactId = "smb_unraid_$shareName"
    return mounts.firstOrNull { it.id == exactId }
        ?: mounts.firstOrNull { it.isUnraidShare && it.defaultLabel.equals(shareName, ignoreCase = true) }
}

data class ExplorerLocation(
    val mountId: String,
    val path: String
)

data class ExplorerTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Files",
    val mountId: String = "local",
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val sortField: SortField = SortField.NAME,
    val sortAscending: Boolean = true,
    val viewMode: ExplorerViewMode = ExplorerViewMode.DETAILS,
    val historyBack: List<ExplorerLocation> = emptyList(),
    val historyForward: List<ExplorerLocation> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val canNavigateBackOrUp: Boolean
        get() = historyBack.isNotEmpty() || currentPath.isNotEmpty()
}

internal fun pushLocationHistory(
    tab: ExplorerTab,
    targetMountId: String,
    targetMountTitle: String,
    targetPath: String
): ExplorerTab {
    val cleanPath = PathGuard.normalizeRelativePath(targetPath)
    if (tab.mountId == targetMountId && tab.currentPath == cleanPath) {
        return tab
    }
    return tab.copy(
        title = targetMountTitle,
        mountId = targetMountId,
        currentPath = cleanPath,
        selectedPaths = emptySet(),
        historyBack = tab.historyBack + ExplorerLocation(tab.mountId, tab.currentPath),
        historyForward = emptyList()
    )
}

internal fun resolveBackStep(
    tab: ExplorerTab,
    mountsById: Map<String, StorageMount>
): ExplorerTab? {
    if (tab.currentPath.isNotEmpty()) {
        val parent = tab.currentPath.substringBeforeLast('/', "")
        val trimmedHistory = tab.historyBack.dropLastWhile { it.mountId == tab.mountId }
        return tab.copy(
            historyBack = trimmedHistory,
            historyForward = tab.historyForward + ExplorerLocation(tab.mountId, tab.currentPath),
            currentPath = parent,
            selectedPaths = emptySet()
        )
    }

    for (idx in tab.historyBack.lastIndex downTo 0) {
        val target = tab.historyBack[idx]
        val targetMount = mountsById[target.mountId] ?: continue
        return tab.copy(
            historyBack = tab.historyBack.take(idx),
            historyForward = tab.historyForward + ExplorerLocation(tab.mountId, tab.currentPath),
            mountId = target.mountId,
            title = targetMount.label,
            currentPath = target.path,
            selectedPaths = emptySet()
        )
    }

    return null
}

data class ExplorerUiState(
    val mounts: List<StorageMount> = emptyList(),
    val tabs: List<ExplorerTab> = listOf(ExplorerTab()),
    val activeTabId: String = "",
    val isDualPane: Boolean = false,
    val secondaryTab: ExplorerTab? = null,
    val activePane: PaneSlot = PaneSlot.PRIMARY,
    val quickLookEntry: FileEntry? = null,
    val quickLookProvider: FileSystemProvider? = null,
    val isExpandedSidebarCollapsed: Boolean = false,
    val showHiddenLocations: Boolean = false
) {
    val activeTab: ExplorerTab
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val focusedTab: ExplorerTab
        get() = if (isDualPane && activePane == PaneSlot.SECONDARY && secondaryTab != null) {
            secondaryTab
        } else {
            activeTab
        }

    val hiddenMountCount: Int
        get() = mounts.count { it.isHidden }
}

private data class LocationPrefsSnapshot(
    val pinnedIds: Set<String>,
    val hiddenIds: Set<String>,
    val displayNames: Map<String, String>,
    val sidebarCollapsed: Boolean
)

class FileExplorerViewModel(
    private val serverConfigDao: ServerConfigDao,
    private val shareMountDao: ShareMountDao,
    private val preferencesRepository: AppPreferencesRepository,
    private val cryptoManager: KeystoreCryptoManager,
    private val lanVpnGuard: LanVpnGuard,
    private val okHttpFactory: OkHttpFactory,
    private val transferQueueManager: TransferQueueManager,
    private val contentResolver: ContentResolver,
    localRootDir: File = Environment.getExternalStorageDirectory()
) : ViewModel() {

    private val localMount = StorageMount(
        id = "local",
        defaultLabel = "Device Storage",
        protocol = StorageProtocol.LOCAL,
        provider = LocalFileSystem(rootFile = localRootDir, rootId = "local", displayName = "Device Storage")
    )

    private val unraidShareMounts = MutableStateFlow<List<StorageMount>>(emptyList())
    private val customMounts = MutableStateFlow<List<StorageMount>>(emptyList())
    private val _uiState = MutableStateFlow(
        ExplorerUiState(
            mounts = listOf(localMount),
            tabs = listOf(ExplorerTab(id = "tab-1", title = "Device Storage", mountId = "local")),
            activeTabId = "tab-1"
        )
    )
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()
    val transferJobs: StateFlow<List<TransferJobState>> = transferQueueManager.jobs

    init {
        observeMountsAndPreferences()
        viewModelScope.launch {
            val dualDefault = preferencesRepository.dualPaneDefault.first()
            if (dualDefault) {
                val localTitle = _uiState.value.mounts.firstOrNull { it.id == localMount.id }?.label
                    ?: localMount.label
                _uiState.update { state ->
                    state.copy(
                        isDualPane = true,
                        secondaryTab = ExplorerTab(
                            id = "tab-secondary",
                            title = localTitle,
                            mountId = "local"
                        )
                    )
                }
            }
            loadUnraidShareMounts()
            refreshFocusedTab()
        }
    }

    private fun observeMountsAndPreferences() {
        val prefsFlow = combine(
            preferencesRepository.pinnedLocationIds,
            preferencesRepository.hiddenLocationIds,
            preferencesRepository.locationDisplayNames,
            preferencesRepository.explorerSidebarCollapsed
        ) { pinned, hidden, names, collapsed ->
            LocationPrefsSnapshot(
                pinnedIds = pinned,
                hiddenIds = hidden,
                displayNames = names,
                sidebarCollapsed = collapsed
            )
        }

        viewModelScope.launch {
            combine(
                unraidShareMounts,
                customMounts,
                prefsFlow
            ) { shares, custom, prefs ->
                val rawMounts = listOf(localMount) + shares + custom
                val decorated = rawMounts.map { mount ->
                    mount.copy(
                        customLabel = prefs.displayNames[mount.id],
                        isPinned = mount.id in prefs.pinnedIds,
                        isHidden = mount.id in prefs.hiddenIds
                    )
                }
                decorated to prefs.sidebarCollapsed
            }.collect { (decoratedMounts, sidebarCollapsed) ->
                val labelById = decoratedMounts.associate { it.id to it.label }
                _uiState.update { state ->
                    state.copy(
                        mounts = decoratedMounts,
                        isExpandedSidebarCollapsed = sidebarCollapsed,
                        tabs = state.tabs.map { tab ->
                            val updatedTitle = labelById[tab.mountId]
                            if (updatedTitle != null && updatedTitle != tab.title) {
                                tab.copy(title = updatedTitle)
                            } else {
                                tab
                            }
                        },
                        secondaryTab = state.secondaryTab?.let { sec ->
                            val updatedTitle = labelById[sec.mountId]
                            if (updatedTitle != null && updatedTitle != sec.title) {
                                sec.copy(title = updatedTitle)
                            } else {
                                sec
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun loadUnraidShareMounts() {
        val server = serverConfigDao.getPrimaryServer() ?: return
        val shareRows = shareMountDao.getForServer(server.id).filter { it.smbEnabled }
        val credsProvider: () -> SmbFileSystem.SmbCredentials? = {
            val user = server.encryptedSmbUsername?.let { cryptoManager.decryptString(it) }
            val pass = server.encryptedSmbPassword?.let { cryptoManager.decryptString(it) }
            if (!user.isNullOrBlank()) {
                SmbFileSystem.SmbCredentials(user, pass.orEmpty(), server.smbDomain)
            } else {
                null
            }
        }
        val shareMounts = shareRows.map { row ->
            val mountId = "smb_unraid_${row.shareName}"
            StorageMount(
                id = mountId,
                defaultLabel = row.shareName,
                protocol = StorageProtocol.SMB,
                isUnraidShare = true,
                provider = SmbFileSystem(
                    host = server.host,
                    shareName = row.shareName,
                    lanVpnGuard = lanVpnGuard,
                    credentialProvider = credsProvider,
                    rootId = mountId,
                    displayName = row.shareName
                )
            )
        }
        unraidShareMounts.value = shareMounts
    }

    fun openUnraidShare(shareName: String) {
        val mount = findUnraidShareMount(_uiState.value.mounts, shareName) ?: return
        openMountInternal(mount, "")
    }

    fun openMount(mountId: String, path: String = "") {
        val mount = _uiState.value.mounts.firstOrNull { it.id == mountId } ?: return
        openMountInternal(mount, path)
    }

    private fun openMountInternal(mount: StorageMount, path: String) {
        updateFocusedTab { tab ->
            pushLocationHistory(
                tab = tab,
                targetMountId = mount.id,
                targetMountTitle = mount.label,
                targetPath = path
            )
        }
        refreshFocusedTab()
    }

    fun togglePinLocation(mountId: String) {
        viewModelScope.launch {
            preferencesRepository.togglePinnedLocation(mountId)
        }
    }

    fun setLocationHidden(mountId: String, hidden: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setLocationHidden(mountId, hidden)
        }
    }

    fun renameLocation(mountId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            preferencesRepository.setLocationDisplayName(mountId, trimmed)
        }
    }

    fun resetLocationName(mountId: String) {
        viewModelScope.launch {
            preferencesRepository.setLocationDisplayName(mountId, null)
        }
    }

    fun setShowHiddenLocations(show: Boolean) {
        _uiState.update { it.copy(showHiddenLocations = show) }
    }

    fun toggleExpandedSidebarCollapsed() {
        val next = !_uiState.value.isExpandedSidebarCollapsed
        _uiState.update { it.copy(isExpandedSidebarCollapsed = next) }
        viewModelScope.launch {
            preferencesRepository.setExplorerSidebarCollapsed(next)
        }
    }

    fun navigateTo(path: String) {
        val clean = PathGuard.normalizeRelativePath(path)
        val currentTab = _uiState.value.focusedTab
        if (clean == currentTab.currentPath) return
        updateFocusedTab { tab ->
            tab.copy(
                historyBack = tab.historyBack + ExplorerLocation(tab.mountId, tab.currentPath),
                historyForward = emptyList(),
                currentPath = clean,
                selectedPaths = emptySet()
            )
        }
        refreshFocusedTab()
    }

    fun navigateBackOrUp(): Boolean {
        val state = _uiState.value
        val mountsById = state.mounts.associateBy { it.id }
        val nextTab = resolveBackStep(
            tab = state.focusedTab,
            mountsById = mountsById
        ) ?: return false
        updateFocusedTab { nextTab }
        refreshFocusedTab()
        return true
    }

    fun clearSelection() {
        updateFocusedTab { it.copy(selectedPaths = emptySet()) }
    }

    fun selectAll() {
        updateFocusedTab { tab ->
            tab.copy(selectedPaths = tab.entries.map { it.path }.toSet())
        }
    }

    fun refreshFocusedTab() {
        val state = _uiState.value
        val tab = state.focusedTab
        val pane = state.activePane
        val mount = state.mounts.firstOrNull { it.id == tab.mountId } ?: localMount

        viewModelScope.launch {
            updateTabBySlot(pane, tab.id) { it.copy(isLoading = true, errorMessage = null) }
            try {
                val entries = mount.provider.list(tab.currentPath)
                updateTabBySlot(pane, tab.id) { current ->
                    current.copy(
                        entries = sortEntries(entries, current.sortField, current.sortAscending),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updateTabBySlot(pane, tab.id) { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Unable to list directory."
                    )
                }
            }
        }
    }

    fun newTab() {
        val active = _uiState.value.activeTab
        val created = ExplorerTab(
            title = active.title,
            mountId = active.mountId,
            currentPath = active.currentPath,
            viewMode = active.viewMode
        )
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs + created,
                activeTabId = created.id,
                activePane = PaneSlot.PRIMARY
            )
        }
        refreshFocusedTab()
    }

    fun closeTab(tabId: String) {
        _uiState.update { state ->
            if (state.tabs.size <= 1) return@update state
            val remaining = state.tabs.filterNot { it.id == tabId }
            val newActive = if (state.activeTabId == tabId) remaining.last().id else state.activeTabId
            state.copy(tabs = remaining, activeTabId = newActive)
        }
    }

    fun selectTab(tabId: String) {
        _uiState.update { it.copy(activeTabId = tabId, activePane = PaneSlot.PRIMARY) }
    }

    fun nextTab() {
        val state = _uiState.value
        if (state.tabs.size <= 1) return
        val idx = state.tabs.indexOfFirst { it.id == state.activeTabId }
        val next = state.tabs[(idx + 1) % state.tabs.size]
        selectTab(next.id)
    }

    fun selectTabByIndex(index: Int) {
        val tab = _uiState.value.tabs.getOrNull(index) ?: return
        selectTab(tab.id)
    }

    fun toggleDualPane() {
        _uiState.update { state ->
            val enabling = !state.isDualPane
            val localTitle = state.mounts.firstOrNull { it.id == localMount.id }?.label
                ?: localMount.label
            val secondary = if (enabling && state.secondaryTab == null) {
                ExplorerTab(
                    id = "tab-secondary",
                    title = localTitle,
                    mountId = localMount.id
                )
            } else {
                state.secondaryTab
            }
            state.copy(
                isDualPane = enabling,
                secondaryTab = secondary,
                activePane = if (!enabling) PaneSlot.PRIMARY else state.activePane
            )
        }
        if (_uiState.value.isDualPane) {
            setActivePane(PaneSlot.SECONDARY)
            refreshFocusedTab()
        }
    }

    fun setActivePane(pane: PaneSlot) {
        _uiState.update { it.copy(activePane = pane) }
    }

    fun setViewMode(mode: ExplorerViewMode) {
        updateFocusedTab { it.copy(viewMode = mode) }
    }

    fun setSort(field: SortField) {
        updateFocusedTab { tab ->
            val ascending = if (tab.sortField == field) !tab.sortAscending else true
            tab.copy(
                sortField = field,
                sortAscending = ascending,
                entries = sortEntries(tab.entries, field, ascending)
            )
        }
    }

    fun toggleSelection(path: String) {
        updateFocusedTab { tab ->
            val next = if (path in tab.selectedPaths) tab.selectedPaths - path else tab.selectedPaths + path
            tab.copy(selectedPaths = next)
        }
    }

    fun selectSingle(path: String) {
        updateFocusedTab { it.copy(selectedPaths = setOf(path)) }
    }

    fun createFolder(name: String) {
        val state = _uiState.value
        val tab = state.focusedTab
        val mount = state.mounts.firstOrNull { it.id == tab.mountId } ?: return
        viewModelScope.launch {
            runCatching { mount.provider.createDirectory(tab.currentPath, name) }
            refreshFocusedTab()
        }
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        val state = _uiState.value
        val tab = state.focusedTab
        val mount = state.mounts.firstOrNull { it.id == tab.mountId } ?: return
        viewModelScope.launch {
            runCatching { mount.provider.rename(entry.path, newName) }
            refreshFocusedTab()
        }
    }

    fun deleteEntries(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val state = _uiState.value
        val tab = state.focusedTab
        val mount = state.mounts.firstOrNull { it.id == tab.mountId } ?: return
        val deletedPaths = entries.map { it.path }.toSet()
        updateFocusedTab { current ->
            current.copy(selectedPaths = current.selectedPaths - deletedPaths)
        }
        viewModelScope.launch {
            entries.forEach { entry ->
                runCatching { mount.provider.delete(entry.path) }
            }
            refreshFocusedTab()
        }
    }

    fun transferToOppositePane(isMove: Boolean) {
        val state = _uiState.value
        if (!state.isDualPane || state.secondaryTab == null) return
        val sourceTab = state.focusedTab
        val destTab = if (state.activePane == PaneSlot.PRIMARY) state.secondaryTab else state.activeTab
        val sourceMount = state.mounts.firstOrNull { it.id == sourceTab.mountId } ?: return
        val destMount = state.mounts.firstOrNull { it.id == destTab.mountId } ?: return

        val selected = sourceTab.entries.filter { it.path in sourceTab.selectedPaths }
        selected.forEach { entry ->
            if (isMove) {
                transferQueueManager.enqueueMove(
                    sourceProvider = sourceMount.provider,
                    sourceEntry = entry,
                    destProvider = destMount.provider,
                    destDirectoryPath = destTab.currentPath,
                    conflictPolicy = ConflictPolicy.RENAME
                )
            } else {
                transferQueueManager.enqueueCopy(
                    sourceProvider = sourceMount.provider,
                    sourceEntry = entry,
                    destProvider = destMount.provider,
                    destDirectoryPath = destTab.currentPath,
                    conflictPolicy = ConflictPolicy.RENAME
                )
            }
        }
    }

    fun openQuickLook(entry: FileEntry?) {
        if (entry == null) {
            _uiState.update { it.copy(quickLookEntry = null, quickLookProvider = null) }
            return
        }
        val state = _uiState.value
        val mount = state.mounts.firstOrNull { it.id == state.focusedTab.mountId } ?: return
        _uiState.update { it.copy(quickLookEntry = entry, quickLookProvider = mount.provider) }
    }

    fun addSafMount(treeUri: Uri, label: String) {
        val id = "saf_${UUID.randomUUID()}"
        val cleanLabel = label.ifBlank { "Cloud / USB" }
        val mount = StorageMount(
            id = id,
            defaultLabel = cleanLabel,
            protocol = StorageProtocol.SAF,
            provider = SafFileSystem(
                contentResolver = contentResolver,
                treeUri = treeUri,
                rootId = id,
                displayName = cleanLabel
            )
        )
        customMounts.update { it + mount }
        _uiState.update { state ->
            if (state.mounts.any { it.id == mount.id }) state else state.copy(mounts = state.mounts + mount)
        }
        openMountInternal(mount, "")
    }

    fun addCustomNetworkMount(
        protocol: StorageProtocol,
        label: String,
        hostOrUrl: String,
        port: Int,
        shareOrPath: String,
        username: String,
        password: String,
        useTls: Boolean
    ) {
        val id = "net_${UUID.randomUUID()}"
        val cleanLabel = label.ifBlank { hostOrUrl }
        val provider: FileSystemProvider = when (protocol) {
            StorageProtocol.SMB -> SmbFileSystem(
                host = hostOrUrl,
                shareName = shareOrPath,
                port = port,
                lanVpnGuard = lanVpnGuard,
                credentialProvider = {
                    if (username.isNotBlank()) SmbFileSystem.SmbCredentials(username, password) else null
                },
                rootId = id,
                displayName = cleanLabel
            )
            StorageProtocol.WEBDAV -> WebDavFileSystem(
                baseUrl = hostOrUrl,
                okHttpClient = okHttpFactory.createClient(),
                username = username.ifBlank { null },
                password = password.ifBlank { null },
                rootId = id,
                displayName = cleanLabel
            )
            StorageProtocol.FTP -> FtpFileSystem(
                host = hostOrUrl,
                port = port,
                useFtps = useTls,
                username = username.ifBlank { "anonymous" },
                password = password,
                lanVpnGuard = lanVpnGuard,
                rootId = id,
                displayName = cleanLabel
            )
            else -> return
        }
        val mount = StorageMount(id = id, defaultLabel = cleanLabel, protocol = protocol, provider = provider)
        customMounts.update { it + mount }
        _uiState.update { state ->
            if (state.mounts.any { it.id == mount.id }) state else state.copy(mounts = state.mounts + mount)
        }
        openMountInternal(mount, "")
    }

    fun cancelTransfer(jobId: String) = transferQueueManager.cancel(jobId)
    fun clearCompletedTransfers() = transferQueueManager.clearCompleted()

    private fun updateFocusedTab(transform: (ExplorerTab) -> ExplorerTab) {
        val state = _uiState.value
        updateTabBySlot(state.activePane, state.focusedTab.id, transform)
    }

    private fun updateTabBySlot(
        pane: PaneSlot,
        tabId: String,
        transform: (ExplorerTab) -> ExplorerTab
    ) {
        _uiState.update { state ->
            if (pane == PaneSlot.SECONDARY && state.secondaryTab != null) {
                state.copy(secondaryTab = transform(state.secondaryTab))
            } else {
                state.copy(tabs = state.tabs.map { if (it.id == tabId) transform(it) else it })
            }
        }
    }

    private fun sortEntries(
        entries: List<FileEntry>,
        field: SortField,
        ascending: Boolean
    ): List<FileEntry> {
        val comparator = Comparator<FileEntry> { a, b ->
            // Directories always first
            if (a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }
            val cmp = when (field) {
                SortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortField.MODIFIED -> a.modifiedMillis.compareTo(b.modifiedMillis)
                SortField.TYPE -> a.name.substringAfterLast('.', "").compareTo(
                    b.name.substringAfterLast('.', ""),
                    ignoreCase = true
                )
                SortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
            }
            if (ascending) cmp else -cmp
        }
        return entries.sortedWith(comparator)
    }
}
