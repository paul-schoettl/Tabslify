package com.tabslify.tabs.tiktok

import android.content.Context
import android.provider.Settings
import com.tabslify.core.logging.Log
import androidx.core.content.edit
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import com.tabslify.core.objects.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TikTokPushStore {

    private const val TAG = "TikTokTab"
    private const val PREFS = "tiktok_push"
    private const val ORIGIN = "origin"
    private const val REPORTED = "reported"
    private const val REPORTED_LIMIT = 300

    fun origin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(ORIGIN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val value = raw.ifEmpty { "unbekannt" }
        prefs.edit { putString(ORIGIN, value) }
        return value
    }

    fun isOwnReport(context: Context, reportedOrigin: String): Boolean {
        if (reportedOrigin.isEmpty()) return false
        return reportedOrigin == origin(context)
    }

    fun conversation(context: Context, convId: String): TikTokConversation? =
        runCatching { TikTokDmClient.currentConversation(convId) }.getOrNull()

    suspend fun report(
        context: Context,
        convId: String,
        peerId: String,
        name: String,
        text: String
    ) = withContext(Dispatchers.IO) {
        val marker = "$convId|${text.take(40)}"
        if (isReported(context, marker)) return@withContext
        markReported(context, marker)
        val payload = buildJsonObject {
            put("conv_id", convId)
            put("peer_id", peerId)
            put("name", name)
            put("text", text.take(120))
            put("origin", origin(context))
        }
        runCatching {
            Config.safeCall {
                Config.client.from(TIKTOK_PUSH_TABLE).insert(payload)
            }
            Config.safeCall {
                Config.client.functions.invoke(
                    function = TIKTOK_PUSH_FUNCTION,
                    body = buildJsonObject {}
                )
            }
        }.onFailure {
            Log.w(TAG, "TikTok-Push konnte nicht gemeldet werden: ${it.message}")
        }
        Unit
    }

    private fun isReported(context: Context, marker: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(REPORTED, emptySet()).orEmpty().contains(marker)
    }

    private fun markReported(context: Context, marker: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reported = prefs.getStringSet(REPORTED, emptySet()).orEmpty().toMutableSet()
        if (reported.size >= REPORTED_LIMIT) reported.clear()
        reported.add(marker)
        prefs.edit { putStringSet(REPORTED, reported) }
    }
}