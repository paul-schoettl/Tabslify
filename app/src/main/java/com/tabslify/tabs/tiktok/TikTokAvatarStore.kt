package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object TikTokAvatarStore {

    private const val TAG = "TikTokAvatar"
    private const val ORDNER = "tiktok_avatars"
    private const val ENDUNG = ".img"
    private const val TEIL_ENDUNG = ".teil"
    private const val MAX_DATEIEN = 160

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failures = ConcurrentHashMap<String, Long>()
    private const val FEHLSCHLAG_SPERRE_MS = 30L * 60L * 1000L

    fun folder(context: Context): File =
        File(context.cacheDir, ORDNER).apply { if (!exists()) mkdirs() }

    fun file(context: Context, uid: String): File =
        File(folder(context), fileName(uid))

    private fun fileName(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_-]"), "_") + ENDUNG

    fun path(context: Context, uid: String): String =
        if (uid.isEmpty()) "" else file(context, uid)
            .takeIf { it.exists() && it.length() > 0L }
            ?.absolutePath ?: ""

    suspend fun load(context: Context, uid: String, url: String): String =
        withContext(Dispatchers.IO) {
            if (uid.isEmpty() || url.isEmpty()) return@withContext ""
            val app = context.applicationContext
            val last = failures[uid]
            if (last != null && System.currentTimeMillis() - last < FEHLSCHLAG_SPERRE_MS) {
                return@withContext path(app, uid)
            }
            locks.computeIfAbsent(uid) { Mutex() }.withLock {
                val vorhanden = path(app, uid)
                if (vorhanden.isNotEmpty()) return@withLock vorhanden
                val target = file(app, uid)
                val part = File(target.parentFile, target.name + TEIL_ENDUNG)
                val bytes = fetch(url)
                if (bytes == null || bytes.isEmpty()) {
                    failures[uid] = System.currentTimeMillis()
                    Log.w(TAG, "Avatar $uid nicht ladbar")
                    return@withLock ""
                }
                runCatching {
                    part.outputStream().use { it.write(bytes) }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    failures.remove(uid)
                    cleanup(app)
                    target.absolutePath
                }.getOrElse {
                    runCatching { part.delete() }
                    ""
                }
            }
        }

    fun forget(context: Context, uid: String) {
        failures.remove(uid)
        runCatching { file(context.applicationContext, uid).delete() }
    }

    private fun fetch(url: String): ByteArray? {
        var verbindung: HttpURLConnection? = null
        return try {
            verbindung = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", TikTokOfflineDownloader.APP_USER_AGENT)
                setRequestProperty("Referer", TikTokOfflineDownloader.REFERER)
            }
            val code = verbindung.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Avatar HTTP $code")
                return null
            }
            verbindung.inputStream.use { it.readBytes() }
        } catch (error: Exception) {
            Log.w(TAG, "Avatar-Download fehlgeschlagen: ${error.message}")
            null
        } finally {
            runCatching { verbindung?.disconnect() }
        }
    }

    private fun cleanup(context: Context) {
        val alle = folder(context).listFiles()?.filter { it.isFile } ?: return
        if (alle.size <= MAX_DATEIEN) return
        alle.sortedBy { it.lastModified() }
            .take(alle.size - MAX_DATEIEN)
            .forEach { runCatching { it.delete() } }
    }

    fun avatarUrl(profile: JSONObject): String {
        val avatars = profile.optJSONObject("avatars") ?: return ""
        for (key in listOf("avatar_small", "avatar_thumb", "avatar_medium")) {
            val entry = avatars.optJSONObject(key) ?: continue
            val list = entry.optJSONArray("url_list") ?: continue
            if (list.length() == 0) continue
            val url = list.optString(0)
            if (url.isNotEmpty()) return url
        }
        return ""
    }
}
