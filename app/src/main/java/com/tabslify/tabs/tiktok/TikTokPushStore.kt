package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import androidx.core.content.edit
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import com.tabslify.core.objects.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object TikTokPushStore {

    private const val TAG = "TikTokTab"
    private const val PREFS = "tiktok_push"
    private const val ORIGIN = "origin"
    private const val REPORTED = "reported"
    private const val REPORTED_LIMIT = 300
    private const val REMEMBERED_KEYS = "remembered_keys"
    private const val REMEMBERED_NAME = "remembered_name_"
    private const val REMEMBERED_TEXT = "remembered_text_"
    private const val REMEMBERED_LIMIT = 100

    fun origin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(ORIGIN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit { putString(ORIGIN, value) }
        return value
    }

    fun isOwnReport(context: Context, reportedOrigin: String): Boolean {
        if (reportedOrigin.isEmpty()) return false
        return reportedOrigin == origin(context)
    }

    fun conversation(context: Context, convId: String): TikTokConversation? =
        runCatching { TikTokDmClient.currentConversation(convId) }.getOrNull()

    fun remember(context: Context, convId: String, name: String, text: String) {        if (convId.isEmpty()) return
        val cleanText = text.trim()
        if (name.isBlank() && cleanText.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.getStringSet(REMEMBERED_KEYS, emptySet()).orEmpty().toMutableSet()
        if (keys.size >= REMEMBERED_LIMIT && convId !in keys) {
            val oldest = keys.first()
            keys.remove(oldest)
            prefs.edit {
                remove("$REMEMBERED_NAME$oldest")
                remove("$REMEMBERED_TEXT$oldest")
            }
        }
        keys.add(convId)
        prefs.edit {
            putStringSet(REMEMBERED_KEYS, keys)
            if (name.isNotBlank()) putString("$REMEMBERED_NAME$convId", name)
            if (cleanText.isNotBlank()) putString("$REMEMBERED_TEXT$convId", cleanText.take(300))
        }
    }

    fun rememberedName(context: Context, convId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$REMEMBERED_NAME$convId", null)
            .orEmpty()

    fun rememberedText(context: Context, convId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$REMEMBERED_TEXT$convId", null)
            .orEmpty()

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
        remember(context, convId, name, text)
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

    suspend fun reportName(context: Context, convId: String, peerId: String, name: String) =
        withContext(Dispatchers.IO) {
            if (convId.isEmpty() || name.isBlank()) return@withContext
            if (rememberedName(context, convId) == name) return@withContext
            remember(context, convId, name, "")
            val payload = buildJsonObject {
                put("conv_id", convId)
                put("peer_id", peerId)
                put("name", name)
                put("text", "")
                put("origin", origin(context))
            }
            runCatching {
                Config.safeCall {
                    Config.client.from(TIKTOK_PUSH_TABLE).insert(payload)
                }
            }.onFailure {
                Log.w(TAG, "TikTok-Name nicht meldbar: ${it.message}")
            }
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