package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TikTokVoiceStore {

    private const val TAG = "TikTokVoice"
    private const val ORDNER = "tiktok_voice"
    private const val ENDUNG = ".m4a"
    private const val TEIL_ENDUNG = ".teil"
    private const val MAX_DATEIEN = 200

    fun folder(context: Context): File =
        File(context.cacheDir, ORDNER).apply { if (!exists()) mkdirs() }

    fun file(context: Context, messageId: String): File =
        File(folder(context), fileName(messageId))

    fun fileName(messageId: String): String =
        messageId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ENDUNG

    fun existsLocal(context: Context, messageId: String): Boolean =
        messageId.isNotEmpty() && file(context, messageId).let { it.exists() && it.length() > 0L }

    fun path(context: Context, messageId: String): String =
        if (existsLocal(context, messageId)) "file://" + file(context, messageId).absolutePath else ""

    suspend fun load(context: Context, messageId: String, url: String): String =
        withContext(Dispatchers.IO) {
            if (messageId.isEmpty() || url.isEmpty()) return@withContext ""
            val app = context.applicationContext
            val target = file(app, messageId)
            if (target.exists() && target.length() > 0L) return@withContext "file://" + target.absolutePath
            val part = File(target.parentFile, target.name + TEIL_ENDUNG)
            val cookie = TikTokCookieRepository.cachedCookie(app)
            var verbindung: HttpURLConnection? = null
            try {
                verbindung = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", TikTokOfflineDownloader.APP_USER_AGENT)
                    setRequestProperty("Referer", TikTokOfflineDownloader.REFERER)
                    if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                }
                val code = verbindung.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Sprachnachricht $messageId: HTTP $code")
                    return@withContext ""
                }
                part.outputStream().use { aus ->
                    verbindung.inputStream.use { ein -> ein.copyTo(aus) }
                }
                if (part.length() <= 0L) {
                    part.delete()
                    return@withContext ""
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    runCatching { part.copyTo(target, overwrite = true); part.delete() }
                }
                cleanup(app)
                Log.i(TAG, "Sprachnachricht $messageId geladen: ${target.length()} B")
                "file://" + target.absolutePath
            } catch (error: Exception) {
                Log.w(TAG, "Sprachnachricht $messageId fehlgeschlagen: ${error.message}")
                runCatching { part.delete() }
                ""
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
}
