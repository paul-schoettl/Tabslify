package com.tabslify.tabs.exploretab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ExploreNightRestartWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        val repo = ExploreRepository(appCtx)

        runCatching { runExploreMaintenance(appCtx, repo) }
            .onFailure { reportExploreMaintenanceError("nightRestart", it) }

        ExploreLocationTracker.start(appCtx)
        schedule(appCtx)
        return Result.success()
    }

    companion object {
        private const val TAG = "explore_night_restart"
        private const val RESTART_HOUR = 5
        private const val RESTART_MINUTE = 0
        private const val MIN_DELAY_MS = 60_000L

        fun schedule(context: Context) {
            val appCtx = context.applicationContext
            val now = LocalDateTime.now()
            var nextRestart = now.withHour(RESTART_HOUR).withMinute(RESTART_MINUTE).withSecond(0).withNano(0)

            if (!now.isBefore(nextRestart)) {
                nextRestart = nextRestart.plusDays(1)
            }

            val delay = kotlin.math.max(
                nextRestart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis(),
                MIN_DELAY_MS
            )

            val req = OneTimeWorkRequestBuilder<ExploreNightRestartWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(TAG, androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(TAG)
        }
    }
}