package com.unraiddesk.app.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "unraiddesk_prefs")

private val locationNamesMapSerializer = MapSerializer(String.serializer(), String.serializer())

internal fun encodeLocationDisplayNames(names: Map<String, String>): String =
    Json.encodeToString(locationNamesMapSerializer, names)

internal fun decodeLocationDisplayNames(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString(locationNamesMapSerializer, raw)
    }.getOrDefault(emptyMap())
}

/**
 * Stores non-secret application and widget settings in Preferences DataStore.
 * Secrets (API keys, SMB passwords) are NEVER stored here — they use KeystoreCryptoManager + Room.
 */
class AppPreferencesRepository(private val context: Context) {

    val pollingIntervalSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_POLLING_SECONDS] ?: DEFAULT_POLLING_SECONDS).coerceIn(5, 60)
    }

    val widgetRefreshMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_WIDGET_REFRESH_MINUTES] ?: DEFAULT_WIDGET_REFRESH_MINUTES).coerceIn(1, 60)
    }

    val dualPaneDefault: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DUAL_PANE_DEFAULT] ?: false
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val criticalContainerNames: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_CRITICAL_CONTAINERS] ?: emptySet()
    }

    val pinnedLocationIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PINNED_LOCATION_IDS] ?: emptySet()
    }

    val hiddenLocationIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_HIDDEN_LOCATION_IDS] ?: emptySet()
    }

    val locationDisplayNames: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decodeLocationDisplayNames(prefs[KEY_LOCATION_DISPLAY_NAMES])
    }

    val explorerSidebarCollapsed: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EXPLORER_SIDEBAR_COLLAPSED] ?: false
    }

    suspend fun setPollingIntervalSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_POLLING_SECONDS] = seconds.coerceIn(5, 60)
        }
    }

    suspend fun setWidgetRefreshMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WIDGET_REFRESH_MINUTES] = minutes.coerceIn(1, 60)
        }
    }

    suspend fun setDualPaneDefault(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DUAL_PANE_DEFAULT] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun setCriticalContainerNames(names: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CRITICAL_CONTAINERS] = names
        }
    }

    suspend fun togglePinnedLocation(mountId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_LOCATION_IDS] ?: emptySet()
            prefs[KEY_PINNED_LOCATION_IDS] = if (mountId in current) {
                current - mountId
            } else {
                current + mountId
            }
        }
    }

    suspend fun setLocationHidden(mountId: String, hidden: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LOCATION_IDS] ?: emptySet()
            prefs[KEY_HIDDEN_LOCATION_IDS] = if (hidden) {
                current + mountId
            } else {
                current - mountId
            }
        }
    }

    suspend fun setLocationDisplayName(mountId: String, customName: String?) {
        context.dataStore.edit { prefs ->
            val updated = decodeLocationDisplayNames(prefs[KEY_LOCATION_DISPLAY_NAMES]).toMutableMap()
            val trimmed = customName?.trim()
            if (trimmed.isNullOrEmpty()) {
                updated.remove(mountId)
            } else {
                updated[mountId] = trimmed
            }
            prefs[KEY_LOCATION_DISPLAY_NAMES] = encodeLocationDisplayNames(updated)
        }
    }

    suspend fun setExplorerSidebarCollapsed(collapsed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_EXPLORER_SIDEBAR_COLLAPSED] = collapsed
        }
    }

    enum class ThemeMode {
        SYSTEM, LIGHT, DARK
    }

    companion object {
        const val DEFAULT_POLLING_SECONDS = 10
        const val DEFAULT_WIDGET_REFRESH_MINUTES = 5

        private val KEY_POLLING_SECONDS = intPreferencesKey("polling_interval_seconds")
        private val KEY_WIDGET_REFRESH_MINUTES = intPreferencesKey("widget_refresh_minutes")
        private val KEY_DUAL_PANE_DEFAULT = booleanPreferencesKey("dual_pane_default")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_CRITICAL_CONTAINERS = stringSetPreferencesKey("critical_containers")
        private val KEY_PINNED_LOCATION_IDS = stringSetPreferencesKey("explorer_pinned_location_ids")
        private val KEY_HIDDEN_LOCATION_IDS = stringSetPreferencesKey("explorer_hidden_location_ids")
        private val KEY_LOCATION_DISPLAY_NAMES = stringPreferencesKey("explorer_location_display_names_json")
        private val KEY_EXPLORER_SIDEBAR_COLLAPSED = booleanPreferencesKey("explorer_sidebar_collapsed")
    }
}
