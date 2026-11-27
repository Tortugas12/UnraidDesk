package com.unraiddesk.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.unraiddesk.app.UnraidDeskApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UnraidDeskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnraidDeskWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val app = context.applicationContext as? UnraidDeskApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val minutes = app.container.preferencesRepository.widgetRefreshMinutes.first()
            WidgetScheduler.schedule(context.applicationContext, minutes)
            WidgetSyncWorker.enqueueExpedited(context.applicationContext)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetScheduler.cancelAll(context.applicationContext)
    }
}
