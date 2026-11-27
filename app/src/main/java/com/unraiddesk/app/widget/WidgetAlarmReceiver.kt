package com.unraiddesk.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.unraiddesk.app.UnraidDeskApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles sub-15-minute widget refresh alarms while the screen is interactive.
 * Skips network I/O when the display is off and reschedules the next window cleanly.
 */
class WidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetScheduler.ACTION_WIDGET_ALARM_REFRESH) return
        val appContext = context.applicationContext
        val app = appContext as? UnraidDeskApp ?: return
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val isInteractive = powerManager?.isInteractive != false

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = app.container
                val glanceIds = GlanceAppWidgetManager(appContext)
                    .getGlanceIds(UnraidDeskWidget::class.java)
                if (glanceIds.isEmpty()) {
                    WidgetScheduler.cancelAll(appContext)
                    return@launch
                }

                if (isInteractive) {
                    val result = WidgetSyncWorker.syncOnce(
                        serverConfigDao = container.database.serverConfigDao(),
                        snapshotDao = container.database.cachedMetricSnapshotDao(),
                        lanVpnGuard = container.lanVpnGuard,
                        cryptoManager = container.cryptoManager,
                        graphQlClient = container.graphQlClient
                    )
                    WidgetSyncWorker.applySyncResultToWidgets(appContext, result)
                }

                val intervalMinutes = container.preferencesRepository.widgetRefreshMinutes.first()
                WidgetScheduler.schedule(appContext, intervalMinutes)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
