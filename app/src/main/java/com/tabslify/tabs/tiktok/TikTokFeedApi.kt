package com.tabslify.tabs.tiktok

import android.content.Context
import androidx.core.content.edit
import com.tabslify.core.logging.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class TikTokFeedVideo(
    val videoId: String,
    val title: String,
    val author: String,
    val authorUid: String,
    val coverUrl: String,
    val durationMillis: Long,
    val urls: List<String>,
    val diggCount: Long = 0L,
    val commentCount: Long = 0L,
    val playCount: Long = 0L,
    val shareCount: Long = 0L,
    val musicTitle: String = "",
    val liked: Boolean = false
)

object TikTokFeedApi {

    private const val TAG = "TikTokOffline"
    private const val HOST = "https://api16-normal-useastred.tiktokv.eu"
    private const val PFAD_FEED = "/aweme/v1/feed/"
    private const val PFAD_DETAIL = "/aweme/v1/aweme/detail/"
    private const val PFAD_USER = "/aweme/v1/user/post/"
    private const val PREFS = "tiktok_feed"
    private const val VERSION_NAME = "39.8.2"
    private const val VERSION_CODE = "390802"
    private const val AB_VERSION = "39.8.2"
    private const val BUILD_NUMBER = "39.8.2"
    private const val MIN_ABSTAND_MS = 2_000L
    private const val FEED_CACHE_JSON = "feed_cache.json"
    private const val FEED_CACHE_MAX = 60
    private const val USER_AGENT =
        "com.zhiliaoapp.musically/2023508030 (Linux; U; Android 13; de_DE; Pixel 7; " +
            "Build/TD1A.220804.031; Cronet/58.0.2991.0)"
    private const val ANZAHL = 6
    private const val CHANNEL = "googleplay"
    private const val DEVICE_TYPE = "Pixel 7"
    private const val OS_VERSION = "13"

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 45_000
            }
        }
    }

    fun deviceParams(context: Context, count: Int = ANZAHL, cursor: Long = 0L): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: randomNumber().also {
            prefs.edit { putString("device_id", it) }
        }
        val installId = prefs.getString("install_id", null) ?: randomNumber().also {
            prefs.edit { putString("install_id", it) }
        }
        val iid = installId
        val cdid = prefs.getString("cdid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString("cdid", it) }
        }
        val openudid = prefs.getString("openudid", null) ?: randomHex().also {
            prefs.edit { putString("openudid", it) }
        }
        val time = System.currentTimeMillis()
        val fields = linkedMapOf(
            "ab_version" to AB_VERSION,
            "ac" to "wifi",
            "ac2" to "wifi5g",
            "aid" to "1233",
            "app_language" to "de",
            "app_name" to "musical_ly",
            "app_type" to "normal",
            "build_number" to BUILD_NUMBER,
            "carrier_region" to "DE",
            "cdid" to cdid,
            "channel" to "googleplay",
            "current_region" to "DE",
            "device_brand" to "Google",
            "device_id" to deviceId,
            "device_platform" to "android",
            "device_type" to "Pixel 7",
            "dpi" to "420",
            "host_abi" to "arm64-v8a",
            "iid" to iid,
            "install_id" to installId,
            "is_pad" to "0",
            "language" to "de",
            "last_install_time" to (time / 1000L - 86_400L * 30L).toString(),
            "locale" to "de_DE",
            "manifest_version_code" to VERSION_CODE,
            "openudid" to openudid,
            "os" to "android",
            "os_api" to "33",
            "os_version" to "13",
            "region" to "DE",
            "residence" to "DE",
            "resolution" to "1080*2400",
            "ssmix" to "0",
            "timezone_name" to "Europe/Berlin",
            "timezone_offset" to "7200",
            "ts" to (time / 1000L).toString(),
            "uoo" to "0",
            "update_version_code" to VERSION_CODE,
            "version_code" to VERSION_CODE,
            "version_name" to VERSION_NAME,
            "_rticket" to time.toString()
        )
        if (count > 0) {
            fields["count"] = count.toString()
            fields["max_cursor"] = cursor.toString()
            fields["min_cursor"] = cursor.toString()
        }
        return fields.entries.joinToString("&") { (k, v) -> "$k=${urlEnc(v)}" }
    }

    private var lastCall = 0L
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun feed(
        context: Context,
        count: Int = ANZAHL,
        cursor: Long = 0L
    ): List<TikTokFeedVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val params = deviceParams(app, count, cursor)
        val response = fetch(app, "$HOST$PFAD_FEED", params, "")
        parseFeed(response)
    }

    suspend fun loadFeedPage(
        context: Context,
        cursor: Long,
        count: Int = ANZAHL
    ): TikTokFeedPage = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val params = deviceParams(app, count, cursor)
        val response = fetch(app, "$HOST$PFAD_FEED", params, "")
        parsePage(response, cursor)
    }

    private fun parsePage(response: String, cursor: Long): TikTokFeedPage {
        if (response.isEmpty()) {
            return TikTokFeedPage(emptyList(), cursor, false, "Keine Antwort")
        }
        val root = runCatching { JSONObject(response) }.getOrNull()
            ?: return TikTokFeedPage(emptyList(), cursor, false, "Antwort nicht lesbar")
        val status = root.optInt("status_code", -1)
        if (status != 0) {
            val msg = root.optString("status_msg")
            val hint = when (status) {
                9 -> "TikTok isThrottled gerade. Bitte shortForm warten."
                8 -> "Session abgelaufen"
                else -> "status_code=$status"
            }
            Log.w(TAG, "Feed status=$status $msg")
            return TikTokFeedPage(emptyList(), cursor, false, hint)
        }
        val videos = parseFeed(response)
        val next = root.optLong("max_cursor", cursor).coerceAtLeast(cursor)
        val hasMore = root.optInt("has_more", 0) != 0
        return TikTokFeedPage(videos, next, hasMore && videos.isNotEmpty(), null)
    }

    private suspend fun awaitSpacing() {
        lock.withLock {
            val now = System.currentTimeMillis()
            val warten = MIN_ABSTAND_MS - (now - lastCall)
            if (warten > 0L) delay(warten)
            lastCall = System.currentTimeMillis()
        }
    }
    data class TikTokFeedPage(
        val videos: List<TikTokFeedVideo>,
        val nextCursor: Long,
        val hasMore: Boolean,
        val error: String?
    )

    suspend fun detail(context: Context, videoId: String): List<TikTokFeedVideo> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val params = deviceParams(app, 0)
            val body = "aweme_ids=%5B%22$videoId%22%5D&request_source=0"
            val response = fetch(app, "$HOST$PFAD_DETAIL", params, body)
            parseFeed(response)
        }

    suspend fun userPosts(
        context: Context,
        secUid: String,
        count: Int = ANZAHL,
        cursor: Long = 0L
    ): List<TikTokFeedVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val params = deviceParams(app, count, cursor) + "&secUid=$secUid&post_item_list_request_type=0"
        val response = fetch(app, "$HOST$PFAD_USER", params, "")
        parseFeed(response)
    }

    private suspend fun fetch(
        context: Context,
        url: String,
        params: String,
        body: String
    ): String {
        awaitSpacing()
        val cookie = sessionCookie(context)
        val unix = System.currentTimeMillis() / 1000L
        val header = TikTokSignatur.signiere(
            params = params,
            payload = body,
            cookie = cookie,
            unix = unix,
            deviceId = value(params, "device_id"),
            versionName = VERSION_NAME,
            deviceType = DEVICE_TYPE,
            osVersion = OS_VERSION,
            channel = CHANNEL
        )
        val body = if (body.isEmpty()) ByteArray(0) else gzipBody(body)
        val response = try {
            client.post("$url?$params") {
                header("User-Agent", USER_AGENT)
                header("Cookie", cookie)
                for ((name, value) in header) header(name, value)
                contentType(ContentType.Application.FormUrlEncoded)
                if (body.isNotEmpty()) header("Content-Encoding", "gzip")
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Feed-Abfrage fehlgeschlagen: ${e.message}")
            return ""
        }
        val code = response.status.value
        val raw = response.bodyAsBytes()
        val text = unpackIfGzip(raw)
        if (code !in 200..299) {
            Log.w(TAG, "Feed HTTP $code: ${text.take(200)}")
            return ""
        }
        Log.i(TAG, "Feed-Antwort ${text.length} Zeichen")
        return text
    }

    private fun unpackIfGzip(bytes: ByteArray): String {
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return runCatching {
                java.util.zip.GZIPInputStream(bytes.inputStream())
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "gzip nicht entpackbar: ${it.message}")
                ""
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseFeed(response: String): List<TikTokFeedVideo> {
        if (response.isEmpty()) return emptyList()
        return runCatching {
            val root = JSONObject(response)
            val status = root.optInt("status_code", -1)
            if (status != 0) {
                Log.w(TAG, "Feed status_code=$status ${root.optString("status_msg")}")
                return emptyList()
            }
            val list = root.optJSONArray("aweme_list") ?: return emptyList()
            val raus = mutableListOf<TikTokFeedVideo>()
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val video = entry.optJSONObject("video") ?: continue
                val id = entry.optString("aweme_id")
                if (id.isEmpty()) continue
                val author = entry.optJSONObject("author")
                val varianten = mutableListOf<String>()
                val bitraten = video.optJSONArray("bit_rate")
                if (bitraten != null) {
                    val sortiert = (0 until bitraten.length())
                        .mapNotNull { bitraten.optJSONObject(it) }
                        .sortedByDescending { it.optInt("bit_rate", 0) }
                    for (eintragBitrate in sortiert) {
                        val address = eintragBitrate.optJSONObject("play_addr")
                            ?.optJSONArray("url_list")
                        val value = address?.optString(0).orEmpty()
                        if (value.isNotEmpty()) varianten.add(value)
                    }
                }
                val play = video.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0).orEmpty()
                if (play.isNotEmpty() && varianten.none { it == play }) varianten.add(play)
                val cover = video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
                val stats = entry.optJSONObject("statistics")
                val music = entry.optJSONObject("music")
                raus.add(
                    TikTokFeedVideo(
                        videoId = id,
                        title = entry.optString("desc").ifEmpty { "Video $id" },
                        author = author?.optString("nickname").orEmpty(),
                        authorUid = author?.optString("uid").orEmpty(),
                        coverUrl = cover,
                        durationMillis = video.optLong("duration", 0L),
                        urls = varianten.distinct(),
                        diggCount = stats?.optLong("digg_count", 0L) ?: 0L,
                        commentCount = stats?.optLong("comment_count", 0L) ?: 0L,
                        playCount = stats?.optLong("play_count", 0L) ?: 0L,
                        shareCount = stats?.optLong("share_count", 0L) ?: 0L,
                        musicTitle = music?.optString("title").orEmpty()
                    )
                )
            }
            raus
        }.getOrElse {
            Log.w(TAG, "Feed nicht lesbar: ${it.message}")
            emptyList()
        }
    }

    private fun gzipBody(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(9, true)
        deflater.setInput(raw)
        deflater.finish()
        val aus = java.io.ByteArrayOutputStream()
        aus.write(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0x02, 0xFF.toByte()))
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            aus.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        val crc = java.util.zip.CRC32()
        crc.update(raw)
        val vier = ByteArray(4)
        for (i in 0 until 4) vier[i] = ((crc.value ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        for (i in 0 until 4) vier[i] = ((raw.size.toLong() ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        return aus.toByteArray()
    }

    private fun sessionCookie(context: Context): String =
        TikTokCookieRepository.cachedCookie(context)

    private fun value(params: String, name: String): String {
        for (part in params.split("&")) {
            val trenner = part.indexOf('=')
            if (trenner > 0 && part.substring(0, trenner) == name) {
                return part.substring(trenner + 1)
            }
        }
        return ""
    }

    private fun urlEnc(text: String): String {
        val out = StringBuilder(text.length)
        for (character in text) {
            val code = character.code
            val erlaubt = (code in 48..57) || (code in 65..90) || (code in 97..122) ||
                character == '-' || character == '_' || character == '.' || character == '~'
            if (erlaubt) {
                out.append(character)
            } else {
                val bytes = character.toString().toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    out.append('%')
                    out.append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
        return out.toString()
    }

    private fun randomNumber(): String {
        val digits = StringBuilder(19)
        for (i in 0 until 19) {
            digits.append((Math.random() * 10).toInt())
        }
        return digits.toString()
    }

    private fun randomHex(): String {
        val out = StringBuilder(16)
        for (i in 0 until 16) {
            out.append("%02x".format((Math.random() * 256).toInt()))
        }
        return out.toString()
    }
}
