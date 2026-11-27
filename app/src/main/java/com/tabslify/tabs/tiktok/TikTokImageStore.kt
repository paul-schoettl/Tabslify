package com.tabslify.tabs.tiktok

import android.content.Context
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TikTokImageStore {

    private const val TAG = "TikTokBild"
    private const val ORDNER = "tiktok_bilder"
    private const val ENDUNG = ".img"
    private const val TEIL_ENDUNG = ".teil"
    private const val MAX_DATEIEN = 120

    private val locks = ConcurrentHashMap<String, Mutex>()

    fun folder(context: Context): File =
        File(context.cacheDir, ORDNER).apply { if (!exists()) mkdirs() }

    fun file(context: Context, messageId: String): File =
        File(folder(context), fileName(messageId))

    fun fileName(messageId: String): String =
        messageId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ENDUNG

    fun existsLocal(context: Context, messageId: String): Boolean =
        messageId.isNotEmpty() && file(context, messageId).let { it.exists() && it.length() > 0L }

    fun path(context: Context, messageId: String): String =
        if (existsLocal(context, messageId)) file(context, messageId).absolutePath else ""

    suspend fun load(
        context: Context,
        messageId: String,
        url: String,
        keyHex: String,
        convId: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (messageId.isEmpty() || url.isEmpty()) return@withContext ""
        val app = context.applicationContext
        locks.computeIfAbsent(messageId) { Mutex() }.withLock {
            if (existsLocal(app, messageId)) return@withLock file(app, messageId).absolutePath
            val target = file(app, messageId)
            val part = File(target.parentFile, target.name + TEIL_ENDUNG)
            val raw = fetch(app, url)
            if (raw == null) {
                if (convId.isNotEmpty()) {
                    Log.i(TAG, "Bild $messageId nicht ladbar, hole freshItems URL")
                    TikTokDmClient.renewMediaUrl(app, convId)
                }
                return@withLock ""
            }
            val bytes = if (keyHex.isEmpty()) raw else decrypt(raw, keyHex)
            if (bytes == null) {
                Log.w(TAG, "Bild $messageId nicht entschluesselbar")
                return@withLock ""
            }
            try {
                part.outputStream().use { it.write(bytes) }
                if (part.length() <= 0L) {
                    part.delete()
                    return@withLock ""
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    runCatching { part.copyTo(target, overwrite = true); part.delete() }
                }
                cleanup(app)
                Log.i(TAG, "Bild $messageId geladen: ${target.length()} B")
                target.absolutePath
            } catch (error: Exception) {
                Log.w(TAG, "Bild $messageId fehlgeschlagen: ${error.message}")
                runCatching { part.delete() }
                ""
            }
        }
    }

    private suspend fun fetch(context: Context, url: String): ByteArray? {
        var cookie = TikTokCookieRepository.cachedCookie(context)
        var bytes = fetchWith(url, cookie)
        if (bytes == null && cookie.isNotEmpty()) {
            val fresh = runCatching { TikTokCookieRepository.refresh(context) }.getOrNull()
            if (fresh != null && fresh.cookie != cookie) {
                cookie = fresh.cookie
                bytes = fetchWith(url, cookie)
            }
        }
        return bytes
    }

    private fun fetchWith(url: String, cookie: String): ByteArray? {
        var verbindung: HttpURLConnection? = null
        val tiktok = url.contains("tiktok", ignoreCase = true)
        return try {
            verbindung = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", TikTokOfflineDownloader.APP_USER_AGENT)
                setRequestProperty("Referer", TikTokOfflineDownloader.REFERER)
                if (tiktok && cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            }
            val code = verbindung.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Bild HTTP $code")
                return null
            }
            verbindung.inputStream.use { it.readBytes() }
        } catch (error: Exception) {
            Log.w(TAG, "Bild Download fehlgeschlagen: ${error.message}")
            null
        } finally {
            runCatching { verbindung?.disconnect() }
        }
    }

    private fun decrypt(daten: ByteArray, keyHex: String): ByteArray? = runCatching {
        if (daten.size <= 12 || keyHex.length < 64) return null
        val key = ByteArray(keyHex.length / 2) {
            ((keyHex[it * 2].digitToInt(16) shl 4) or keyHex[it * 2 + 1].digitToInt(16)).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, daten.copyOfRange(0, 12))
        )
        cipher.doFinal(daten.copyOfRange(12, daten.size))
    }.getOrNull()

    private fun cleanup(context: Context) {
        val alle = folder(context).listFiles()?.filter { it.isFile } ?: return
        if (alle.size <= MAX_DATEIEN) return
        alle.sortedBy { it.lastModified() }
            .take(alle.size - MAX_DATEIEN)
            .forEach { runCatching { it.delete() } }
    }
}
