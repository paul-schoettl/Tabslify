package com.tabslify.tabs.exploretab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ExploreRecheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        ExploreLocationTracker.recheck(applicationContext)
        return Result.success()
    }
}