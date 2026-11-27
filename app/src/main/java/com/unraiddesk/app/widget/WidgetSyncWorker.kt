package com.unraiddesk.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unraiddesk.app.UnraidDeskApp
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotDao
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotEntity
import com.unraiddesk.app.core.data.db.ServerConfigDao
import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.core.network.DockerContainer
import com.unraiddesk.app.core.network.DockerRootVariant
import com.unraiddesk.app.core.network.ServerInfo
import com.unraiddesk.app.core.network.UnraidGraphQlClient
import com.unraiddesk.app.core.network.UnraidShare
import com.unraiddesk.app.core.security.KeystoreCryptoManager
import com.unraiddesk.app.core.security.LanVpnGuard
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    enum class SyncOutcome {
        NO_SERVER,
        BLOCKED_OFF_LAN,
        SYNCED,
        FAILED_WITH_CACHE
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as UnraidDeskApp
        val container = app.container
        val result = syncOnce(
            serverConfigDao = container.database.serverConfigDao(),
            snapshotDao = container.database.cachedMetricSnapshotDao(),
            lanVpnGuard = container.lanVpnGuard,
            cryptoManager = container.cryptoManager,
            graphQlClient = container.graphQlClient
        )

        applySyncResultToWidgets(applicationContext, result)
        return Result.success()
    }

    data class SyncResult(val outcome: SyncOutcome, val message: String? = null)

    companion object {
        private const val PERIODIC_WORK_NAME = "unraiddesk_widget_periodic_sync"
        private const val EXPEDITED_WORK_NAME = "unraiddesk_widget_expedited_sync"

        suspend fun syncOnce(
            serverConfigDao: ServerConfigDao,
            snapshotDao: CachedMetricSnapshotDao,
            lanVpnGuard: LanVpnGuard,
            cryptoManager: KeystoreCryptoManager,
            graphQlClient: UnraidGraphQlClient,
            json: Json = Json { ignoreUnknownKeys = true }
        ): SyncResult {
            val server = serverConfigDao.getPrimaryServer()
                ?: return SyncResult(SyncOutcome.NO_SERVER)

            val guard = lanVpnGuard.validateHost(server.host)
            if (guard is LanVpnGuard.GuardResult.Blocked) {
                return SyncResult(SyncOutcome.BLOCKED_OFF_LAN, guard.message)
            }

            return try {
                val apiKey = cryptoManager.decryptString(server.encryptedApiKey)
                val baseUrl = server.baseUrl()
                val dashboard = graphQlClient.fetchDashboard(baseUrl, apiKey)
                val shares = graphQlClient.fetchShares(baseUrl, apiKey)
                val variant = when (server.dockerRootVariant) {
                    DockerRootVariant.DOCKER_CONTAINERS.name -> DockerRootVariant.DOCKER_CONTAINERS
                    else -> DockerRootVariant.DOCKER_OBJ
                }
                val containers = graphQlClient.fetchDockerContainers(baseUrl, apiKey, variant)
                val now = System.currentTimeMillis()
                val allDisks = dashboard.array.disks + dashboard.array.caches

                snapshotDao.upsert(
                    CachedMetricSnapshotEntity(
                        serverId = server.id,
                        osInfoJson = json.encodeToString(ServerInfo.serializer(), dashboard.info),
                        cpuPercent = dashboard.metrics.cpuPercent,
                        ramPercent = dashboard.metrics.ramPercent,
                        ramUsedBytes = dashboard.metrics.ramUsedBytes,
                        ramTotalBytes = dashboard.metrics.ramTotalBytes,
                        arrayState = dashboard.array.state,
                        arrayUsedBytes = dashboard.array.usedBytes,
                        arrayTotalBytes = dashboard.array.totalBytes,
                        parityStatus = dashboard.array.parityCheckStatus.status,
                        parityProgress = dashboard.array.parityCheckStatus.progressPercent,
                        sharesJson = json.encodeToString(ListSerializer(UnraidShare.serializer()), shares),
                        containersJson = json.encodeToString(ListSerializer(DockerContainer.serializer()), containers),
                        disksJson = json.encodeToString(ListSerializer(ArrayDisk.serializer()), allDisks),
                        updatedAtMillis = now
                    )
                )
                SyncResult(SyncOutcome.SYNCED)
            } catch (e: Exception) {
                SyncResult(SyncOutcome.FAILED_WITH_CACHE, e.message ?: "Sync failed")
            }
        }

        suspend fun applySyncResultToWidgets(context: Context, result: SyncResult) {
            val glanceIds = GlanceAppWidgetManager(context)
                .getGlanceIds(UnraidDeskWidget::class.java)
            val now = System.currentTimeMillis()
            glanceIds.forEach { glanceId ->
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[WidgetPrefKeys.IS_REFRESHING] = false
                    when (result.outcome) {
                        SyncOutcome.SYNCED -> {
                            prefs[WidgetPrefKeys.IS_OFFLINE] = false
                            prefs.remove(WidgetPrefKeys.OFFLINE_REASON)
                            prefs[WidgetPrefKeys.LAST_UPDATED_MILLIS] = now
                        }
                        SyncOutcome.BLOCKED_OFF_LAN, SyncOutcome.FAILED_WITH_CACHE -> {
                            prefs[WidgetPrefKeys.IS_OFFLINE] = true
                            prefs[WidgetPrefKeys.OFFLINE_REASON] = result.message.orEmpty()
                        }
                        SyncOutcome.NO_SERVER -> Unit
                    }
                }
            }
            UnraidDeskWidget().updateAll(context)
        }

        fun schedulePeriodic(context: Context, intervalMinutes: Int) {
            val minutes = intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                EXPEDITED_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
