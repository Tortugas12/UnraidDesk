package com.unraiddesk.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository.ThemeMode
import com.unraiddesk.app.core.security.LanVpnGuard
import com.unraiddesk.app.ui.dashboard.DashboardScreen
import com.unraiddesk.app.ui.dashboard.DashboardViewModel
import com.unraiddesk.app.ui.docker.DockerScreen
import com.unraiddesk.app.ui.docker.DockerUiState
import com.unraiddesk.app.ui.docker.DockerViewModel
import com.unraiddesk.app.ui.explorer.ExplorerViewMode
import com.unraiddesk.app.ui.explorer.FileExplorerScreen
import com.unraiddesk.app.ui.explorer.FileExplorerViewModel
import com.unraiddesk.app.ui.onboarding.ServerSetupScreen
import com.unraiddesk.app.ui.settings.SettingsScreen
import com.unraiddesk.app.ui.shares.SharesScreen
import com.unraiddesk.app.ui.shares.SharesViewModel
import com.unraiddesk.app.ui.shell.AdaptiveShell
import com.unraiddesk.app.ui.shell.AppDestination
import com.unraiddesk.app.ui.shell.CaptionTabItem
import com.unraiddesk.app.ui.shell.CommandPaletteDialog
import com.unraiddesk.app.ui.shell.PaletteAction
import com.unraiddesk.app.ui.shell.ShortcutCheatsheetDialog
import com.unraiddesk.app.ui.shell.rememberIsDesktopOrChromeOs
import com.unraiddesk.app.widget.WidgetScheduler
import com.unraiddesk.app.ui.shell.showExitPromptToast
import com.unraiddesk.app.ui.theme.UnraidDeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as UnraidDeskApp).container

        setContent {
            val serverConfig by container.database
                .serverConfigDao()
                .observePrimaryServer()
                .collectAsState(initial = null)
            val themeMode by container.preferencesRepository.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            ConfigureDesktopCaptionBar(isDarkTheme = isDark)

            var reconfiguringServer by remember { mutableStateOf(false) }

            UnraidDeskTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val activeServer = serverConfig
                    if (activeServer == null || reconfiguringServer) {
                        BackHandler(enabled = activeServer != null && reconfiguringServer) {
                            reconfiguringServer = false
                        }
                        ServerSetupScreen(
                            lanVpnGuard = container.lanVpnGuard,
                            graphQlClient = container.graphQlClient,
                            authCoordinator = container.authCoordinator,
                            pinnedCertStore = container.pinnedCertStore,
                            database = container.database,
                            cryptoManager = container.cryptoManager,
                            onSetupComplete = { reconfiguringServer = false }
                        )
                    } else {
                        MainAppContent(
                            container = container,
                            onReconfigureServer = { reconfiguringServer = true }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigureDesktopCaptionBar(isDarkTheme: Boolean) {
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val controller = window.insetsController ?: return@SideEffect
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
                )
                val lightMask = if (isDarkTheme) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
                controller.setSystemBarsAppearance(
                    lightMask,
                    WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
                )
            }
        }
    }
}

@Composable
private fun MainAppContent(
    container: AppContainer,
    onReconfigureServer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedDest by remember { mutableStateOf(AppDestination.DASHBOARD) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var focusPathBarSignal by remember { mutableIntStateOf(0) }
    var renameSelectedSignal by remember { mutableIntStateOf(0) }

    val vmFactory = remember(container, context) {
        MainViewModelFactory(container, context.applicationContext.contentResolver)
    }
    val dashboardVm: DashboardViewModel = viewModel(factory = vmFactory)
    val sharesVm: SharesViewModel = viewModel(factory = vmFactory)
    val dockerVm: DockerViewModel = viewModel(factory = vmFactory)
    val explorerVm: FileExplorerViewModel = viewModel(factory = vmFactory)

    val dashboardState by dashboardVm.uiState.collectAsState()
    val sharesState by sharesVm.uiState.collectAsState()
    val dockerState by dockerVm.uiState.collectAsState()
    val pendingCritical by dockerVm.pendingCriticalAction.collectAsState()
    val explorerState by explorerVm.uiState.collectAsState()
    val transferJobs by explorerVm.transferJobs.collectAsState()

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = uri.lastPathSegment?.substringAfterLast(':') ?: "Cloud / USB"
            explorerVm.addSafMount(uri, label)
        }
    }

    val serverConfig by container.database.serverConfigDao().observePrimaryServer()
        .collectAsState(initial = null)
    val pinnedCerts by container.database.pinnedCertDao().observeAll()
        .collectAsState(initial = emptyList())
    val pollingSeconds by container.preferencesRepository.pollingIntervalSeconds
        .collectAsState(initial = AppPreferencesRepository.DEFAULT_POLLING_SECONDS)
    val widgetRefreshMinutes by container.preferencesRepository.widgetRefreshMinutes
        .collectAsState(initial = AppPreferencesRepository.DEFAULT_WIDGET_REFRESH_MINUTES)
    val dualPaneDefault by container.preferencesRepository.dualPaneDefault
        .collectAsState(initial = false)
    val themeMode by container.preferencesRepository.themeMode
        .collectAsState(initial = ThemeMode.SYSTEM)

    fun openSafeWebUrl(url: String) {
        scope.launch {
            val check = withContext(Dispatchers.IO) { container.lanVpnGuard.validateUrl(url) }
            if (check is LanVpnGuard.GuardResult.Allowed) {
                container.authCoordinator.launchCustomTab(context, url)
            }
        }
    }

    if (showCommandPalette) {
        val paletteActions = buildList {
            AppDestination.entries.forEach { dest ->
                add(
                    PaletteAction(
                        id = "nav_${dest.name}",
                        title = "Go to ${dest.label}",
                        category = "Navigation",
                        onExecute = { selectedDest = dest }
                    )
                )
            }
            explorerState.mounts.forEach { mount ->
                add(
                    PaletteAction(
                        id = "mount_${mount.id}",
                        title = "Open ${mount.label}",
                        category = "Storage (${mount.protocol.name})",
                        onExecute = {
                            selectedDest = AppDestination.FILES
                            explorerVm.openMount(mount.id)
                        }
                    )
                )
            }
            (dockerState as? DockerUiState.Content)?.allContainers?.forEach { c ->
                c.webUiUrl?.let { url ->
                    add(
                        PaletteAction(
                            id = "docker_${c.id}",
                            title = "Open ${c.name} WebUI",
                            category = "Docker",
                            onExecute = { openSafeWebUrl(url) }
                        )
                    )
                }
            }
            add(
                PaletteAction(
                    id = "cmd_dual_pane",
                    title = "Toggle Dual-Pane Commander",
                    category = "Files",
                    shortcut = "F3 / Ctrl+4",
                    onExecute = {
                        selectedDest = AppDestination.FILES
                        explorerVm.toggleDualPane()
                    }
                )
            )
            add(
                PaletteAction(
                    id = "cmd_shortcuts",
                    title = "Keyboard Shortcuts Cheatsheet",
                    category = "Help",
                    shortcut = "F1",
                    onExecute = { showShortcuts = true }
                )
            )
        }
        CommandPaletteDialog(
            actions = paletteActions,
            onDismiss = { showCommandPalette = false }
        )
    }

    if (showShortcuts) {
        ShortcutCheatsheetDialog(onDismiss = { showShortcuts = false })
    }

    val captionTabs = if (selectedDest == AppDestination.FILES) {
        explorerState.tabs.map { tab ->
            CaptionTabItem(
                id = tab.id,
                title = tab.title,
                closable = explorerState.tabs.size > 1
            )
        }
    } else {
        listOf(CaptionTabItem(id = "unraid-home", title = "UnraidDesk", closable = false))
    }
    val activeCaptionId = if (selectedDest == AppDestination.FILES) {
        explorerState.activeTabId
    } else {
        "unraid-home"
    }

    val isDesktopOrChromeOs = rememberIsDesktopOrChromeOs()
    val lifecycleOwner = LocalLifecycleOwner.current
    var exitArmed by remember { mutableStateOf(false) }
    var activeToast by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(
        selectedDest,
        explorerState.focusedTab.mountId,
        explorerState.focusedTab.currentPath
    ) {
        exitArmed = false
        activeToast?.cancel()
        activeToast = null
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_000L)
            exitArmed = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exitArmed = false
                activeToast?.cancel()
                activeToast = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeToast?.cancel()
            activeToast = null
        }
    }

    // Root double-back exit guard (non-desktop phone/tablet only).
    // Disabled once armed so a second Back within 2s hands off directly to Android 16 predictive back-to-home.
    BackHandler(enabled = !isDesktopOrChromeOs && !exitArmed) {
        exitArmed = true
        activeToast?.cancel()
        activeToast = showExitPromptToast(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed

                when {
                    ctrl && event.key == Key.K -> {
                        showCommandPalette = true
                        true
                    }
                    event.key == Key.F1 -> {
                        showShortcuts = true
                        true
                    }
                    ctrl && event.key == Key.T -> {
                        selectedDest = AppDestination.FILES
                        explorerVm.newTab()
                        true
                    }
                    ctrl && event.key == Key.W && selectedDest == AppDestination.FILES -> {
                        explorerVm.closeTab(explorerState.activeTabId)
                        true
                    }
                    ctrl && event.key == Key.Tab && selectedDest == AppDestination.FILES -> {
                        explorerVm.nextTab()
                        true
                    }
                    alt && event.key == Key.DirectionLeft && selectedDest == AppDestination.FILES -> {
                        explorerVm.navigateBackOrUp()
                        true
                    }
                    alt && event.key in listOf(
                        Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
                        Key.Six, Key.Seven, Key.Eight, Key.Nine
                    ) -> {
                        val idx = when (event.key) {
                            Key.One -> 0
                            Key.Two -> 1
                            Key.Three -> 2
                            Key.Four -> 3
                            Key.Five -> 4
                            Key.Six -> 5
                            Key.Seven -> 6
                            Key.Eight -> 7
                            else -> 8
                        }
                        selectedDest = AppDestination.FILES
                        explorerVm.selectTabByIndex(idx)
                        true
                    }
                    ctrl && event.key == Key.One && selectedDest == AppDestination.FILES -> {
                        explorerVm.setViewMode(ExplorerViewMode.DETAILS)
                        true
                    }
                    ctrl && event.key == Key.Two && selectedDest == AppDestination.FILES -> {
                        explorerVm.setViewMode(ExplorerViewMode.GRID)
                        true
                    }
                    (ctrl && event.key == Key.Four) || event.key == Key.F3 -> {
                        selectedDest = AppDestination.FILES
                        explorerVm.toggleDualPane()
                        true
                    }
                    event.key == Key.F5 && selectedDest == AppDestination.FILES -> {
                        explorerVm.transferToOppositePane(isMove = false)
                        true
                    }
                    event.key == Key.F6 && selectedDest == AppDestination.FILES -> {
                        explorerVm.transferToOppositePane(isMove = true)
                        true
                    }
                    event.key == Key.F2 && selectedDest == AppDestination.FILES -> {
                        renameSelectedSignal++
                        true
                    }
                    ctrl && event.key == Key.L && selectedDest == AppDestination.FILES -> {
                        focusPathBarSignal++
                        true
                    }
                    event.key == Key.Delete && selectedDest == AppDestination.FILES -> {
                        val tab = explorerState.focusedTab
                        val selected = tab.entries.filter { it.path in tab.selectedPaths }
                        explorerVm.deleteEntries(selected)
                        true
                    }
                    event.key == Key.Spacebar && selectedDest == AppDestination.FILES -> {
                        val tab = explorerState.focusedTab
                        val selected = tab.entries.firstOrNull {
                            it.path in tab.selectedPaths && !it.isDirectory
                        }
                        if (selected != null) {
                            explorerVm.openQuickLook(selected)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        AdaptiveShell(
            selectedDestination = selectedDest,
            onSelectDestination = { selectedDest = it },
            captionTabs = captionTabs,
            activeCaptionTabId = activeCaptionId,
            onSelectCaptionTab = { tabId ->
                if (selectedDest == AppDestination.FILES) explorerVm.selectTab(tabId)
            },
            onCloseCaptionTab = { tabId ->
                if (selectedDest == AppDestination.FILES) explorerVm.closeTab(tabId)
            },
            onNewCaptionTab = if (selectedDest == AppDestination.FILES) explorerVm::newTab else null
        ) { isExpanded ->
            when (selectedDest) {
                AppDestination.DASHBOARD -> DashboardScreen(
                    uiState = dashboardState,
                    onRefresh = dashboardVm::refresh,
                    onOpenSettings = { selectedDest = AppDestination.SETTINGS }
                )

                AppDestination.SHARES -> SharesScreen(
                    uiState = sharesState,
                    onRefresh = sharesVm::refresh,
                    onOpenInFiles = { shareName ->
                        explorerVm.openUnraidShare(shareName)
                        selectedDest = AppDestination.FILES
                    },
                    onOpenSettings = { selectedDest = AppDestination.SETTINGS }
                )

                AppDestination.DOCKER -> DockerScreen(
                    uiState = dockerState,
                    pendingCriticalAction = pendingCritical,
                    onConfirmCriticalAction = dockerVm::confirmCriticalAction,
                    onDismissCriticalAction = dockerVm::dismissCriticalAction,
                    onRefresh = dockerVm::refresh,
                    onContainerAction = dockerVm::requestContainerAction,
                    onToggleCritical = dockerVm::toggleCriticalContainer,
                    onCreateOrUpdateFolder = dockerVm::createOrUpdateFolder,
                    onDeleteFolder = dockerVm::deleteFolder,
                    onToggleFolderExpanded = dockerVm::toggleFolderExpanded,
                    onOpenUrl = ::openSafeWebUrl,
                    onOpenSettings = { selectedDest = AppDestination.SETTINGS }
                )

                AppDestination.FILES -> FileExplorerScreen(
                    uiState = explorerState,
                    transferJobs = transferJobs,
                    isExpanded = isExpanded,
                    isDesktop = isDesktopOrChromeOs,
                    focusPathBarSignal = focusPathBarSignal,
                    renameSelectedSignal = renameSelectedSignal,
                    onSelectMount = { explorerVm.openMount(it) },
                    onNavigateTo = explorerVm::navigateTo,
                    onRefresh = explorerVm::refreshFocusedTab,
                    onToggleDualPane = explorerVm::toggleDualPane,
                    onSelectPane = explorerVm::setActivePane,
                    onViewModeChange = explorerVm::setViewMode,
                    onSortChange = explorerVm::setSort,
                    onSelectEntry = { entry, exclusive ->
                        if (exclusive) {
                            explorerVm.selectSingle(entry.path)
                        } else {
                            explorerVm.toggleSelection(entry.path)
                        }
                    },
                    onOpenQuickLook = explorerVm::openQuickLook,
                    onCreateFolder = explorerVm::createFolder,
                    onRenameEntry = explorerVm::renameEntry,
                    onDeleteEntries = explorerVm::deleteEntries,
                    onCopyToOpposite = { explorerVm.transferToOppositePane(isMove = false) },
                    onMoveToOpposite = { explorerVm.transferToOppositePane(isMove = true) },
                    onCancelTransfer = explorerVm::cancelTransfer,
                    onClearCompletedTransfers = explorerVm::clearCompletedTransfers,
                    onAddCustomNetworkMount = explorerVm::addCustomNetworkMount,
                    onPickSafFolder = { safLauncher.launch(null) },
                    onTogglePinLocation = explorerVm::togglePinLocation,
                    onSetLocationHidden = explorerVm::setLocationHidden,
                    onRenameLocation = explorerVm::renameLocation,
                    onResetLocationName = explorerVm::resetLocationName,
                    onSetShowHiddenLocations = explorerVm::setShowHiddenLocations,
                    onToggleExpandedSidebar = explorerVm::toggleExpandedSidebarCollapsed,
                    onNavigateBackOrUp = { explorerVm.navigateBackOrUp() },
                    onClearSelection = explorerVm::clearSelection,
                    onSelectAll = explorerVm::selectAll
                )

                AppDestination.SETTINGS -> SettingsScreen(
                    serverConfig = serverConfig,
                    pinnedCerts = pinnedCerts,
                    pollingSeconds = pollingSeconds,
                    widgetRefreshMinutes = widgetRefreshMinutes,
                    dualPaneDefault = dualPaneDefault,
                    themeMode = themeMode,
                    onPollingSecondsChange = { sec ->
                        scope.launch { container.preferencesRepository.setPollingIntervalSeconds(sec) }
                    },
                    onWidgetRefreshMinutesChange = { min ->
                        scope.launch {
                            container.preferencesRepository.setWidgetRefreshMinutes(min)
                            WidgetScheduler.schedule(context.applicationContext, min)
                        }
                    },
                    onDualPaneDefaultChange = { enabled ->
                        scope.launch { container.preferencesRepository.setDualPaneDefault(enabled) }
                    },
                    onThemeModeChange = { mode ->
                        scope.launch { container.preferencesRepository.setThemeMode(mode) }
                    },
                    onRevokePinnedCert = { host ->
                        scope.launch(Dispatchers.IO) { container.database.pinnedCertDao().deleteByHost(host) }
                    },
                    onReconfigureServer = onReconfigureServer
                )
            }
        }
    }
}

private class MainViewModelFactory(
    private val container: AppContainer,
    private val contentResolver: android.content.ContentResolver
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(DashboardViewModel::class.java) -> DashboardViewModel(
                serverConfigDao = container.database.serverConfigDao(),
                snapshotDao = container.database.cachedMetricSnapshotDao(),
                preferencesRepository = container.preferencesRepository,
                cryptoManager = container.cryptoManager,
                graphQlClient = container.graphQlClient
            ) as T

            modelClass.isAssignableFrom(SharesViewModel::class.java) -> SharesViewModel(
                serverConfigDao = container.database.serverConfigDao(),
                shareMountDao = container.database.shareMountDao(),
                snapshotDao = container.database.cachedMetricSnapshotDao(),
                preferencesRepository = container.preferencesRepository,
                cryptoManager = container.cryptoManager,
                graphQlClient = container.graphQlClient
            ) as T

            modelClass.isAssignableFrom(DockerViewModel::class.java) -> DockerViewModel(
                serverConfigDao = container.database.serverConfigDao(),
                dockerFolderDao = container.database.dockerFolderDao(),
                snapshotDao = container.database.cachedMetricSnapshotDao(),
                preferencesRepository = container.preferencesRepository,
                cryptoManager = container.cryptoManager,
                graphQlClient = container.graphQlClient
            ) as T

            modelClass.isAssignableFrom(FileExplorerViewModel::class.java) -> FileExplorerViewModel(
                serverConfigDao = container.database.serverConfigDao(),
                shareMountDao = container.database.shareMountDao(),
                preferencesRepository = container.preferencesRepository,
                cryptoManager = container.cryptoManager,
                lanVpnGuard = container.lanVpnGuard,
                okHttpFactory = container.okHttpFactory,
                transferQueueManager = container.transferQueueManager,
                contentResolver = contentResolver
            ) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
