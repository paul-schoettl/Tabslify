package com.tabslify.tabs.tiktok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TikTokDmException(val status: Int, message: String) : Exception(message)

object TikTokDmClient {

    private const val INIT_URL = "https://im-api.tiktok.com/v2/message/get_by_user_init"
    private const val CONV_URL = "https://im-api.tiktok.com/v1/message/get_by_conversation"
    private const val PROFILE_URL = "https://www.tiktok.com/tiktok/v1/im/user/profile/"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0"

    private val _feed = MutableStateFlow(TikTokFeed(emptyList(), emptyList()))
    val feed: StateFlow<TikTokFeed> = _feed.asStateFlow()

    private val _loadingConversation = MutableStateFlow<String?>(null)
    val loadingConversation: StateFlow<String?> = _loadingConversation.asStateFlow()

    private val cursors = mutableMapOf<String, Long>()
    private val hasMoreConv = mutableMapOf<String, Boolean>()
    private var selfId = ""

    suspend fun load(context: Context): TikTokFeed = withContext(Dispatchers.IO) {
        val session = TikTokCookieRepository.session(context)
        val parsed = fetch(session)
        val named = resolveNames(session.cookie, parsed)
        selfId = named.selfId
        cursors.clear()
        hasMoreConv.clear()
        _feed.value = named
        named
    }

    suspend fun openConversation(context: Context, convId: String) = withContext(Dispatchers.IO) {
        _loadingConversation.value = convId
        try {
            val session = TikTokCookieRepository.session(context)
            val hash = convHash(convId)
            if (hash == 0L) return@withContext
            val page = fetchConversationPage(session, convId, hash, System.currentTimeMillis() * 1000L)
            cursors[convId] = page.nextCursor
            hasMoreConv[convId] = page.hasMore
            merge(convId, page, replace = true)
        } finally {
            _loadingConversation.value = null
        }
    }

    suspend fun loadMore(context: Context, convId: String) = withContext(Dispatchers.IO) {
        val cursor = cursors[convId]
        if (cursor == null || cursor <= 0L) return@withContext
        _loadingConversation.value = convId
        try {
            val session = TikTokCookieRepository.session(context)
            val hash = convHash(convId)
            val page = fetchConversationPage(session, convId, hash, cursor)
            cursors[convId] = page.nextCursor
            hasMoreConv[convId] = page.hasMore
            merge(convId, page, replace = false)
        } finally {
            _loadingConversation.value = null
        }
    }

    private fun convHash(convId: String): Long =
        _feed.value.messages.firstOrNull { it.convId == convId }?.convHash ?: 0L

    private fun merge(convId: String, page: TikTokPage, replace: Boolean) {
        val current = _feed.value
        val kept = current.messages.filterNot { it.convId == convId }
        val existing = if (replace) emptyList() else current.messages.filter { it.convId == convId }
        val byId = LinkedHashMap<String, TikTokMessage>()
        for (message in existing + page.messages) {
            val key = if (message.id.isNotEmpty()) message.id else "${message.convId}:${message.timestamp}:${message.text}"
            byId[key] = message
        }
        val merged = (kept + byId.values).sortedBy { it.timestamp }
        _feed.value = current.copy(messages = merged, hasMore = hasMoreConv.filterValues { it }.keys.toSet())
    }

    private fun fetch(session: TikTokSession): TikTokFeed {
        val params = buildParams(session)
        val body = TikTokProto.buildInitRequest(params, session.deviceId)
        return TikTokProto.parse(post(INIT_URL, session.cookie, body))
    }

    private fun fetchConversationPage(
        session: TikTokSession,
        convId: String,
        convHash: Long,
        cursor: Long
    ): TikTokPage {
        val params = buildParams(session)
        val body = TikTokProto.buildConversationRequest(params, session.deviceId, convId, convHash, cursor)
        return TikTokProto.parseConversationPage(post(CONV_URL, session.cookie, body), selfId)
    }

    private fun resolveNames(cookie: String, feed: TikTokFeed): TikTokFeed {
        val peerIds = feed.conversations.map { it.peerId }.filter { it.isNotEmpty() }.distinct()
        if (peerIds.isEmpty()) return feed
        val names = fetchProfiles(cookie, peerIds)
        if (names.isEmpty()) return feed
        return feed.copy(conversations = feed.conversations.map { conversation ->
            val name = names[conversation.peerId]
            if (name.isNullOrEmpty()) conversation else conversation.copy(name = name)
        })
    }

    private fun buildParams(session: TikTokSession): Map<String, String> {
        val cookies = parseCookies(session.cookie)
        val verifyFp = cookies["s_v_web_id"].orEmpty()
        val msToken = cookies["msToken"].orEmpty()
        return linkedMapOf(
            "aid" to "1988",
            "app_name" to "tiktok_web",
            "channel" to "web",
            "device_platform" to "web_pc",
            "device_id" to session.deviceId,
            "region" to "DE",
            "priority_region" to "DE",
            "os" to "linux",
            "referer" to "https://www.tiktok.com/messages",
            "root_referer" to "",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "de",
            "browser_platform" to "Linux x86_64",
            "browser_name" to "Mozilla",
            "browser_version" to "5.0 (X11)",
            "browser_online" to "true",
            "verifyFp" to verifyFp,
            "app_language" to "de-DE",
            "webcast_language" to "de-DE",
            "tz_name" to "Europe/Berlin",
            "is_page_visible" to "true",
            "focus_state" to "true",
            "is_fullscreen" to "false",
            "history_len" to "2",
            "user_is_login" to "true",
            "data_collection_enabled" to "true",
            "from_appID" to "1988",
            "locale" to "de-DE",
            "user_agent" to USER_AGENT,
            "Web-Sdk-Ms-Token" to msToken
        )
    }

    private fun fetchProfiles(cookie: String, uids: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (chunk in uids.chunked(20)) {
            val ids = chunk.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "$PROFILE_URL?aid=1988&user_ids=${URLEncoder.encode(ids, "UTF-8")}"
            val body = get(url, cookie) ?: continue
            val users = runCatching { JSONObject(body).optJSONArray("users") }.getOrNull() ?: continue
            for (index in 0 until users.length()) {
                val profile = users.optJSONObject(index)?.optJSONObject("im_user_profile") ?: continue
                val uid = profile.optString("user_id").ifEmpty { profile.optString("user_id_str") }
                if (uid.isEmpty()) continue
                val name = profile.optString("nick_name").ifEmpty { profile.optString("unique_id") }
                if (name.isNotEmpty()) result[uid] = name
            }
        }
        return result
    }

    private fun parseCookies(cookie: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in cookie.split(';')) {
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            result[trimmed.substring(0, separator)] = trimmed.substring(separator + 1)
        }
        return result
    }

    private fun post(url: String, cookie: String, body: ByteArray): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("Accept", "application/x-protobuf")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Origin", "https://www.tiktok.com")
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cookie", cookie)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw TikTokDmException(code, detail.ifEmpty { "HTTP $code" })
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, cookie: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Origin", "https://www.tiktok.com")
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cookie", cookie)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
