package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TikTokFollowClient {

    private const val TAG = "TikTokFollow"
    private const val FOLLOW_URL =
        "https://api16-normal-useastred.tiktokv.eu/aweme/v1/commit/follow/user/"

    suspend fun setFollow(context: Context, userId: String, follow: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (userId.isEmpty()) throw TikTokDmException(400, "Kein Nutzer")
            val session = TikTokCookieRepository.session(context)
            val fields = appParams(session.deviceId).toMutableMap()
            fields["user_id"] = userId
            fields["type"] = if (follow) "1" else "0"
            val query = fields.entries
                .sortedBy { it.key }
                .joinToString("&") { (key, value) -> "$key=${urlEnc(value)}" }
            val body = "user_id=$userId&type=${if (follow) "1" else "0"}"
            val unix = System.currentTimeMillis() / 1000L
            val header = TikTokSignatur.signiere(
                params = query,
                payload = body,
                cookie = session.cookie,
                unix = unix,
                deviceId = session.deviceId,
                versionName = "39.8.2",
                deviceType = "Pixel 7",
                osVersion = "13",
                channel = "googleplay"
            )
            var verbindung: HttpURLConnection? = null
            try {
                verbindung = (URL("$FOLLOW_URL?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Cookie", session.cookie)
                    setRequestProperty("User-Agent", TikTokOfflineDownloader.APP_USER_AGENT)
                    setRequestProperty("Referer", TikTokOfflineDownloader.REFERER)
                    for ((key, value) in header) setRequestProperty(key, value)
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = verbindung.responseCode
                val text = (if (code in 200..299) verbindung.inputStream else verbindung.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) {
                    Log.w(TAG, "Follow HTTP $code: ${text.take(160)}")
                    throw TikTokDmException(code, "Folgen fehlgeschlagen (HTTP $code)")
                }
                val json = runCatching { org.json.JSONObject(text) }.getOrNull()
                val status = json?.optInt("status_code", -1) ?: -1
                if (status != 0) {
                    val msg = json?.optString("status_msg").orEmpty()
                    Log.w(TAG, "Follow abgelehnt: $status $msg")
                    throw TikTokDmException(status, "Folgen fehlgeschlagen${if (msg.isEmpty()) "" else ": $msg"}")
                }
                val result = json?.optInt("follow_status", -1) ?: -1
                follow
            } finally {
                runCatching { verbindung?.disconnect() }
            }
        }

    private fun appParams(deviceId: String): Map<String, String> {
        val z = System.currentTimeMillis()
        val hex = deviceId.toLongOrNull()?.let { String.format("%016x", it) } ?: deviceId
        return linkedMapOf(
            "ab_version" to "39.8.2",
            "ac" to "wifi",
            "ac2" to "wifi5g",
            "aid" to "1233",
            "app_language" to "de",
            "app_name" to "musical_ly",
            "app_type" to "normal",
            "build_number" to "39.8.2",
            "carrier_region" to "DE",
            "cdid" to "cdid-$deviceId",
            "channel" to "googleplay",
            "current_region" to "DE",
            "device_brand" to "Google",
            "device_id" to deviceId,
            "device_platform" to "android",
            "device_type" to "Pixel 7",
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
            "os_version" to "13",
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
            "version_name" to "39.8.2",
            "_rticket" to z.toString()
        )
    }

    private fun urlEnc(text: String): String = URLEncoder.encode(text, "UTF-8")
}
