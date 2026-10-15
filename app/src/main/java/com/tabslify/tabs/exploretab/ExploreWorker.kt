package com.tabslify.tabs.exploretab

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ExploreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        ExploreConfig.init(appCtx)

        if (!ExploreLocationTracker.trackingActive) {
            return Result.success()
        }

        val repo = ExploreRepository(appCtx)

        runCatching { runExploreMaintenance(appCtx, repo) }
            .onFailure { reportExploreMaintenanceError("watchdogMaintenance", it) }

        val latest = runCatching { repo.latestPointTimestamp() }.getOrNull()
        if (latest == null) {
            logExploreStallEvent(repo, EXPLORE_STALE_AFTER_MS)
            return Result.success()
        }
        val staleMs = System.currentTimeMillis() - latest
        if (staleMs < EXPLORE_STALE_AFTER_MS) {
            return Result.success()
        }

        logExploreStallEvent(repo, staleMs)
        notifyTrackingStalled(appCtx, staleMs)
        return Result.success()
    }

    companion object {
        private const val TAG = "explore_periodic"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ExploreWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}