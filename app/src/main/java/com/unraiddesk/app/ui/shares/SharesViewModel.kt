package com.unraiddesk.app.ui.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotDao
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.data.db.ShareMountDao
import com.unraiddesk.app.core.data.db.ShareMountEntity
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.network.UnraidShare
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

data class ShareThroughput(
    val readsPerSec: Double,
    val writesPerSec: Double,
    val scopeLabel: String
)

data class ShareRowItem(
    val share: UnraidShare,
    val throughput: ShareThroughput?
)

sealed interface SharesUiState {
    data object Loading : SharesUiState
    data class Content(
        val shares: List<ShareRowItem>,
        val isOffline: Boolean,
        val lastUpdatedMillis: Long,
        val offlineReason: String?
    ) : SharesUiState

    data class Error(val message: String) : SharesUiState
}

/**
 * Computes honest disk/array I/O delta rates for an Unraid FUSE share by mapping its
 * `include` / `exclude` disk lists to `array.disks` / `array.caches` counters between polls.
 */
object SharesThroughputCalculator {
    fun calculate(
        share: UnraidShare,
        previousDisks: List<ArrayDisk>,
        currentDisks: List<ArrayDisk>,
        elapsedMillis: Long
    ): ShareThroughput? {
        if (elapsedMillis <= 0L || previousDisks.isEmpty() || currentDisks.isEmpty()) {
            return null
        }
        val seconds = elapsedMillis / 1000.0
        val includeSet = share.include.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val excludeSet = share.exclude.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val prevByName = previousDisks.associateBy { it.name.lowercase() }
        val matchingCurrent = currentDisks.filter { disk ->
            val key = disk.name.lowercase()
            val included = if (includeSet.isEmpty()) !disk.isCache else key in includeSet
            included && key !in excludeSet
        }

        if (matchingCurrent.isEmpty()) return null

        var deltaReads = 0L
        var deltaWrites = 0L
        matchingCurrent.forEach { curr ->
            val prev = prevByName[curr.name.lowercase()] ?: return@forEach
            deltaReads += (curr.numReads - prev.numReads).coerceAtLeast(0L)
            deltaWrites += (curr.numWrites - prev.numWrites).coerceAtLeast(0L)
        }

        val scopeLabel = if (includeSet.isEmpty()) {
            "Array I/O (delta)"
        } else {
            "Disk I/O (${share.include.joinToString(", ")})"
        }
        return ShareThroughput(
            readsPerSec = deltaReads / seconds,
            writesPerSec = deltaWrites / seconds,
            scopeLabel = scopeLabel
        )
    }
}

class SharesViewModel(
    private val serverConfigDao: ServerConfigDao,
    private val shareMountDao: ShareMountDao,
    private val snapshotDao: CachedMetricSnapshotDao,
    private val preferencesRepository: AppPreferencesRepository,
    private val cryptoManager: KeystoreCryptoManager,
    private val graphQlClient: UnraidGraphQlClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ViewModel() {

    private val _uiState = MutableStateFlow<SharesUiState>(SharesUiState.Loading)
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    private var previousDisks: List<ArrayDisk> = emptyList()
    private var previousTimestampMillis: Long = 0L
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
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val server = serverConfigDao.getPrimaryServer()
        if (server == null) {
            _uiState.value = SharesUiState.Error("No Unraid server configured.")
            return
        }

        val cachedSnapshot = snapshotDao.getLatest(server.id)
        if (_uiState.value is SharesUiState.Loading && cachedSnapshot != null) {
            val cachedShares = decodeCachedShares(cachedSnapshot.sharesJson)
            if (cachedShares.isNotEmpty()) {
                _uiState.value = SharesUiState.Content(
                    shares = cachedShares.map { ShareRowItem(it, null) },
                    isOffline = false,
                    lastUpdatedMillis = cachedSnapshot.updatedAtMillis,
                    offlineReason = null
                )
            }
        }

        try {
            val apiKey = cryptoManager.decryptString(server.encryptedApiKey)
            val shares = graphQlClient.fetchShares(server.baseUrl(), apiKey)
            val dashboard = graphQlClient.fetchDashboard(server.baseUrl(), apiKey)
            val now = System.currentTimeMillis()
            val currentDisks = dashboard.array.disks + dashboard.array.caches
            val elapsed = if (previousTimestampMillis > 0L) now - previousTimestampMillis else 0L

            val rows = shares.map { share ->
                val throughput = SharesThroughputCalculator.calculate(
                    share = share,
                    previousDisks = previousDisks,
                    currentDisks = currentDisks,
                    elapsedMillis = elapsed
                )
                ShareRowItem(share = share, throughput = throughput)
            }

            previousDisks = currentDisks
            previousTimestampMillis = now

            // Sync share capacities into Room
            val existingMounts = shareMountDao.getForServer(server.id).associateBy { it.shareName }
            val updatedMounts = shares.map { s ->
                val prev = existingMounts[s.name]
                ShareMountEntity(
                    id = prev?.id ?: 0L,
                    serverId = server.id,
                    shareName = s.name,
                    comment = s.comment,
                    totalBytes = s.sizeBytes,
                    freeBytes = s.freeBytes,
                    usedBytes = s.usedBytes,
                    includeDisks = s.include.joinToString(","),
                    excludeDisks = s.exclude.joinToString(","),
                    cacheMode = s.cache,
                    smbEnabled = prev?.smbEnabled ?: true,
                    isMonitored = prev?.isMonitored ?: true
                )
            }
            if (updatedMounts.isNotEmpty()) {
                shareMountDao.upsertAll(updatedMounts)
            }

            cachedSnapshot?.let { snap ->
                snapshotDao.upsert(
                    snap.copy(
                        sharesJson = json.encodeToString(ListSerializer(UnraidShare.serializer()), shares),
                        updatedAtMillis = now
                    )
                )
            }

            _uiState.value = SharesUiState.Content(
                shares = rows,
                isOffline = false,
                lastUpdatedMillis = now,
                offlineReason = null
            )
        } catch (e: Exception) {
            val fallback = snapshotDao.getLatest(server.id)
            val cachedShares = fallback?.let { decodeCachedShares(it.sharesJson) }.orEmpty()
            if (fallback != null && cachedShares.isNotEmpty()) {
                _uiState.value = SharesUiState.Content(
                    shares = cachedShares.map { ShareRowItem(it, null) },
                    isOffline = true,
                    lastUpdatedMillis = fallback.updatedAtMillis,
                    offlineReason = e.message
                )
            } else {
                _uiState.value = SharesUiState.Error(e.message ?: "Unable to load shares.")
            }
        }
    }

    private fun decodeCachedShares(sharesJson: String): List<UnraidShare> =
        runCatching {
            json.decodeFromString(ListSerializer(UnraidShare.serializer()), sharesJson)
        }.getOrDefault(emptyList())
}
