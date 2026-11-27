package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class TikTokComment(
    val id: String,
    val user: String,
    val handle: String,
    val text: String,
    val likes: Long,
    val timestamp: Long,
    val avatarUrl: String = "",
    val replyCount: Int = 0
)

object TikTokCommentClient {

    private const val TAG = "TikTokComments"
    private const val HOST = "https://api16-normal-useastred.tiktokv.eu"
    private const val PFAD = "/aweme/v2/comment/list/"
    private const val APP_VERSION_NAME = "39.8.2"
    private const val APP_DEVICE_TYPE = "Pixel 7"
    private const val APP_OS_VERSION = "13"
    private const val APP_CHANNEL = "googleplay"
    private const val APP_USER_AGENT =
        "com.zhiliaoapp.musically/2024704030 (Linux; U; Android 13; de_DE; Pixel 7; " +
            "Build/TQ3A.220804.031; Cronet/58.0.2991.0)"

    suspend fun comments(
        context: Context,
        awemeId: String,
        cursor: Long = 0L,
        count: Int = 20
    ): List<TikTokComment> = withContext(Dispatchers.IO) {
        if (awemeId.isEmpty()) return@withContext emptyList()
        val session = runCatching { TikTokCookieRepository.session(context) }.getOrNull()
            ?: return@withContext emptyList()
        val fields = appParams(session.deviceId).toMutableMap()
        fields["aweme_id"] = awemeId
        fields["count"] = count.toString()
        fields["cursor"] = cursor.toString()
        fields["aid"] = "1233"
        fields["app_name"] = "musical_ly"
        fields["device_platform"] = "android"
        val query = fields.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val unix = System.currentTimeMillis() / 1000L
        val header = runCatching {
            TikTokSignatur.signiere(
                params = query,
                payload = "",
                cookie = session.cookie,
                unix = unix,
                deviceId = session.deviceId,
                versionName = APP_VERSION_NAME,
                deviceType = APP_DEVICE_TYPE,
                osVersion = APP_OS_VERSION,
                channel = APP_CHANNEL
            )
        }.getOrNull() ?: return@withContext emptyList()
        var verbindung: HttpURLConnection? = null
        try {
            verbindung = (URL("$HOST$PFAD?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 25_000
                setRequestProperty("Cookie", session.cookie)
                setRequestProperty("User-Agent", APP_USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                for ((key, value) in header) setRequestProperty(key, value)
            }
            val code = verbindung.responseCode
            val raw = (if (code in 200..299) verbindung.inputStream else verbindung.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            val text = unpack(raw)
            if (code !in 200..299 || text.isEmpty()) {
                Log.w(TAG, "Kommentare HTTP $code len=${text.length}")
                return@withContext emptyList()
            }
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: return@withContext emptyList()
            val status = root.optInt("status_code", -1)
            if (status != 0) {
                Log.w(TAG, "Kommentare status=$status ${root.optString("status_msg")}")
                return@withContext emptyList()
            }
            val list = root.optJSONArray("comments") ?: return@withContext emptyList()
            val raus = mutableListOf<TikTokComment>()
            for (index in 0 until list.length()) {
                val entry = list.optJSONObject(index) ?: continue
                val user = entry.optJSONObject("user")
                val content = entry.optString("text")
                if (content.isEmpty()) continue
                raus.add(
                    TikTokComment(
                        id = entry.optString("cid"),
                        user = user?.optString("nickname").orEmpty(),
                        handle = user?.optString("unique_id").orEmpty(),
                        text = content,
                        likes = entry.optLong("digg_count", 0L),
                        timestamp = entry.optLong("create_time", 0L) * 1000L,
                        avatarUrl = user?.optJSONObject("avatar_thumb")
                            ?.optJSONArray("url_list")
                            ?.optString(0)
                            .orEmpty(),
                        replyCount = entry.optInt("reply_comment_total", 0)
                    )
                )
            }
            Log.i(TAG, "Kommentare geladen: ${raus.size}")
            raus
        } catch (error: Exception) {
            Log.w(TAG, "Kommentare fehlgeschlagen: ${error.message}")
            emptyList()
        } finally {
            runCatching { verbindung?.disconnect() }
        }
    }

    private fun unpack(bytes: ByteArray): String {
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return runCatching {
                GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrDefault("")
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun appParams(deviceId: String): Map<String, String> {
        val z = System.currentTimeMillis()
        val hex = deviceId.toLongOrNull()?.let { String.format("%016x", it) } ?: deviceId
        return linkedMapOf(
            "ab_version" to APP_VERSION_NAME,
            "ac" to "wifi",
            "ac2" to "wifi5g",
            "app_language" to "de",
            "app_type" to "normal",
            "build_number" to APP_VERSION_NAME,
            "carrier_region" to "DE",
            "cdid" to "cdid-$deviceId",
            "channel" to APP_CHANNEL,
            "current_region" to "DE",
            "device_brand" to "Google",
            "device_id" to deviceId,
            "device_type" to APP_DEVICE_TYPE,
            "dpi" to "420",
            "host_abi" to "arm64-v8a",
            "iid" to deviceId,
            "install_id" to deviceId,
            "is_pad" to "0",
            "language" to "de",
            "last_install_time" to ((z / 1000L) - 2_592_000L).toString(),
            "locale" to "de_DE",
            "manifest_version_code" to "390802",
            "openudid" to hex,
            "os" to "android",
            "os_api" to "33",
            "os_version" to APP_OS_VERSION,
            "region" to "DE",
            "residence" to "DE",
            "resolution" to "1080*2400",
            "ssmix" to "0",
            "timezone_name" to "Europe/Berlin",
            "timezone_offset" to "7200",
            "ts" to (z / 1000L).toString(),
            "uoo" to "0",
            "update_version_code" to "390802",
            "version_code" to "390802",
            "version_name" to APP_VERSION_NAME,
            "_rticket" to z.toString()
        )
    }
}
