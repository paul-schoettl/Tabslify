package com.tabslify.tabs.tiktok

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class TikTokFeedVideo(
    val videoId: String,
    val titel: String,
    val autor: String,
    val coverUrl: String,
    val dauerMillis: Long,
    val urls: List<String>
)

object TikTokFeedApi {

    private const val TAG = "TikTokOffline"
    private const val HOST = "https://api16-normal-c-useast1a.tiktokv.com"
    private const val PFAD_FEED = "/aweme/v1/feed/"
    private const val PFAD_DETAIL = "/aweme/v1/aweme/detail/"
    private const val PFAD_USER = "/aweme/v1/user/post/"
    private const val PREFS = "tiktok_feed"
    private const val VERSION_NAME = "39.8.2"
    private const val VERSION_CODE = "390802"
    private const val AB_VERSION = "39.8.2"
    private const val BUILD_NUMBER = "39.8.2"
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

    fun deviceParams(context: Context, anzahl: Int = ANZAHL, cursor: Long = 0L): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: zufallsNummer().also {
            prefs.edit().putString("device_id", it).apply()
        }
        val installId = prefs.getString("install_id", null) ?: zufallsNummer().also {
            prefs.edit().putString("install_id", it).apply()
        }
        val iid = installId
        val cdid = prefs.getString("cdid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("cdid", it).apply()
        }
        val openudid = prefs.getString("openudid", null) ?: zufallsHex().also {
            prefs.edit().putString("openudid", it).apply()
        }
        val zeit = System.currentTimeMillis()
        val felder = linkedMapOf(
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
            "last_install_time" to (zeit / 1000L - 86_400L * 30L).toString(),
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
            "ts" to (zeit / 1000L).toString(),
            "uoo" to "0",
            "update_version_code" to VERSION_CODE,
            "version_code" to VERSION_CODE,
            "version_name" to VERSION_NAME,
            "_rticket" to zeit.toString()
        )
        if (anzahl > 0) {
            felder["count"] = anzahl.toString()
            felder["max_cursor"] = cursor.toString()
            felder["min_cursor"] = cursor.toString()
        }
        return felder.entries.joinToString("&") { (k, v) -> "$k=${urlEnc(v)}" }
    }

    suspend fun feed(context: Context, anzahl: Int = ANZAHL, cursor: Long = 0L): List<TikTokFeedVideo> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val params = deviceParams(app, anzahl, cursor)
            val antwort = hole(app, "$HOST$PFAD_FEED", params, "")
            parseFeed(antwort)
        }

    suspend fun detail(context: Context, videoId: String): List<TikTokFeedVideo> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val params = deviceParams(app, 0)
            val body = "aweme_ids=%5B%22$videoId%22%5D&request_source=0"
            val antwort = hole(app, "$HOST$PFAD_DETAIL", params, body)
            parseFeed(antwort)
        }

    suspend fun userPosts(
        context: Context,
        secUid: String,
        anzahl: Int = ANZAHL,
        cursor: Long = 0L
    ): List<TikTokFeedVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val params = deviceParams(app, anzahl, cursor) + "&secUid=$secUid&post_item_list_request_type=0"
        val antwort = hole(app, "$HOST$PFAD_USER", params, "")
        parseFeed(antwort)
    }

    private suspend fun hole(
        context: Context,
        url: String,
        params: String,
        body: String
    ): String {
        val cookie = sessionCookie(context)
        val unix = System.currentTimeMillis() / 1000L
        val kopf = TikTokSignatur.signiere(
            params = params,
            payload = body,
            cookie = cookie,
            unix = unix,
            deviceId = wert(params, "device_id"),
            versionName = VERSION_NAME,
            deviceType = DEVICE_TYPE,
            osVersion = OS_VERSION,
            channel = CHANNEL
        )
        val koerper = if (body.isEmpty()) ByteArray(0) else gzipBody(body)
        val antwort = try {
            client.post("$url?$params") {
                header("User-Agent", USER_AGENT)
                header("Accept-Encoding", "gzip")
                header("Cookie", cookie)
                for ((name, wert) in kopf) header(name, wert)
                contentType(ContentType.Application.FormUrlEncoded)
                if (koerper.isNotEmpty()) header("Content-Encoding", "gzip")
                setBody(koerper)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Feed-Abfrage fehlgeschlagen: ${e.message}")
            return ""
        }
        val code = antwort.status.value
        val text = antwort.bodyAsText()
        if (code !in 200..299) {
            Log.w(TAG, "Feed HTTP $code: ${text.take(200)}")
            return ""
        }
        Log.i(TAG, "Feed-Antwort ${text.length} Zeichen")
        return text
    }

    private fun parseFeed(antwort: String): List<TikTokFeedVideo> {
        if (antwort.isEmpty()) return emptyList()
        return runCatching {
            val root = JSONObject(antwort)
            val status = root.optInt("status_code", -1)
            if (status != 0) {
                Log.w(TAG, "Feed status_code=$status ${root.optString("status_msg")}")
                return emptyList()
            }
            val liste = root.optJSONArray("aweme_list") ?: return emptyList()
            val raus = mutableListOf<TikTokFeedVideo>()
            for (i in 0 until liste.length()) {
                val eintrag = liste.optJSONObject(i) ?: continue
                val video = eintrag.optJSONObject("video") ?: continue
                val id = eintrag.optString("aweme_id")
                if (id.isEmpty()) continue
                val autor = eintrag.optJSONObject("author")
                val varianten = mutableListOf<String>()
                val bitraten = video.optJSONArray("bit_rate")
                if (bitraten != null) {
                    for (b in 0 until bitraten.length()) {
                        val eintragBitrate = bitraten.optJSONObject(b) ?: continue
                        val adresse = eintragBitrate.optJSONObject("play_addr")
                            ?.optJSONArray("url_list")
                        val wert = adresse?.optString(0).orEmpty()
                        if (wert.isNotEmpty()) varianten.add(wert)
                    }
                }
                val play = video.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0).orEmpty()
                if (play.isNotEmpty() && varianten.none { it == play }) varianten.add(play)
                val cover = video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
                raus.add(
                    TikTokFeedVideo(
                        videoId = id,
                        titel = eintrag.optString("desc").ifEmpty { "Video $id" },
                        autor = autor?.optString("nickname").orEmpty(),
                        coverUrl = cover,
                        dauerMillis = video.optLong("duration", 0L),
                        urls = varianten.distinct()
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
        val roh = text.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(9, true)
        deflater.setInput(roh)
        deflater.finish()
        val aus = java.io.ByteArrayOutputStream()
        aus.write(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0x02, 0xFF.toByte()))
        val puffer = ByteArray(4096)
        while (!deflater.finished()) {
            aus.write(puffer, 0, deflater.deflate(puffer))
        }
        deflater.end()
        val crc = java.util.zip.CRC32()
        crc.update(roh)
        val vier = ByteArray(4)
        for (i in 0 until 4) vier[i] = ((crc.value ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        for (i in 0 until 4) vier[i] = ((roh.size.toLong() ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        return aus.toByteArray()
    }

    private fun sessionCookie(context: Context): String {
        val roh = TikTokCookieRepository.cachedCookie(context)
        if (roh.isEmpty()) return ""
        var wert = ""
        for (teil in roh.split(";")) {
            val stueck = teil.trim()
            if (stueck.startsWith("sessionid=")) {
                wert = stueck
                break
            }
        }
        return wert
    }

    private fun wert(params: String, name: String): String {
        for (teil in params.split("&")) {
            val trenner = teil.indexOf('=')
            if (trenner > 0 && teil.substring(0, trenner) == name) {
                return teil.substring(trenner + 1)
            }
        }
        return ""
    }

    private fun urlEnc(text: String): String {
        val out = StringBuilder(text.length)
        for (zeichen in text) {
            val code = zeichen.code
            val erlaubt = (code in 48..57) || (code in 65..90) || (code in 97..122) ||
                zeichen == '-' || zeichen == '_' || zeichen == '.' || zeichen == '~'
            if (erlaubt) {
                out.append(zeichen)
            } else {
                val bytes = zeichen.toString().toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    out.append('%')
                    out.append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
        return out.toString()
    }

    private fun zufallsNummer(): String {
        val ziffern = StringBuilder(19)
        for (i in 0 until 19) {
            ziffern.append((Math.random() * 10).toInt())
        }
        return ziffern.toString()
    }

    private fun zufallsHex(): String {
        val out = StringBuilder(16)
        for (i in 0 until 16) {
            out.append("%02x".format((Math.random() * 256).toInt()))
        }
        return out.toString()
    }
}
