package com.tabslify.tabs.aitab

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class StoredChat(
    val id: String,
    val title: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val mode: String = "Nvidia",
    val model: String = "",
    val messages: List<ChatMessage> = emptyList()
)

data class LoadedChats(
    val chats: List<StoredChat>,
    val activeChatId: String?
)

object ChatStore {
    private const val PREFS = "ai_prefs"
    private const val KEY_CHATS = "ai_chats"
    private const val KEY_ACTIVE = "ai_active_chat"
    private const val KEY_LEGACY = "ai_history"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): LoadedChats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CHATS, null)
        if (!raw.isNullOrBlank()) {
            val decoded = runCatching { json.decodeFromString<List<StoredChat>>(raw) }.getOrNull()
            if (decoded != null) {
                val chats = decoded.map { it.sanitized() }.filter { it.id.isNotBlank() }
                if (chats.isNotEmpty()) {
                    val active = prefs.getString(KEY_ACTIVE, null)
                        ?.takeIf { id -> chats.any { it.id == id } }
                    return LoadedChats(chats, active)
                }
            }
        }
        val migrated = migrateLegacy(context)
        if (migrated != null) {
            save(context, listOf(migrated))
            saveActive(context, migrated.id)
            return LoadedChats(listOf(migrated), migrated.id)
        }
        return LoadedChats(emptyList(), null)
    }

    fun save(context: Context, chats: List<StoredChat>) {
        val encoded = json.encodeToString(chats)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CHATS, encoded)
        }
    }

    fun saveActive(context: Context, activeChatId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (activeChatId.isNullOrBlank()) remove(KEY_ACTIVE) else putString(
                KEY_ACTIVE,
                activeChatId
            )
        }
    }

    private fun migrateLegacy(context: Context): StoredChat? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LEGACY, null)?.takeIf { it.isNotBlank() } ?: return null
        val messages = runCatching { json.decodeFromString<List<ChatMessage>>(raw) }.getOrNull()
            ?: return null
        val filtered = messages
            .map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
            .filterNot { !it.own && it.text.isBlank() }
        if (filtered.isEmpty()) return null
        val firstUser = filtered.firstOrNull { it.own }?.text.orEmpty()
        val lastMode = filtered.lastOrNull { !it.own }?.mode ?: "Nvidia"
        return StoredChat(
            id = UUID.randomUUID().toString(),
            title = ChatTitles.from(firstUser),
            createdAt = filtered.first().ts,
            updatedAt = filtered.last().ts,
            mode = lastMode,
            model = "",
            messages = filtered
        )
    }

    private fun StoredChat.sanitized(): StoredChat = copy(
        messages = messages
            .map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
            .filterNot { !it.own && it.text.isBlank() }
    )
}

object ChatTitles {
    fun from(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        if (clean.length <= 48) return clean
        val cut = clean.take(48)
        val space = cut.lastIndexOf(' ')
        val body = if (space >= 24) cut.take(space) else cut
        return body.trimEnd() + "…"
    }
}
