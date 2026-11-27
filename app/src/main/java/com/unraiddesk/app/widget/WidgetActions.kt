package com.unraiddesk.app.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.unraiddesk.app.UnraidDeskApp
import com.unraiddesk.app.core.security.LanVpnGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class WidgetTab {
    DASHBOARD,
    SHARES,
    DOCKER
}

object WidgetPrefKeys {
    val ACTIVE_TAB = stringPreferencesKey("widget_active_tab")
    val EXPANDED_FOLDER_IDS = stringSetPreferencesKey("widget_expanded_folder_ids")
    val VISIBLE_FOLDER_IDS = stringSetPreferencesKey("widget_visible_folder_ids")
    val IS_OFFLINE = booleanPreferencesKey("widget_is_offline")
    val OFFLINE_REASON = stringPreferencesKey("widget_offline_reason")
    val LAST_UPDATED_MILLIS = longPreferencesKey("widget_last_updated_millis")
    val IS_REFRESHING = booleanPreferencesKey("widget_is_refreshing")

    val THEME_PRESET = stringPreferencesKey("widget_theme_preset")
    val THEME_OPACITY_PERCENT = intPreferencesKey("widget_theme_opacity_percent")
    val THEME_SHOW_BORDER = booleanPreferencesKey("widget_theme_show_border")
    val THEME_CUSTOM_BG_HEX = stringPreferencesKey("widget_theme_custom_bg_hex")
    val THEME_CUSTOM_CARD_HEX = stringPreferencesKey("widget_theme_custom_card_hex")
    val THEME_CUSTOM_ACCENT_HEX = stringPreferencesKey("widget_theme_custom_accent_hex")

    val PARAM_TAB = ActionParameters.Key<String>("tab")
    val PARAM_FOLDER_ID = ActionParameters.Key<String>("folder_id")
    val PARAM_URL = ActionParameters.Key<String>("url")
    val PARAM_CONTAINER_ID = ActionParameters.Key<String>("container_id")
    val PARAM_CONTAINER_NAME = ActionParameters.Key<String>("container_name")
    val PARAM_CONTAINER_STATE = ActionParameters.Key<String>("container_state")
    val PARAM_CONTAINER_STATUS = ActionParameters.Key<String>("container_status")
}

class SwitchWidgetTabAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val targetTab = parameters[WidgetPrefKeys.PARAM_TAB] ?: WidgetTab.DASHBOARD.name
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetPrefKeys.ACTIVE_TAB] = targetTab
        }
        UnraidDeskWidget().update(context, glanceId)
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val app = context.applicationContext as? UnraidDeskApp ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetPrefKeys.IS_REFRESHING] = true
        }
        UnraidDeskWidget().update(context, glanceId)

        val container = app.container
        val result = withTimeoutOrNull(15_000L) {
            withContext(Dispatchers.IO) {
                WidgetSyncWorker.syncOnce(
                    serverConfigDao = container.database.serverConfigDao(),
                    snapshotDao = container.database.cachedMetricSnapshotDao(),
                    lanVpnGuard = container.lanVpnGuard,
                    cryptoManager = container.cryptoManager,
                    graphQlClient = container.graphQlClient
                )
            }
        } ?: WidgetSyncWorker.SyncResult(
            outcome = WidgetSyncWorker.SyncOutcome.FAILED_WITH_CACHE,
            message = "Refresh timed out"
        )
        WidgetSyncWorker.applySyncResultToWidgets(context.applicationContext, result)
    }
}

class ToggleFolderAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val folderId = parameters[WidgetPrefKeys.PARAM_FOLDER_ID] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetPrefKeys.EXPANDED_FOLDER_IDS] ?: emptySet()
            prefs[WidgetPrefKeys.EXPANDED_FOLDER_IDS] = if (folderId in current) {
                current - folderId
            } else {
                current + folderId
            }
        }
        UnraidDeskWidget().update(context, glanceId)
    }
}

class OpenUrlAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val url = parameters[WidgetPrefKeys.PARAM_URL] ?: return
        val app = context.applicationContext as? UnraidDeskApp ?: return
        val check = withContext(Dispatchers.IO) { app.container.lanVpnGuard.validateUrl(url) }
        if (check is LanVpnGuard.GuardResult.Allowed) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

class OpenContainerControlsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val containerId = parameters[WidgetPrefKeys.PARAM_CONTAINER_ID] ?: return
        val containerName = parameters[WidgetPrefKeys.PARAM_CONTAINER_NAME].orEmpty()
        val containerState = parameters[WidgetPrefKeys.PARAM_CONTAINER_STATE].orEmpty()
        val containerStatus = parameters[WidgetPrefKeys.PARAM_CONTAINER_STATUS].orEmpty()
        val intent = WidgetContainerActionActivity.createIntent(
            context = context,
            containerId = containerId,
            containerName = containerName,
            containerState = containerState,
            containerStatus = containerStatus
        )
        context.startActivity(intent)
    }
}
