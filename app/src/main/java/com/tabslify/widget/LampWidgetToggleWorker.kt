package com.tabslify.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tabslify.core.functions.errorInsert
import java.time.Instant
import kotlinx.coroutines.CancellationException

class LampWidgetToggleWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "Lampen-Worker start")

        return try {
            val ok = HandyLampClient.toggleBestEffort(app, SOURCE)
            LampWidgetPending.clear(app)
            CommandCodeWidgetRenderer.pushCurrent(app)
            Log.i(TAG, "Lampen-Worker fertig in ${SystemClock.elapsedRealtime() - started} ms, LAN=$ok")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lampen-Befehl fehlgeschlagen: ${e.message}")
            errorInsert(
                "LampWidgetToggle",
                "Lampen-Befehl fehlgeschlagen: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            CommandCodeWidgetRenderer.pushCurrent(app)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LampWidgetToggle"
        private const val SOURCE = "widget_lampe"
        private const val UNIQUE_NAME = "lamp_widget_toggle"

        fun enqueue(context: Context) {
            val app = context.applicationContext
            val request = OneTimeWorkRequestBuilder<LampWidgetToggleWorker>().build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}