package com.unraiddesk.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.unraiddesk.app.MainActivity
import com.unraiddesk.app.R
import com.unraiddesk.app.UnraidDeskApp
import com.unraiddesk.app.core.data.db.CachedMetricSnapshotEntity
import com.unraiddesk.app.core.data.db.DockerFolderEntity
import com.unraiddesk.app.core.data.db.DockerFolderMembershipEntity
import com.unraiddesk.app.core.data.db.ShareMountEntity
import com.unraiddesk.app.core.network.ArrayDisk
import com.unraiddesk.app.core.network.DockerContainer
import com.unraiddesk.app.core.network.ServerInfo
import com.unraiddesk.app.core.network.UnraidShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetRenderData(
    val serverName: String,
    val serverBaseUrl: String,
    val serverInfo: ServerInfo,
    val snapshot: CachedMetricSnapshotEntity?,
    val disks: List<ArrayDisk>,
    val shares: List<UnraidShare>,
    val monitoredShareNames: Set<String>,
    val containers: List<DockerContainer>,
    val folders: List<DockerFolderEntity>,
    val memberships: List<DockerFolderMembershipEntity>
)

data class DockerWidgetPartition(
    val groupedFolders: List<Pair<DockerFolderEntity, List<DockerContainer>>>,
    val ungroupedContainers: List<DockerContainer>
)

/**
 * Pure state reducer for widget rendering & unit testing.
 */
object WidgetStateReducer {
    fun filterMonitoredShares(
        shares: List<UnraidShare>,
        monitoredNames: Set<String>
    ): List<UnraidShare> =
        if (monitoredNames.isEmpty()) shares else shares.filter { it.name in monitoredNames }

    fun groupContainersByFolder(
        folders: List<DockerFolderEntity>,
        memberships: List<DockerFolderMembershipEntity>,
        containers: List<DockerContainer>,
        visibleFolderIds: Set<String>
    ): List<Pair<DockerFolderEntity, List<DockerContainer>>> {
        val byName = containers.associateBy { it.name }
        val filteredFolders = if (visibleFolderIds.isEmpty()) {
            folders
        } else {
            folders.filter { it.id.toString() in visibleFolderIds }
        }
        return filteredFolders.map { folder ->
            val members = memberships
                .filter { it.folderId == folder.id }
                .mapNotNull { byName[it.containerName] }
            folder to members
        }
    }

    fun partitionDockerContainers(
        folders: List<DockerFolderEntity>,
        memberships: List<DockerFolderMembershipEntity>,
        containers: List<DockerContainer>,
        visibleFolderIds: Set<String>
    ): DockerWidgetPartition {
        val grouped = groupContainersByFolder(
            folders = folders,
            memberships = memberships,
            containers = containers,
            visibleFolderIds = visibleFolderIds
        )
        val validFolderIds = folders.map { it.id }.toSet()
        val assignedNames = memberships
            .filter { it.folderId in validFolderIds }
            .map { it.containerName }
            .toSet()
        val ungrouped = containers
            .filter { it.name !in assignedNames }
            .sortedBy { it.name.lowercase(Locale.US) }
        return DockerWidgetPartition(
            groupedFolders = grouped,
            ungroupedContainers = ungrouped
        )
    }
}

class UnraidDeskWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as UnraidDeskApp
        val db = app.container.database
        val json = Json { ignoreUnknownKeys = true }

        val data = withContext(Dispatchers.IO) {
            val server = db.serverConfigDao().getPrimaryServer()
            val snapshot = server?.let { db.cachedMetricSnapshotDao().getLatest(it.id) }
            val shareMounts: List<ShareMountEntity> = server?.let {
                db.shareMountDao().getForServer(it.id)
            }.orEmpty()
            val folders = db.dockerFolderDao().observeFolders(1L).first()
            val memberships = db.dockerFolderDao().observeMemberships().first()

            val serverInfo = snapshot?.osInfoJson?.let { raw ->
                runCatching { json.decodeFromString(ServerInfo.serializer(), raw) }.getOrNull()
            } ?: ServerInfo()
            val disks = snapshot?.disksJson?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(ArrayDisk.serializer()), raw) }.getOrNull()
            }.orEmpty()
            val shares = snapshot?.sharesJson?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(UnraidShare.serializer()), raw) }.getOrNull()
            }.orEmpty()
            val containers = snapshot?.containersJson?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(DockerContainer.serializer()), raw) }.getOrNull()
            }.orEmpty()

            WidgetRenderData(
                serverName = server?.name ?: "UnraidDesk",
                serverBaseUrl = server?.baseUrl().orEmpty(),
                serverInfo = serverInfo,
                snapshot = snapshot,
                disks = disks,
                shares = shares,
                monitoredShareNames = shareMounts.filter { it.isMonitored }.map { it.shareName }.toSet(),
                containers = containers,
                folders = folders,
                memberships = memberships
            )
        }

        provideContent {
            GlanceTheme {
                WidgetContent(data = data)
            }
        }
    }
}

@Composable
private fun WidgetContent(data: WidgetRenderData) {
    val prefs = currentState<Preferences>()
    val themeConfig = WidgetThemeConfig.fromPreferences(prefs)
    val palette = WidgetTheme.resolveGlancePalette(themeConfig)
    val activeTab = when (prefs[WidgetPrefKeys.ACTIVE_TAB]) {
        WidgetTab.SHARES.name -> WidgetTab.SHARES
        WidgetTab.DOCKER.name -> WidgetTab.DOCKER
        else -> WidgetTab.DASHBOARD
    }
    val expandedFolders = prefs[WidgetPrefKeys.EXPANDED_FOLDER_IDS] ?: emptySet()
    val visibleFolders = prefs[WidgetPrefKeys.VISIBLE_FOLDER_IDS] ?: emptySet()
    val isOffline = prefs[WidgetPrefKeys.IS_OFFLINE] ?: false
    val isRefreshing = prefs[WidgetPrefKeys.IS_REFRESHING] ?: false
    val lastUpdated = prefs[WidgetPrefKeys.LAST_UPDATED_MILLIS]
        ?: data.snapshot?.updatedAtMillis
        ?: 0L

    val innerContent: @Composable () -> Unit = {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(if (themeConfig.showBorder) 19.dp else 20.dp)
                .background(palette.background)
                .padding(12.dp)
        ) {
            WidgetHeader(
                serverName = data.serverName,
                isRefreshing = isRefreshing,
                isOffline = isOffline,
                lastUpdatedMillis = lastUpdated,
                palette = palette
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            WidgetSegmentedTabBar(
                activeTab = activeTab,
                palette = palette
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            when (activeTab) {
                WidgetTab.DASHBOARD -> DashboardTabContent(
                    snapshot = data.snapshot,
                    serverInfo = data.serverInfo,
                    disks = data.disks,
                    palette = palette
                )
                WidgetTab.SHARES -> SharesTabContent(
                    shares = WidgetStateReducer.filterMonitoredShares(
                        data.shares,
                        data.monitoredShareNames
                    ),
                    palette = palette
                )
                WidgetTab.DOCKER -> DockerTabContent(
                    baseUrl = data.serverBaseUrl,
                    folders = data.folders,
                    memberships = data.memberships,
                    containers = data.containers,
                    expandedFolders = expandedFolders,
                    visibleFolders = visibleFolders,
                    palette = palette
                )
            }
        }
    }

    if (themeConfig.showBorder) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(palette.border)
                .padding(1.dp)
        ) {
            innerContent()
        }
    } else {
        innerContent()
    }
}

@Composable
private fun WidgetHeader(
    serverName: String,
    isRefreshing: Boolean,
    isOffline: Boolean,
    lastUpdatedMillis: Long,
    palette: ResolvedGlancePalette
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                text = serverName,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )
            )
            val statusText: String
            val statusColor = when {
                isRefreshing -> {
                    statusText = "Updating…"
                    palette.primary
                }
                isOffline -> {
                    statusText = if (lastUpdatedMillis > 0L) {
                        "Offline · ${formatRelativeAge(lastUpdatedMillis)}"
                    } else {
                        "Offline · Cached"
                    }
                    palette.statusError
                }
                lastUpdatedMillis > 0L -> {
                    statusText = "Updated ${formatClockTime(lastUpdatedMillis)}"
                    palette.onSurfaceVariant
                }
                else -> {
                    statusText = "Tap to connect server"
                    palette.onSurfaceVariant
                }
            }
            Text(
                text = statusText,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = statusColor
                )
            )
        }

        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .cornerRadius(10.dp)
                .background(palette.card)
                .clickable(actionRunCallback<RefreshWidgetAction>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = "Refresh widget",
                colorFilter = ColorFilter.tint(
                    if (isRefreshing) palette.primary else palette.onSurface
                ),
                modifier = GlanceModifier.size(18.dp)
            )
        }

        Spacer(modifier = GlanceModifier.width(6.dp))

        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .cornerRadius(10.dp)
                .background(palette.card)
                .clickable(actionStartActivity<WidgetConfigActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_settings),
                contentDescription = "Widget settings",
                colorFilter = ColorFilter.tint(palette.onSurfaceVariant),
                modifier = GlanceModifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun WidgetSegmentedTabBar(
    activeTab: WidgetTab,
    palette: ResolvedGlancePalette
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(palette.tabTrack)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WidgetTab.entries.forEach { tab ->
            val isSelected = tab == activeTab
            val label = when (tab) {
                WidgetTab.DASHBOARD -> "Dashboard"
                WidgetTab.SHARES -> "Shares"
                WidgetTab.DOCKER -> "Docker"
            }
            val unselectedBg = ColorProvider(day = Color.Transparent, night = Color.Transparent)
            val pillModifier = GlanceModifier
                .defaultWeight()
                .cornerRadius(9.dp)
                .background(if (isSelected) palette.tabSelected else unselectedBg)
                .padding(vertical = 5.dp, horizontal = 4.dp)
                .clickable(
                    actionRunCallback<SwitchWidgetTabAction>(
                        actionParametersOf(WidgetPrefKeys.PARAM_TAB to tab.name)
                    )
                )
            Box(
                modifier = pillModifier,
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) palette.onPrimary else palette.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

@Composable
private fun DashboardTabContent(
    snapshot: CachedMetricSnapshotEntity?,
    serverInfo: ServerInfo,
    disks: List<ArrayDisk>,
    palette: ResolvedGlancePalette
) {
    if (snapshot == null) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(14.dp)
                .background(palette.card)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Open UnraidDesk to connect your server.",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = palette.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            )
        }
        return
    }

    val isWide = LocalSize.current.width >= 320.dp
    val cpuFraction = (snapshot.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val ramFraction = (snapshot.ramPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val arrayFraction = if (snapshot.arrayTotalBytes > 0L) {
        (snapshot.arrayUsedBytes.toDouble() / snapshot.arrayTotalBytes.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        item {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                MetricTileCard(
                    iconRes = R.drawable.ic_widget_cpu,
                    title = "CPU",
                    value = String.format(Locale.US, "%.0f%%", snapshot.cpuPercent.coerceIn(0.0, 100.0)),
                    subtitle = if (serverInfo.cpuCores > 0) {
                        "${serverInfo.cpuCores}c / ${serverInfo.cpuThreads}t"
                    } else {
                        "Total load"
                    },
                    progress = cpuFraction,
                    palette = palette,
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                MetricTileCard(
                    iconRes = R.drawable.ic_widget_memory,
                    title = "Memory",
                    value = String.format(Locale.US, "%.0f%%", snapshot.ramPercent.coerceIn(0.0, 100.0)),
                    subtitle = if (snapshot.ramTotalBytes > 0L) {
                        if (isWide) {
                            "${formatBytes(snapshot.ramUsedBytes)} / ${formatBytes(snapshot.ramTotalBytes)}"
                        } else {
                            formatBytes(snapshot.ramUsedBytes)
                        }
                    } else {
                        "RAM usage"
                    },
                    progress = ramFraction,
                    palette = palette,
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }

        item {
            Spacer(modifier = GlanceModifier.height(8.dp))
            ArrayHealthCard(
                arrayState = snapshot.arrayState,
                usedBytes = snapshot.arrayUsedBytes,
                totalBytes = snapshot.arrayTotalBytes,
                parityStatus = snapshot.parityStatus,
                progress = arrayFraction,
                palette = palette
            )
        }

        if (disks.isNotEmpty()) {
            item {
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = "Disks & Caches (${disks.size})",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurfaceVariant
                    ),
                    modifier = GlanceModifier.padding(bottom = 4.dp)
                )
            }
            items(disks) { disk ->
                DiskRowCard(
                    disk = disk,
                    isWide = isWide,
                    palette = palette
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MetricTileCard(
    iconRes: Int,
    title: String,
    value: String,
    subtitle: String,
    progress: Float,
    palette: ResolvedGlancePalette,
    modifier: GlanceModifier = GlanceModifier
) {
    val barColor = when {
        progress >= 0.9f -> palette.statusError
        progress >= 0.75f -> palette.statusWarning
        else -> palette.primary
    }
    Column(
        modifier = modifier
            .cornerRadius(14.dp)
            .background(palette.card)
            .padding(10.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = title,
                colorFilter = ColorFilter.tint(palette.primary),
                modifier = GlanceModifier.size(14.dp)
            )
            Spacer(modifier = GlanceModifier.width(5.dp))
            Text(
                text = title,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onSurfaceVariant
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = value,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            color = barColor,
            backgroundColor = palette.progressTrack,
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(6.dp)
                .cornerRadius(3.dp)
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = subtitle,
            maxLines = 1,
            style = TextStyle(
                fontSize = 10.sp,
                color = palette.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun ArrayHealthCard(
    arrayState: String,
    usedBytes: Long,
    totalBytes: Long,
    parityStatus: String,
    progress: Float,
    palette: ResolvedGlancePalette
) {
    val isStarted = arrayState.equals("STARTED", ignoreCase = true)
    val stateColor = if (isStarted) palette.statusRunning else palette.statusWarning
    val barColor = when {
        progress >= 0.92f -> palette.statusError
        progress >= 0.80f -> palette.statusWarning
        else -> palette.primary
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(14.dp)
            .background(palette.card)
            .padding(10.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_storage),
                contentDescription = "Array storage",
                colorFilter = ColorFilter.tint(palette.primary),
                modifier = GlanceModifier.size(14.dp)
            )
            Spacer(modifier = GlanceModifier.width(5.dp))
            Text(
                text = "Array",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = arrayState,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = stateColor
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            color = barColor,
            backgroundColor = palette.progressTrack,
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(6.dp)
                .cornerRadius(3.dp)
        )
        Spacer(modifier = GlanceModifier.height(5.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (totalBytes > 0L) {
                    "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}"
                } else {
                    "Capacity unavailable"
                },
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = palette.onSurface
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "Parity: $parityStatus",
                maxLines = 1,
                style = TextStyle(
                    fontSize = 10.sp,
                    color = palette.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun DiskRowCard(
    disk: ArrayDisk,
    isWide: Boolean,
    palette: ResolvedGlancePalette
) {
    val isHealthy = disk.status.equals("DISK_OK", ignoreCase = true) ||
        disk.status.equals("OK", ignoreCase = true)
    val dotColor = if (isHealthy) palette.statusRunning else palette.statusWarning
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(10.dp)
            .background(palette.card)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "●",
            style = TextStyle(fontSize = 10.sp, color = dotColor)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = if (disk.isCache) "${disk.name} (Cache)" else disk.name,
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = palette.onSurface
            ),
            modifier = GlanceModifier.defaultWeight()
        )
        if (isWide && (disk.numReads > 0L || disk.numWrites > 0L)) {
            Text(
                text = "R:${formatCompactCount(disk.numReads)} W:${formatCompactCount(disk.numWrites)}",
                maxLines = 1,
                style = TextStyle(fontSize = 10.sp, color = palette.onSurfaceVariant)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
        }
        disk.tempCelsius?.let { temp ->
            val tempColor = when {
                temp >= 55 -> palette.statusError
                temp >= 45 -> palette.statusWarning
                else -> palette.onSurfaceVariant
            }
            Text(
                text = "${temp}°C",
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = tempColor
                )
            )
        }
    }
}

@Composable
private fun SharesTabContent(
    shares: List<UnraidShare>,
    palette: ResolvedGlancePalette
) {
    if (shares.isEmpty()) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(14.dp)
                .background(palette.card)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No monitored shares cached yet.",
                style = TextStyle(fontSize = 12.sp, color = palette.onSurfaceVariant)
            )
        }
        return
    }
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(shares) { share ->
            val totalBytes = if (share.sizeBytes > 0L) {
                share.sizeBytes
            } else {
                share.usedBytes + share.freeBytes
            }
            val progress = if (totalBytes > 0L) {
                (share.usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            val barColor = when {
                progress >= 0.90f -> palette.statusError
                progress >= 0.75f -> palette.statusWarning
                else -> palette.primary
            }

            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(palette.card)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = share.name,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "${formatBytes(share.freeBytes)} free",
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.primary
                        )
                    )
                }
                Spacer(modifier = GlanceModifier.height(5.dp))
                LinearProgressIndicator(
                    progress = progress,
                    color = barColor,
                    backgroundColor = palette.progressTrack,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .cornerRadius(3.dp)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = if (totalBytes > 0L) {
                        "${formatBytes(share.usedBytes)} / ${formatBytes(totalBytes)}"
                    } else {
                        "Usage unavailable"
                    },
                    maxLines = 1,
                    style = TextStyle(fontSize = 10.sp, color = palette.onSurfaceVariant)
                )
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
        }
    }
}

@Composable
private fun DockerTabContent(
    baseUrl: String,
    folders: List<DockerFolderEntity>,
    memberships: List<DockerFolderMembershipEntity>,
    containers: List<DockerContainer>,
    expandedFolders: Set<String>,
    visibleFolders: Set<String>,
    palette: ResolvedGlancePalette
) {
    val partition = WidgetStateReducer.partitionDockerContainers(
        folders = folders,
        memberships = memberships,
        containers = containers,
        visibleFolderIds = visibleFolders
    )
    if (partition.groupedFolders.isEmpty() && partition.ungroupedContainers.isEmpty()) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(14.dp)
                .background(palette.card)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No Docker containers cached yet.",
                style = TextStyle(fontSize = 12.sp, color = palette.onSurfaceVariant)
            )
        }
        return
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(partition.groupedFolders) { (folder, members) ->
            val fid = folder.id.toString()
            val isExpanded = fid in expandedFolders
            val running = members.count { it.isRunning }

            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(palette.card)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(
                            actionRunCallback<ToggleFolderAction>(
                                actionParametersOf(WidgetPrefKeys.PARAM_FOLDER_ID to fid)
                            )
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = folder.name,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "$running/${members.size}",
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (running > 0) palette.statusRunning else palette.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(
                            if (isExpanded) R.drawable.ic_widget_expand_less else R.drawable.ic_widget_expand_more
                        ),
                        contentDescription = if (isExpanded) "Collapse folder" else "Expand folder",
                        colorFilter = ColorFilter.tint(palette.onSurfaceVariant),
                        modifier = GlanceModifier.size(18.dp)
                    )
                }

                if (isExpanded && members.isNotEmpty()) {
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    members.forEach { container ->
                        DockerContainerRow(
                            container = container,
                            baseUrl = baseUrl,
                            palette = palette
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                }
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
        }

        if (partition.ungroupedContainers.isNotEmpty()) {
            item {
                Text(
                    text = "Ungrouped Containers (${partition.ungroupedContainers.size})",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurfaceVariant
                    ),
                    modifier = GlanceModifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }
            items(partition.ungroupedContainers) { container ->
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(12.dp)
                        .background(palette.card)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    DockerContainerRow(
                        container = container,
                        baseUrl = baseUrl,
                        palette = palette
                    )
                }
                Spacer(modifier = GlanceModifier.height(5.dp))
            }
        }
    }
}

@Composable
private fun DockerContainerRow(
    container: DockerContainer,
    baseUrl: String,
    palette: ResolvedGlancePalette
) {
    val dotColor = if (container.isRunning) palette.statusRunning else palette.onSurfaceVariant
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "●",
            style = TextStyle(fontSize = 10.sp, color = dotColor)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = container.name,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onSurface
                )
            )
            if (container.status.isNotBlank()) {
                Text(
                    text = container.status,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = palette.onSurfaceVariant
                    )
                )
            }
        }

        container.webUiUrl?.let { url ->
            Box(
                modifier = GlanceModifier
                    .size(28.dp)
                    .cornerRadius(8.dp)
                    .background(palette.cardElevated)
                    .clickable(
                        actionRunCallback<OpenUrlAction>(
                            actionParametersOf(WidgetPrefKeys.PARAM_URL to url)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_open_browser),
                    contentDescription = "Open ${container.name} WebUI",
                    colorFilter = ColorFilter.tint(palette.onSurface),
                    modifier = GlanceModifier.size(15.dp)
                )
            }
            Spacer(modifier = GlanceModifier.width(4.dp))
        }

        if (baseUrl.isNotBlank()) {
            Box(
                modifier = GlanceModifier
                    .size(28.dp)
                    .cornerRadius(8.dp)
                    .background(palette.cardElevated)
                    .clickable(
                        actionRunCallback<OpenUrlAction>(
                            actionParametersOf(WidgetPrefKeys.PARAM_URL to "$baseUrl/Docker")
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_logs),
                    contentDescription = "Open ${container.name} Logs",
                    colorFilter = ColorFilter.tint(palette.onSurfaceVariant),
                    modifier = GlanceModifier.size(15.dp)
                )
            }
            Spacer(modifier = GlanceModifier.width(4.dp))
        }

        Box(
            modifier = GlanceModifier
                .size(28.dp)
                .cornerRadius(8.dp)
                .background(palette.cardElevated)
                .clickable(
                    actionRunCallback<OpenContainerControlsAction>(
                        actionParametersOf(
                            WidgetPrefKeys.PARAM_CONTAINER_ID to container.id,
                            WidgetPrefKeys.PARAM_CONTAINER_NAME to container.name,
                            WidgetPrefKeys.PARAM_CONTAINER_STATE to container.state,
                            WidgetPrefKeys.PARAM_CONTAINER_STATUS to container.status
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_power),
                contentDescription = "Control ${container.name}",
                colorFilter = ColorFilter.tint(palette.primary),
                modifier = GlanceModifier.size(15.dp)
            )
        }
    }
}

private fun formatClockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatRelativeAge(millis: Long): String {
    val mins = ((System.currentTimeMillis() - millis) / 60_000L).coerceAtLeast(0L)
    return if (mins == 0L) "just now" else "${mins}m ago"
}

private fun formatCompactCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}
