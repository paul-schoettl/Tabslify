package com.tabslify.quiethoursnotificationhelper

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tabslify.R
import com.tabslify.core.activities.MainActivity
import com.tabslify.core.functions.errorInsert
import com.tabslify.core.functions.showSimpleNotificationExtern
import com.tabslify.core.objects.prvt
import com.tabslify.core.objects.tNotify
import com.tabslify.services.QuietHoursNotificationService.Companion.SCHOOL_SUMMARY_CHANNEL_ID
import com.tabslify.services.QuietHoursNotificationService.Companion.SCHOOL_SUMMARY_NOTIF_ID
import com.tabslify.tabs.focusguard.data.FocusGuardConfig
import com.tabslify.tabs.mediaplayer.MediaAnalyticsManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

suspend fun runDailyMusicSummary(context: Context) {
    try {
        kotlinx.coroutines.delay(500.milliseconds)
        MediaAnalyticsManager.init(context)
        val lastAiTimestamp = loadTodayOrYesterdayEntry(context)?.timestamp ?: 0L

        val sessions = MediaAnalyticsManager.dedupForStats(
            MediaAnalyticsManager.getSessions().filter { it.startedAt >= lastAiTimestamp }
        )
        if (sessions.isEmpty()) return

        val stats = buildSessionStatsText(sessions)
        val result = sendAiRequest(
            context,
            userMessage = stats,
            target = AiTarget.MusicSummary,
            serviceKey = "music_summary",
            provider = AiProvider.NVIDIA,
            model = "openai/gpt-oss-20b",
            onToken = {}
        ) ?: return

        val musicMs = sessions.filter { it.type == "music" }.sumOf { it.listenedMs }
        val podcastMs = sessions.filter { it.type == "podcast" }.sumOf { it.listenedMs }

        fun fmt(ms: Long): String {
            val mins = ms / 1000.0 / 60.0
            return if (mins < 1) "${(ms / 1000).toInt()}s" else "%.1f".format(mins) + " min"
        }

        val parts = mutableListOf<String>()
        if (musicMs > 0) parts += "🎵${fmt(musicMs)}"
        if (podcastMs > 0) parts += "🎙️${fmt(podcastMs)}"
        val suffix = parts.joinToString(" · ")

        val finalResult = if (suffix.isNotEmpty()) "$result\n\n$suffix" else result

        saveAiResponse(context, finalResult)
        aiResponseFlow.emit(
            AiResponseEntry(
                finalResult,
                System.currentTimeMillis(),
                getTodayKey()
            )
        )
        showSimpleNotificationExtern(
            context.getString(R.string.tages_zusammenfassung),
            finalResult,
            60.seconds,
            context,
            silent = false
        )
    } catch (e: Exception) {
        errorInsert(
            "DailyDigest:runDailyMusicSummary",
            e.stackTraceToString().take(8000),
            Instant.now().toString(),
            "ERROR"
        )
    }
}

fun checkPermissionsForPrvt(context: Context) {
    if (!prvt()) return
    try {
        val dpm =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(
            context,
            "com.tabslify.core.activities.MyDeviceAdminReceiver"
        )
        if (!dpm.isAdminActive(adminComponent)) {
            val intent =
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        adminComponent
                    )
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        context.getString(R.string.ermoglicht_sicherheitsfunktionen)
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }

        val listeners =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (listeners == null || !listeners.contains(context.packageName)) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        errorInsert(
            "DailyDigest:checkPermissionsForPrvt",
            e.stackTraceToString().take(8000),
            Instant.now().toString(),
            "ERROR"
        )
    }
}

fun postSchoolDaySummary(context: Context) {
    if (!prvt()) return
    val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return

    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(SCHOOL_SUMMARY_CHANNEL_ID) == null) {
        android.app.NotificationChannel(
            SCHOOL_SUMMARY_CHANNEL_ID,
            context.getString(R.string.schultag_zusammenfassung),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            enableLights(true)
        }.also { nm.createNotificationChannel(it) }
    }

    val launchBase = Intent(context, MainActivity::class.java)
    launchBase.putExtra("target", "files")
    launchBase.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    launchBase.setPackage(context.packageName)
    val pi = PendingIntent.getActivity(
        context, SCHOOL_SUMMARY_NOTIF_ID, launchBase,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, SCHOOL_SUMMARY_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_upload)
        .setContentTitle(context.getString(R.string.schultag_beendet))
        .setContentText(context.getString(R.string.materialien_von_heute_in_die))
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.lade_deine_unterlagen_fotos_und))
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .build()

    tNotify(context, SCHOOL_SUMMARY_NOTIF_ID, notification)
}

fun millisUntilNextHour(hour: Int): Long {
    val now = LocalDateTime.now()
    var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    if (!now.isBefore(next)) {
        next = next.plusDays(1)
    }
    return max(
        next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis(),
        MIN_DIGEST_DELAY_MS
    )
}

object DailyDigestScheduler {
    private const val TAG = "daily_digest"

    fun schedule(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    fun scheduleNext(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        try {
            FocusGuardConfig.init(context)
            val req = OneTimeWorkRequestBuilder<DailyDigestWorker>()
                .setInitialDelay(
                    millisUntilNextHour(FocusGuardConfig.dailySummaryHour),
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(TAG, policy, req)
        } catch (e: Exception) {
            errorInsert(
                "DailyDigestScheduler:enqueue",
                e.stackTraceToString().take(8000),
                Instant.now().toString(),
                "ERROR"
            )
        }
    }
}

private const val MIN_DIGEST_DELAY_MS = 60_000L