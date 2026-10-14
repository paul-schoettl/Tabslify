package com.tabslify.core.functions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

object ExactAlarms {
    const val MIN_WINDOW_MS = 10 * 60_000L

    fun setExactOrWindow(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        windowMs: Long
    ): Boolean {
        return try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    maxOf(windowMs, MIN_WINDOW_MS),
                    pendingIntent
                )
            }
            true
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Alarm konnte nicht gesetzt werden: ", e)
            false
        }
    }

    fun setExactElapsed(
        alarmManager: AlarmManager,
        triggerAtElapsedRealtime: Long,
        pendingIntent: PendingIntent
    ): Boolean {
        return try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedRealtime,
                    pendingIntent
                )
                true
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedRealtime,
                    pendingIntent
                )
                false
            }
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Alarm konnte nicht gesetzt werden: ", e)
            false
        }
    }

    fun requestPermission(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Anfrage der genauen-Alarm-Berechtigung fehlgeschlagen: ", e)
        }
    }
}