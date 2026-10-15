package com.tabslify.quiethoursnotificationhelper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tabslify.core.functions.errorInsert
import com.tabslify.core.objects.CloudBackup
import com.tabslify.services.QuietHoursNotificationService
import com.tabslify.services.QuietHoursNotificationService.Companion.workerHandler
import com.tabslify.services.WhatsAppNotificationListener
import com.tabslify.tabs.focusguard.monitoring.FocusGuardSummaryRunner
import java.time.Instant

class DailyDigestWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        try {
            runDailyMusicSummary(appCtx)
            CloudBackup.backupNow(appCtx)
            postSchoolDaySummary(appCtx)
            runCatching { FocusGuardSummaryRunner.run(appCtx) }
        } catch (e: Exception) {
            errorInsert(
                "DailyDigestWorker",
                e.stackTraceToString().take(8000),
                Instant.now().toString(),
                "ERROR"
            )
        }
        DailyDigestScheduler.scheduleNext(appCtx)
        return Result.success()
    }
}

class CleanupWorker(
    context: Context,
    params: WorkerParameters,

    ) : Worker(context, params) {
    private fun cleanupReadMessages() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

        QuietHoursNotificationService.readMessageIds.removeAll { messageId ->
            val timestamp = messageId.substringAfterLast("_").toLongOrNull() ?: 0
            timestamp < cutoffTime
        }
    }

    fun cleanupOldMessages() {
        workerHandler.post {
            WhatsAppNotificationListener.messagesByContact.forEach { (_, msgs) ->
                pruneContactMessages(msgs)
            }
            WhatsAppNotificationListener.messagesByContact.entries.removeIf { it.value.isEmpty() }
        }
    }

    override fun doWork(): Result {
        return try {
            cleanupReadMessages()
            cleanupOldMessages()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class SyncTriggerWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val calendar = java.util.Calendar.getInstance()
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

            if (hour in 21..22) {
                if (!isTriggerPortOpen()) {
                    syncTodosWithLaptop(context)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}