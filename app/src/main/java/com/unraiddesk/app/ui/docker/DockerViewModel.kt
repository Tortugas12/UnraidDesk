package com.unraiddesk.app.ui.docker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotDao
import com.unraiddesk.app.core.data.db.DockerFolderDao
import com.unraiddesk.app.core.data.db.DockerFolderEntity
import com.unraiddesk.app.core.data.db.DockerFolderMembershipEntity
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.network.DockerContainer
import com.unraiddesk.app.core.network.DockerRootVariant
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class DockerFolderGroup(
    val folder: DockerFolderEntity,
    val containers: List<DockerContainer>
) {
    val runningCount: Int
        get() = containers.count { it.isRunning }
}

enum class ContainerActionType { START, STOP, RESTART }

data class PendingCriticalAction(
    val container: DockerContainer,
    val action: ContainerActionType
)

sealed interface DockerUiState {
    data object Loading : DockerUiState
    data class Content(
        val serverBaseUrl: String,
        val folders: List<DockerFolderGroup>,
        val ungroupedContainers: List<DockerContainer>,
        val allContainers: List<DockerContainer>,
        val criticalContainerNames: Set<String>,
        val busyContainerIds: Set<String>,
        val isOffline: Boolean,
        val lastUpdatedMillis: Long,
        val offlineReason: String?
    ) : DockerUiState

    data class Error(val message: String) : DockerUiState
}

class DockerViewModel(
    private val serverConfigDao: ServerConfigDao,
    private val dockerFolderDao: DockerFolderDao,
    private val snapshotDao: CachedMetricSnapshotDao,
    private val preferencesRepository: AppPreferencesRepository,
    private val cryptoManager: KeystoreCryptoManager,
    private val graphQlClient: UnraidGraphQlClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ViewModel() {

    private val containersFlow = MutableStateFlow<List<DockerContainer>?>(null)
    private val busyIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val offlineFlow = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val lastUpdatedFlow = MutableStateFlow(0L)
    private val serverBaseUrlFlow = MutableStateFlow("")
    private val fatalErrorFlow = MutableStateFlow<String?>(null)

    private val _pendingCriticalAction = MutableStateFlow<PendingCriticalAction?>(null)
    val pendingCriticalAction: StateFlow<PendingCriticalAction?> = _pendingCriticalAction.asStateFlow()

    private val _uiState = MutableStateFlow<DockerUiState>(DockerUiState.Loading)
    val uiState: StateFlow<DockerUiState> = _uiState.asStateFlow()

    init {
        observeState()
        refresh()
    }

    private fun observeState() {
        viewModelScope.launch {
            combine(
                dockerFolderDao.observeFolders(1L),
                dockerFolderDao.observeMemberships(),
                containersFlow,
                preferencesRepository.criticalContainerNames,
                busyIdsFlow
            ) { folders, memberships, containers, criticalNames, busyIds ->
                FolderMergeInput(folders, memberships, containers, criticalNames, busyIds)
            }.combine(offlineFlow) { merge, offline ->
                merge to offline
            }.collect { (merge, offline) ->
                val fatal = fatalErrorFlow.value
                val containers = merge.containers
                if (fatal != null && containers == null) {
                    _uiState.value = DockerUiState.Error(fatal)
                    return@collect
                }
                if (containers == null) {
                    _uiState.value = DockerUiState.Loading
                    return@collect
                }

                val byName = containers.associateBy { it.name }
                val assignedNames = mutableSetOf<String>()
                val folderGroups = merge.folders.map { folder ->
                    val memberNames = merge.memberships
                        .filter { it.folderId == folder.id }
                        .map { it.containerName }
                    val matched = memberNames.mapNotNull { name ->
                        byName[name]?.also { assignedNames.add(name) }
                    }
                    DockerFolderGroup(folder = folder, containers = matched)
                }
                val ungrouped = containers.filter { it.name !in assignedNames }

                _uiState.value = DockerUiState.Content(
                    serverBaseUrl = serverBaseUrlFlow.value,
                    folders = folderGroups,
                    ungroupedContainers = ungrouped,
                    allContainers = containers,
                    criticalContainerNames = merge.criticalNames,
                    busyContainerIds = merge.busyIds,
                    isOffline = offline.first,
                    lastUpdatedMillis = lastUpdatedFlow.value,
                    offlineReason = offline.second
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val server = serverConfigDao.getPrimaryServer()
            if (server == null) {
                fatalErrorFlow.value = "No Unraid server configured."
                return@launch
            }
            serverBaseUrlFlow.value = server.baseUrl()

            val cachedSnap = snapshotDao.getLatest(server.id)
            if (containersFlow.value == null && cachedSnap != null) {
                val cachedList = decodeCachedContainers(cachedSnap.containersJson)
                if (cachedList.isNotEmpty()) {
                    lastUpdatedFlow.value = cachedSnap.updatedAtMillis
                    containersFlow.value = cachedList
                }
            }

            try {
                val apiKey = cryptoManager.decryptString(server.encryptedApiKey)
                val variant = when (server.dockerRootVariant) {
                    DockerRootVariant.DOCKER_CONTAINERS.name -> DockerRootVariant.DOCKER_CONTAINERS
                    else -> DockerRootVariant.DOCKER_OBJ
                }
                val live = graphQlClient.fetchDockerContainers(server.baseUrl(), apiKey, variant)
                val now = System.currentTimeMillis()
                fatalErrorFlow.value = null
                offlineFlow.value = false to null
                lastUpdatedFlow.value = now
                containersFlow.value = live

                cachedSnap?.let { snap ->
                    snapshotDao.upsert(
                        snap.copy(
                            containersJson = json.encodeToString(
                                ListSerializer(DockerContainer.serializer()),
                                live
                            ),
                            updatedAtMillis = now
                        )
                    )
                }
            } catch (e: Exception) {
                val fallback = snapshotDao.getLatest(server.id)
                val cachedList = fallback?.let { decodeCachedContainers(it.containersJson) }.orEmpty()
                if (cachedList.isNotEmpty()) {
                    lastUpdatedFlow.value = fallback!!.updatedAtMillis
                    offlineFlow.value = true to e.message
                    containersFlow.value = cachedList
                } else {
                    fatalErrorFlow.value = e.message ?: "Unable to load Docker containers."
                    containersFlow.value = null
                }
            }
        }
    }

    fun requestContainerAction(container: DockerContainer, action: ContainerActionType) {
        viewModelScope.launch {
            val critical = preferencesRepository.criticalContainerNames.first()
            if (action != ContainerActionType.START && container.name in critical) {
                _pendingCriticalAction.value = PendingCriticalAction(container, action)
            } else {
                executeContainerAction(container, action)
            }
        }
    }

    fun confirmCriticalAction() {
        val pending = _pendingCriticalAction.value ?: return
        _pendingCriticalAction.value = null
        executeContainerAction(pending.container, pending.action)
    }

    fun dismissCriticalAction() {
        _pendingCriticalAction.value = null
    }

    private fun executeContainerAction(container: DockerContainer, action: ContainerActionType) {
        viewModelScope.launch {
            val server = serverConfigDao.getPrimaryServer() ?: return@launch
            busyIdsFlow.value = busyIdsFlow.value + container.id
            try {
                val apiKey = cryptoManager.decryptString(server.encryptedApiKey)
                val variant = when (server.dockerRootVariant) {
                    DockerRootVariant.DOCKER_CONTAINERS.name -> DockerRootVariant.DOCKER_CONTAINERS
                    else -> DockerRootVariant.DOCKER_OBJ
                }
                when (action) {
                    ContainerActionType.START ->
                        graphQlClient.startContainer(server.baseUrl(), apiKey, container.id)
                    ContainerActionType.STOP ->
                        graphQlClient.stopContainer(server.baseUrl(), apiKey, container.id)
                    ContainerActionType.RESTART ->
                        graphQlClient.restartContainer(server.baseUrl(), apiKey, container.id, variant)
                }
                refresh()
            } catch (e: Exception) {
                offlineFlow.value = true to (e.message ?: "Container action failed.")
            } finally {
                busyIdsFlow.value = busyIdsFlow.value - container.id
            }
        }
    }

    fun createOrUpdateFolder(folderId: Long?, name: String, memberContainerNames: Set<String>) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            val id = dockerFolderDao.upsertFolder(
                DockerFolderEntity(
                    id = folderId ?: 0L,
                    serverId = 1L,
                    name = cleanName,
                    isExpanded = true
                )
            )
            val resolvedId = if (folderId != null && folderId > 0L) folderId else id
            val currentMemberships = dockerFolderDao.observeMemberships().first()
                .filter { it.folderId == resolvedId }
                .map { it.containerName }
                .toSet()

            (currentMemberships - memberContainerNames).forEach { removed ->
                dockerFolderDao.deleteMembership(resolvedId, removed)
            }
            memberContainerNames.forEach { added ->
                dockerFolderDao.upsertMembership(
                    DockerFolderMembershipEntity(folderId = resolvedId, containerName = added)
                )
            }
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            dockerFolderDao.deleteFolder(folderId)
        }
    }

    fun toggleFolderExpanded(folderId: Long, expanded: Boolean) {
        viewModelScope.launch {
            dockerFolderDao.setExpanded(folderId, expanded)
        }
    }

    fun toggleCriticalContainer(containerName: String, isCritical: Boolean) {
        viewModelScope.launch {
            val current = preferencesRepository.criticalContainerNames.first()
            val updated = if (isCritical) current + containerName else current - containerName
            preferencesRepository.setCriticalContainerNames(updated)
        }
    }

    private fun decodeCachedContainers(containersJson: String): List<DockerContainer> =
        runCatching {
            json.decodeFromString(ListSerializer(DockerContainer.serializer()), containersJson)
        }.getOrDefault(emptyList())

    private data class FolderMergeInput(
        val folders: List<DockerFolderEntity>,
        val memberships: List<DockerFolderMembershipEntity>,
        val containers: List<DockerContainer>?,
        val criticalNames: Set<String>,
        val busyIds: Set<String>
    )
}
