package com.tabslify.core.functions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.tabslify.core.logging.Log
import androidx.core.net.toUri

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

    fun canScheduleExact(context: Context): Boolean {
        return try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .canScheduleExactAlarms()
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Status der genauen-Alarm-Berechtigung unbekannt: ", e)
            false
        }
    }

    fun isExemptFromBatteryOptimizations(context: Context): Boolean {
        return try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Akku-Ausnahme konnte nicht geprueft werden: ", e)
            false
        }
    }

    fun requestPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e("ExactAlarms", "Anfrage der genauen-Alarm-Berechtigung fehlgeschlagen: ", e)
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri()
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.e("ExactAlarms", "App-Einstellungen nicht oeffenbar: ", e2)
            }
        }
    }
}