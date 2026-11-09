package com.tabslify.core.logging

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.Config.client
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.util.concurrent.TimeUnit

class AppLogUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AppLogUpload"
        private const val TABLE = "app_logs"
        private const val PERIODIC_WORK = "app_log_upload_periodic"
        private const val NOW_WORK = "app_log_upload_now"
        private const val MAX_BATCH = 100
        private const val MAX_BATCH_BYTES = 512 * 1024

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppLogUploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AppLogUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Config.realDevice) return@withContext Result.success()
        val store = AppLog.store ?: return@withContext Result.success()
        var uploaded = 0
        try {
            for (file in store.pendingFiles()) {
                var lines = store.readLines(file)
                while (lines.isNotEmpty()) {
                    val batch = takeBatch(lines)
                    if (batch.isEmpty()) {
                        store.delete(file)
                        lines = emptyList()
                        break
                    }
                    upload(batch)
                    uploaded += batch.size
                    lines = lines.drop(batch.size)
                    if (lines.isEmpty()) store.delete(file) else store.rewrite(file, lines)
                }
            }
            if (uploaded > 0) AppLog.success("AppLogUpload", "$uploaded Zeilen hochgeladen")
            Result.success()
        } catch (e: Exception) {
            AppLog.w(TAG, "Upload fehlgeschlagen: ${e.message}", e)
            Result.retry()
        }
    }

    private fun takeBatch(lines: List<String>): List<String> {
        val batch = ArrayList<String>(MAX_BATCH)
        var bytes = 0
        for (line in lines) {
            if (batch.size >= MAX_BATCH) break
            if (batch.isNotEmpty() && bytes + line.length > MAX_BATCH_BYTES) break
            batch.add(line)
            bytes += line.length
        }
        return batch
    }

    private suspend fun upload(lines: List<String>) {
        val payload = buildJsonArray {
            for (line in lines) {
                val element = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: continue
                add(element)
            }
        }
        if (payload.isEmpty()) return
        Config.safeCall {
            client.from(TABLE).upsert(payload) {
                onConflict = "client_id"
                ignoreDuplicates = true
            }
        }
    }
}
