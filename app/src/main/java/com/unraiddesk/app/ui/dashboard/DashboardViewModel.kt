package com.unraiddesk.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotDao
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotEntity
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.core.network.ArrayStatus
import com.unraiddesk.app.core.network.DashboardSnapshot
import com.unraiddesk.app.core.network.ParityCheckStatus
import com.unraiddesk.app.core.network.ServerInfo
import com.unraiddesk.app.core.network.ServerMetrics
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Content(
        val serverName: String,
        val snapshot: DashboardSnapshot,
        val isOffline: Boolean,
        val lastUpdatedMillis: Long,
        val offlineReason: String?
    ) : DashboardUiState

    data class Error(
        val message: String
    ) : DashboardUiState
}

class DashboardViewModel(
    private val serverConfigDao: ServerConfigDao,
    private val snapshotDao: CachedMetricSnapshotDao,
    private val preferencesRepository: AppPreferencesRepository,
    private val cryptoManager: KeystoreCryptoManager,
    private val graphQlClient: UnraidGraphQlClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                val intervalSec = preferencesRepository.pollingIntervalSeconds.first()
                delay(intervalSec * 1_000L)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshOnce()
        }
    }

    private suspend fun refreshOnce() {
        val server = serverConfigDao.getPrimaryServer()
        if (server == null) {
            _uiState.value = DashboardUiState.Error("No Unraid server configured.")
            return
        }

        val cached = snapshotDao.getLatest(server.id)
        if (_uiState.value is DashboardUiState.Loading && cached != null) {
            _uiState.value = DashboardUiState.Content(
                serverName = server.name,
                snapshot = cached.toDashboardSnapshot(json),
                isOffline = false,
                lastUpdatedMillis = cached.updatedAtMillis,
                offlineReason = null
            )
        }

        try {
            val apiKey = cryptoManager.decryptString(server.encryptedApiKey)
            val live = graphQlClient.fetchDashboard(server.baseUrl(), apiKey)
            val now = System.currentTimeMillis()
            val allDisks = live.array.disks + live.array.caches
            val existing = snapshotDao.getLatest(server.id)
            snapshotDao.upsert(
                CachedMetricSnapshotEntity(
                    serverId = server.id,
                    osInfoJson = json.encodeToString(ServerInfo.serializer(), live.info),
                    cpuPercent = live.metrics.cpuPercent,
                    ramPercent = live.metrics.ramPercent,
                    ramUsedBytes = live.metrics.ramUsedBytes,
                    ramTotalBytes = live.metrics.ramTotalBytes,
                    arrayState = live.array.state,
                    arrayUsedBytes = live.array.usedBytes,
                    arrayTotalBytes = live.array.totalBytes,
                    parityStatus = live.array.parityCheckStatus.status,
                    parityProgress = live.array.parityCheckStatus.progressPercent,
                    sharesJson = existing?.sharesJson ?: "[]",
                    containersJson = existing?.containersJson ?: "[]",
                    disksJson = json.encodeToString(ListSerializer(ArrayDisk.serializer()), allDisks),
                    updatedAtMillis = now
                )
            )
            _uiState.value = DashboardUiState.Content(
                serverName = server.name,
                snapshot = live,
                isOffline = false,
                lastUpdatedMillis = now,
                offlineReason = null
            )
        } catch (e: Exception) {
            val fallback = snapshotDao.getLatest(server.id)
            if (fallback != null) {
                _uiState.value = DashboardUiState.Content(
                    serverName = server.name,
                    snapshot = fallback.toDashboardSnapshot(json),
                    isOffline = true,
                    lastUpdatedMillis = fallback.updatedAtMillis,
                    offlineReason = e.message ?: "Unable to reach server"
                )
            } else {
                _uiState.value = DashboardUiState.Error(
                    e.message ?: "Unable to load Unraid dashboard."
                )
            }
        }
    }
}

private fun CachedMetricSnapshotEntity.toDashboardSnapshot(json: Json): DashboardSnapshot {
    val info = runCatching {
        json.decodeFromString(ServerInfo.serializer(), osInfoJson)
    }.getOrDefault(ServerInfo())

    val allDisks = runCatching {
        json.decodeFromString(ListSerializer(ArrayDisk.serializer()), disksJson)
    }.getOrDefault(emptyList())

    return DashboardSnapshot(
        info = info,
        metrics = ServerMetrics(
            cpuPercent = cpuPercent,
            ramPercent = ramPercent,
            ramUsedBytes = ramUsedBytes,
            ramTotalBytes = ramTotalBytes
        ),
        array = ArrayStatus(
            state = arrayState,
            usedBytes = arrayUsedBytes,
            freeBytes = (arrayTotalBytes - arrayUsedBytes).coerceAtLeast(0L),
            totalBytes = arrayTotalBytes,
            parityCheckStatus = ParityCheckStatus(
                status = parityStatus,
                progressPercent = parityProgress
            ),
            disks = allDisks.filter { !it.isCache },
            caches = allDisks.filter { it.isCache }
        )
    )
}
