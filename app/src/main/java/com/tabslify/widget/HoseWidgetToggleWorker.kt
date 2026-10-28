package com.tabslify.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tabslify.core.functions.errorInsert
import java.time.Instant
import kotlinx.coroutines.CancellationException

class HoseWidgetToggleWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            val result = HandyLampClient.toggleHosesBestEffort(app, SOURCE)
            HoseWidgetPending.clear(app)
            CommandCodeWidgetRenderer.pushCurrent(app)
            Log.i(TAG, "Lichtschlauch-Worker: $result")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HoseWidgetPending.clear(app)
            CommandCodeWidgetRenderer.pushCurrent(app)
            errorInsert(
                "HoseWidgetToggle",
                "Lichtschlauch-Befehl fehlgeschlagen: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HoseWidgetToggle"
        private const val SOURCE = "widget_lichtschlaeuche"
        private const val UNIQUE_NAME = "hose_widget_toggle"

        fun enqueue(context: Context) {
            val app = context.applicationContext
            val request = OneTimeWorkRequestBuilder<HoseWidgetToggleWorker>().build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
