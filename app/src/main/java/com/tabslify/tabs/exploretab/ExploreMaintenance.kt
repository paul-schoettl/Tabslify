package com.tabslify.tabs.exploretab

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.tabslify.R
import com.tabslify.core.functions.errorInsert
import com.tabslify.core.objects.tNotify
import java.time.Instant
import java.time.LocalDate

const val EXPLORE_STALE_NOTIFICATION_ID = 80001
const val EXPLORE_TRACKING_CHANNEL_ID = "explore_tracking_channel"
const val EXPLORE_STALE_AFTER_MS = 20 * 60_000L
const val EXPLORE_STALE_WARN_GAP_MS = 6 * 60 * 60_000L
const val RAW_POINT_RETENTION_DAYS = 30L

suspend fun runExploreMaintenance(context: Context, repo: ExploreRepository) {
    ExploreConfig.init(context)
    val yesterday = LocalDate.now().minusDays(1)
    if (!ExploreConfig.needsRebuild(yesterday)) return

    ExploreSegmentBuilder.rebuildDay(context, yesterday)
    val cutoff = System.currentTimeMillis() - RAW_POINT_RETENTION_DAYS * 24 * 60 * 60 * 1000
    repo.deleteRawPointsBefore(cutoff)
    repo.deleteTrackerEventsBefore(cutoff)
    ExploreConfig.markRebuilt(yesterday)
}

fun notifyTrackingStalled(context: Context, staleMs: Long) {
    val appCtx = context.applicationContext
    val now = System.currentTimeMillis()
    if (!ExploreConfig.shouldWarnStale(now, EXPLORE_STALE_WARN_GAP_MS)) return
    ExploreConfig.markStaleWarned(now)

    val nm = appCtx.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(EXPLORE_TRACKING_CHANNEL_ID) == null) {
        nm.createNotificationChannel(
            NotificationChannel(
                EXPLORE_TRACKING_CHANNEL_ID,
                appCtx.getString(R.string.explore_tracking_kanal),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    val minutes = staleMs / 60_000
    val notification = NotificationCompat.Builder(appCtx, EXPLORE_TRACKING_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(appCtx.getString(R.string.explore_watchdog_still))
        .setContentText(
            appCtx.getString(R.string.explore_watchdog_still_text, minutes.toString())
        )
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    tNotify(appCtx, EXPLORE_STALE_NOTIFICATION_ID, notification)
}

fun reportExploreMaintenanceError(where: String, e: Throwable) {
    errorInsert(
        "ExploreMaintenance:$where",
        e.stackTraceToString().take(8000),
        Instant.now().toString(),
        "ERROR"
    )
}

suspend fun logExploreStallEvent(repo: ExploreRepository, staleMs: Long) {
    runCatching {
        repo.logTrackerEvent(
            TrackerEvent(
                timestamp = System.currentTimeMillis(),
                type = "TRACKER_STALLED",
                mode = "UNKNOWN",
                confidence = 0,
                detail = "kein Punkt seit ${staleMs / 60_000} min"
            )
        )
    }
}