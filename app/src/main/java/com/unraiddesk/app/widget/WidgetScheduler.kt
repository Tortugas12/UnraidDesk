package com.unraiddesk.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Coordinates widget refresh scheduling:
 * - Intervals < 15 minutes use an inexact AlarmManager window (zero special permissions required)
 *   paired with a screen-on guard in [WidgetAlarmReceiver].
 * - Intervals >= 15 minutes use WorkManager periodic sync.
 */
object WidgetScheduler {
    const val ACTION_WIDGET_ALARM_REFRESH = "com.unraiddesk.app.widget.ACTION_ALARM_REFRESH"
    private const val REQUEST_CODE_ALARM = 4107
    private const val SUB_WORKMANAGER_THRESHOLD_MINUTES = 15

    fun schedule(context: Context, intervalMinutes: Int) {
        val clamped = intervalMinutes.coerceIn(1, 60)
        if (clamped < SUB_WORKMANAGER_THRESHOLD_MINUTES) {
            WidgetSyncWorker.cancelPeriodic(context)
            scheduleInexactAlarm(context, clamped)
        } else {
            cancelAlarm(context)
            WidgetSyncWorker.schedulePeriodic(context, clamped)
        }
    }

    fun cancelAll(context: Context) {
        cancelAlarm(context)
        WidgetSyncWorker.cancelPeriodic(context)
    }

    private fun scheduleInexactAlarm(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intervalMillis = intervalMinutes * 60_000L
        val windowMillis = if (intervalMinutes == 1) 15_000L else 30_000L
        val triggerAtMillis = System.currentTimeMillis() + intervalMillis
        alarmManager.setWindow(
            AlarmManager.RTC,
            triggerAtMillis,
            windowMillis,
            createAlarmPendingIntent(context)
        )
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(createAlarmPendingIntent(context))
    }

    private fun createAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetAlarmReceiver::class.java).apply {
            action = ACTION_WIDGET_ALARM_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
