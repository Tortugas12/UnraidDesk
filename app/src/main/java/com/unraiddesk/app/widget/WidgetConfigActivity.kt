package com.unraiddesk.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.unraiddesk.app.UnraidDeskApp
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository.ThemeMode
import com.unraiddesk.app.ui.theme.UnraidDeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Widget configuration & reconfiguration activity with live preview, theme presets,
 * opacity/border customization, custom color swatches, and sub-15m refresh cadence.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val container = (application as UnraidDeskApp).container

        setContent {
            val scope = rememberCoroutineScope()
            val themeMode by container.preferencesRepository.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)
            val serverConfig by container.database.serverConfigDao().observePrimaryServer()
                .collectAsState(initial = null)
            val shares by container.database.shareMountDao().observeForServer(1L)
                .collectAsState(initial = emptyList())
            val folders by container.database.dockerFolderDao().observeFolders(1L)
                .collectAsState(initial = emptyList())
            val savedRefresh by container.preferencesRepository.widgetRefreshMinutes
                .collectAsState(initial = AppPreferencesRepository.DEFAULT_WIDGET_REFRESH_MINUTES)

            var defaultTab by remember { mutableStateOf(WidgetTab.DASHBOARD) }
            var themeConfig by remember { mutableStateOf(WidgetThemeConfig()) }
            var refreshMinutes by remember(savedRefresh) { mutableIntStateOf(savedRefresh) }
            var selectedShareIds by remember(shares) {
                mutableStateOf(shares.filter { it.isMonitored }.map { it.id }.toSet())
            }
            var selectedFolderIds by remember(folders) {
                mutableStateOf(folders.map { it.id.toString() }.toSet())
            }
            var loadedFolderIdsFromPrefs by remember { mutableStateOf(false) }

            LaunchedEffect(appWidgetId) {
                withContext(Dispatchers.IO) {
                    val glanceManager = GlanceAppWidgetManager(this@WidgetConfigActivity)
                    val targetGlanceId = runCatching {
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            glanceManager.getGlanceIdBy(appWidgetId)
                        } else {
                            glanceManager.getGlanceIds(UnraidDeskWidget::class.java).firstOrNull()
                        }
                    }.getOrNull() ?: return@withContext

                    val existingPrefs = runCatching {
                        getAppWidgetState(
                            context = this@WidgetConfigActivity,
                            definition = PreferencesGlanceStateDefinition,
                            glanceId = targetGlanceId
                        )
                    }.getOrNull() ?: return@withContext

                    val restoredTab = when (existingPrefs[WidgetPrefKeys.ACTIVE_TAB]) {
                        WidgetTab.SHARES.name -> WidgetTab.SHARES
                        WidgetTab.DOCKER.name -> WidgetTab.DOCKER
                        else -> WidgetTab.DASHBOARD
                    }
                    val restoredTheme = WidgetThemeConfig.fromPreferences(existingPrefs)
                    val restoredFolders = existingPrefs[WidgetPrefKeys.VISIBLE_FOLDER_IDS]

                    withContext(Dispatchers.Main) {
                        defaultTab = restoredTab
                        themeConfig = restoredTheme
                        if (!restoredFolders.isNullOrEmpty()) {
                            selectedFolderIds = restoredFolders
                            loadedFolderIdsFromPrefs = true
                        }
                    }
                }
            }

            LaunchedEffect(folders, loadedFolderIdsFromPrefs) {
                if (!loadedFolderIdsFromPrefs && folders.isNotEmpty()) {
                    selectedFolderIds = folders.map { it.id.toString() }.toSet()
                }
            }

            UnraidDeskTheme(themeMode = themeMode) {
                val isSystemDark = isSystemInDarkTheme()
                val previewPalette = remember(themeConfig, isSystemDark) {
                    WidgetTheme.resolveComposePalette(themeConfig, isSystemDark)
                }

                Scaffold { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Configure UnraidDesk Widget",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )

                            WidgetLivePreviewCard(
                                serverName = serverConfig?.name ?: "Tower",
                                activeTab = defaultTab,
                                showBorder = themeConfig.showBorder,
                                palette = previewPalette
                            )

                            // Theme Preset & Opacity Card
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "Appearance & Theme",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        WidgetThemePreset.entries.forEach { preset ->
                                            FilterChip(
                                                selected = themeConfig.preset == preset,
                                                onClick = {
                                                    themeConfig = themeConfig.copy(
                                                        preset = preset,
                                                        opacityPercent = preset.defaultOpacityPercent,
                                                        showBorder = preset.defaultShowBorder
                                                    )
                                                },
                                                label = { Text(preset.displayName) }
                                            )
                                        }
                                    }

                                    if (themeConfig.preset == WidgetThemePreset.CUSTOM) {
                                        SwatchPickerRow(
                                            label = "Background",
                                            swatches = WidgetThemeConfig.CUSTOM_BG_SWATCHES,
                                            selectedHex = themeConfig.customBgHex,
                                            onSelect = { hex ->
                                                themeConfig = themeConfig.copy(customBgHex = hex)
                                            }
                                        )
                                        SwatchPickerRow(
                                            label = "Card Surface",
                                            swatches = WidgetThemeConfig.CUSTOM_CARD_SWATCHES,
                                            selectedHex = themeConfig.customCardHex,
                                            onSelect = { hex ->
                                                themeConfig = themeConfig.copy(customCardHex = hex)
                                            }
                                        )
                                        SwatchPickerRow(
                                            label = "Accent",
                                            swatches = WidgetThemeConfig.CUSTOM_ACCENT_SWATCHES,
                                            selectedHex = themeConfig.customAccentHex,
                                            onSelect = { hex ->
                                                themeConfig = themeConfig.copy(customAccentHex = hex)
                                            }
                                        )
                                    }

                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Background Opacity",
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            Text(
                                                text = "${themeConfig.opacityPercent}%",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = themeConfig.opacityPercent.toFloat(),
                                            onValueChange = { raw ->
                                                val snapped = ((raw / 5f).roundToInt() * 5)
                                                    .coerceIn(
                                                        WidgetThemeConfig.MIN_OPACITY_PERCENT,
                                                        WidgetThemeConfig.MAX_OPACITY_PERCENT
                                                    )
                                                themeConfig = themeConfig.copy(opacityPercent = snapped)
                                            },
                                            valueRange = WidgetThemeConfig.MIN_OPACITY_PERCENT.toFloat()..
                                                WidgetThemeConfig.MAX_OPACITY_PERCENT.toFloat(),
                                            steps = 15
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Subtle Glass Border",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = themeConfig.showBorder,
                                            onCheckedChange = { enabled ->
                                                themeConfig = themeConfig.copy(showBorder = enabled)
                                            }
                                        )
                                    }
                                }
                            }

                            // Default Tab & Refresh Cadence Card
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(text = "Default Tab", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        WidgetTab.entries.forEach { tab ->
                                            FilterChip(
                                                selected = defaultTab == tab,
                                                onClick = { defaultTab = tab },
                                                label = {
                                                    Text(tab.name.lowercase().replaceFirstChar { it.uppercase() })
                                                }
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Refresh Cadence",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(1, 5, 10, 15, 30, 60).forEach { mins ->
                                            FilterChip(
                                                selected = refreshMinutes == mins,
                                                onClick = { refreshMinutes = mins },
                                                label = { Text("${mins}m") }
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (refreshMinutes < 15) {
                                            "Updates every ${refreshMinutes}m while the screen is on (pauses when screen is off to save battery)."
                                        } else {
                                            "Updates every ${refreshMinutes}m via Android WorkManager."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (shares.isNotEmpty()) {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Monitored Shares",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        shares.forEach { share ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = share.id in selectedShareIds,
                                                    onCheckedChange = { checked ->
                                                        selectedShareIds = if (checked) {
                                                            selectedShareIds + share.id
                                                        } else {
                                                            selectedShareIds - share.id
                                                        }
                                                    }
                                                )
                                                Text(
                                                    text = share.shareName,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (folders.isNotEmpty()) {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Visible Docker Folders",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        folders.forEach { folder ->
                                            val fid = folder.id.toString()
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = fid in selectedFolderIds,
                                                    onCheckedChange = { checked ->
                                                        selectedFolderIds = if (checked) {
                                                            selectedFolderIds + fid
                                                        } else {
                                                            selectedFolderIds - fid
                                                        }
                                                    }
                                                )
                                                Text(
                                                    text = folder.name,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { finish() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val normalizedTheme = themeConfig.normalized()
                                            withContext(Dispatchers.IO) {
                                                container.preferencesRepository
                                                    .setWidgetRefreshMinutes(refreshMinutes)
                                                shares.forEach { s ->
                                                    container.database.shareMountDao()
                                                        .updateMonitored(s.id, s.id in selectedShareIds)
                                                }
                                                val glanceManager =
                                                    GlanceAppWidgetManager(this@WidgetConfigActivity)
                                                val targetIds =
                                                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                                        listOf(glanceManager.getGlanceIdBy(appWidgetId))
                                                    } else {
                                                        glanceManager.getGlanceIds(UnraidDeskWidget::class.java)
                                                    }
                                                targetIds.forEach { gid ->
                                                    updateAppWidgetState(this@WidgetConfigActivity, gid) { prefs ->
                                                        prefs[WidgetPrefKeys.ACTIVE_TAB] = defaultTab.name
                                                        prefs[WidgetPrefKeys.VISIBLE_FOLDER_IDS] = selectedFolderIds
                                                        prefs[WidgetPrefKeys.THEME_PRESET] =
                                                            normalizedTheme.preset.name
                                                        prefs[WidgetPrefKeys.THEME_OPACITY_PERCENT] =
                                                            normalizedTheme.opacityPercent
                                                        prefs[WidgetPrefKeys.THEME_SHOW_BORDER] =
                                                            normalizedTheme.showBorder
                                                        prefs[WidgetPrefKeys.THEME_CUSTOM_BG_HEX] =
                                                            normalizedTheme.customBgHex
                                                        prefs[WidgetPrefKeys.THEME_CUSTOM_CARD_HEX] =
                                                            normalizedTheme.customCardHex
                                                        prefs[WidgetPrefKeys.THEME_CUSTOM_ACCENT_HEX] =
                                                            normalizedTheme.customAccentHex
                                                    }
                                                    UnraidDeskWidget().update(this@WidgetConfigActivity, gid)
                                                }
                                                WidgetScheduler.schedule(applicationContext, refreshMinutes)
                                                WidgetSyncWorker.enqueueExpedited(applicationContext)
                                            }
                                            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                                setResult(
                                                    RESULT_OK,
                                                    Intent().putExtra(
                                                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                                                        appWidgetId
                                                    )
                                                )
                                            }
                                            finish()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save Widget")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetLivePreviewCard(
    serverName: String,
    activeTab: WidgetTab,
    showBorder: Boolean,
    palette: ResolvedComposePalette
) {
    // Rich wallpaper-like gradient backdrop so opacity and frosted borders are clearly visible
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E3A5F),
                        Color(0xFF312E81),
                        Color(0xFF7C2D12)
                    )
                )
            )
            .padding(16.dp)
    ) {
        val outerModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, palette.border, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .background(palette.background)
            .padding(12.dp)

        Column(
            modifier = outerModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = serverName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface
                    )
                    Text(
                        text = "Updated 12:34",
                        fontSize = 11.sp,
                        color = palette.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.card),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↻", color = palette.onSurface, fontSize = 14.sp)
                }
            }

            // Segmented pill tabs preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.tabTrack)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WidgetTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) palette.tabSelected else Color.Transparent)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) palette.onPrimary else palette.onSurfaceVariant
                        )
                    }
                }
            }

            // Sample CPU / RAM tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PreviewMetricTile(
                    title = "CPU",
                    value = "24%",
                    progress = 0.24f,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                PreviewMetricTile(
                    title = "Memory",
                    value = "58%",
                    progress = 0.58f,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PreviewMetricTile(
    title: String,
    value: String,
    progress: Float,
    palette: ResolvedComposePalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontSize = 11.sp, color = palette.onSurfaceVariant)
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            color = palette.primary,
            trackColor = palette.progressTrack,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun SwatchPickerRow(
    label: String,
    swatches: List<String>,
    selectedHex: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            swatches.forEach { hex ->
                val color = WidgetTheme.parseHexColor(hex, Color.DarkGray)
                val isSelected = selectedHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                        .clickable { onSelect(hex) }
                )
            }
        }
    }
}
