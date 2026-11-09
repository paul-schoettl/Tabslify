package com.tabslify.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.Config
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

class MixWidgetRefreshWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        try {
            try {
                val response = Config.client.functions.invoke(
                    function = "get_suggestion",
                    body = buildJsonObject { }
                )
                val suggestion = parseSuggestion(response.bodyAsText())
                if (suggestion != null) {
                    MixWidgetStore.saveSuggestion(app, suggestion)
                    Log.i(TAG, "Empfehlung aktualisiert: ${suggestion.title}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Empfehlung nicht erreichbar: ${e.message}")
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Worker abgebrochen: ${e.message}")
            return Result.retry()
        } finally {
            MixWidgetStore.setRefreshing(app, false)
            runCatching { MixWidgetRenderer.push(app) }
        }
    }

    private fun parseSuggestion(body: String): MixWidgetSuggestion? {
        val root = JSONObject(body)
        val value = root.optJSONObject("suggestion") ?: return null
        val videoId = value.optString("video_id").takeIf { it.isNotBlank() } ?: return null
        return MixWidgetSuggestion(
            videoId = videoId,
            title = value.optString("title"),
            channel = value.optString("channel"),
            reason = value.optString("reason"),
            thumbnailUrl = value.optString("thumbnail_url"),
            validUntil = value.optString("valid_until")
        )
    }

    companion object {
        private const val TAG = "MixWidget"
        private const val UNIQUE_NAME = "tabslify_mix_widget_refresh"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<MixWidgetRefreshWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
