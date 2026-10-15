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
import com.tabslify.core.functions.isNetworkFailure
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CommandCodeWidgetRefreshWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "Refresh-Worker start")

        var state = CommandCodeWidgetRenderer.PaceState.DATA
        try {
            try {
                HandyLampClient.refreshBestEffort(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lampen-Abgleich fehlgeschlagen: ${e.message}")
            }

            try {
                val usage = CommandCodeUsageRepository.load(app)
                WidgetSnapshotStore.save(app, usage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CookieExpiredException) {
                state = CommandCodeWidgetRenderer.PaceState.EXPIRED
                Log.w(TAG, "Cookie abgelaufen")
            } catch (e: CommandCodeBlockedException) {
                state = CommandCodeWidgetRenderer.PaceState.BLOCKED
                Log.w(TAG, "Antwort vom Server abgewiesen")
            } catch (e: Exception) {
                val keepCache = WidgetSnapshotStore.read(app) != null && isNetworkFailure(e)
                if (keepCache) {
                    state = CommandCodeWidgetRenderer.PaceState.DATA
                    Log.w(TAG, "Netzfehler, Cache bleibt stehen: ${e.message}")
                } else {
                    state = CommandCodeWidgetRenderer.PaceState.ERROR
                    Log.e(TAG, "Abruf fehlgeschlagen: ${e.message}")
                    errorInsert(
                        "CommandCodeWidget",
                        "CommandCode-Abruf fehlgeschlagen: ${e.message}",
                        Instant.now().toString(),
                        "ERROR"
                    )
                }
            }

            CommandCodeWidgetRenderer.push(
                app,
                CommandCodeWidgetRenderer.renderWithFooter(app, state)
            )
            val cached = WidgetSnapshotStore.read(app) != null
            CommandCodeWidgetRenderer.logRender(app, state, cached)
            Log.i(TAG, "Refresh-Worker fertig in ${SystemClock.elapsedRealtime() - started} ms")
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorInsert(
                "CommandCodeWidget",
                "Refresh-Worker abgebrochen: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "CommandCodeWidget"
        private const val UNIQUE_NAME = "commandcode_widget_refresh"

        fun enqueue(context: Context, delayMs: Long = 0L) {
            val app = context.applicationContext
            val request = OneTimeWorkRequestBuilder<CommandCodeWidgetRefreshWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            val uniqueName = if (delayMs <= 0L) UNIQUE_NAME else "$UNIQUE_NAME.$delayMs"
            WorkManager.getInstance(app)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueueUnique(context: Context, delayMs: Long = 0L) {
            val app = context.applicationContext
            val request = OneTimeWorkRequestBuilder<CommandCodeWidgetRefreshWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}