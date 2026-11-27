package com.tabslify.tabs.tiktok

import android.content.Context
import androidx.core.content.edit
import android.os.StatFs
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

object TikTokOfflineStore {

    const val MAX_VIDEOS = 400
    const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L
    const val DEFAULT_AUTO_LIMIT = 5
    const val MAX_AUTO_LIMIT = 50
    const val SCHRITT_BYTES = 512L * 1024L * 1024L
    const val GESEHEN_MAX = 2000

    const val PREFS = "tiktok_offline"
    const val ORDNER = "tiktok_offline"
    const val ENDUNG = ".mp4"
    const val TEIL_ENDUNG = ".teil"

    const val STATE_DONE = "finished"
    const val STATE_PENDING = "wartet"
    const val STATE_DOWNLOADING = "laedt"
    const val STATE_ERROR = "fehler"

    private const val TAG = "TikTokOffline"
    private const val KEY_MAX_BYTES = "max_bytes"
    private const val KEY_AUTO_LIMIT = "auto_limit"
    private const val KEY_AUTO_AKTIV = "auto_aktiv"
    private const val KEY_GESEHEN = "gesehen"

    private val _videos = MutableStateFlow<List<OfflineVideo>>(emptyList())
    val videos: StateFlow<List<OfflineVideo>> = _videos.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    private val _candidates = MutableStateFlow<List<TikTokFeedVideo>>(emptyList())
    val candidates: StateFlow<List<TikTokFeedVideo>> = _candidates.asStateFlow()

    private var loaded = false

    fun setCandidates(list: List<TikTokFeedVideo>) {
        _candidates.value = list
    }

    fun candidate(videoId: String): TikTokFeedVideo? =
        _candidates.value.firstOrNull { it.videoId == videoId }

    fun isDownloaded(): Boolean = loaded

    suspend fun loadStore(context: Context): List<OfflineVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        folder(app)
        val rows = runCatching { TikTokOfflineDatabase.get(app).videoDao().all() }
            .onFailure { Log.w(TAG, "Bibliothek nicht lesbar: ${it.message}") }
            .getOrDefault(emptyList())
        val sauber = bereinige(app, rows)
        loaded = true
        _videos.value = sauber
        sauber
    }

    suspend fun aktualisiere(context: Context): List<OfflineVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val rows = runCatching { TikTokOfflineDatabase.get(app).videoDao().all() }
            .onFailure { Log.w(TAG, "Bibliothek nicht lesbar: ${it.message}") }
            .getOrDefault(emptyList())
        _videos.value = rows
        rows
    }

    suspend fun get(context: Context, videoId: String): OfflineVideo? = withContext(Dispatchers.IO) {
        runCatching { TikTokOfflineDatabase.get(context.applicationContext).videoDao().get(videoId) }
            .getOrNull()
    }

    suspend fun register(context: Context, request: OfflineAntrag): OfflineVideo =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val dao = TikTokOfflineDatabase.get(app).videoDao()
            val alt = runCatching { dao.get(request.videoId) }.getOrNull()
            val row = OfflineVideo(
                videoId = request.videoId,
                title = request.title.ifEmpty { "Video" },
                author = request.author,
                coverUrl = request.coverUrl,
                streamUrl = request.urls.firstOrNull().orEmpty(),
                durationMs = request.durationMs,
                sizeBytes = alt?.sizeBytes ?: 0L,
                fileName = alt?.fileName?.takeIf { it.isNotEmpty() } ?: fileName(request.videoId),
                state = STATE_PENDING,
                error = "",
                positionMs = alt?.positionMs ?: 0L,
                addedAt = alt?.addedAt ?: System.currentTimeMillis(),
                lastPlayedAt = alt?.lastPlayedAt ?: 0L
            )
            runCatching { dao.upsert(row) }
                .onFailure { Log.w(TAG, "Anlegen fehlgeschlagen ${request.videoId}: ${it.message}") }
            aktualisiere(app)
            row
        }

    suspend fun markState(
        context: Context,
        videoId: String,
        state: String,
        error: String = "",
        sizeBytes: Long = -1L
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TikTokOfflineDatabase.get(app).videoDao()
        val alt = runCatching { dao.get(videoId) }.getOrNull() ?: return@withContext
        runCatching {
            dao.upsert(
                alt.copy(
                    state = state,
                    error = error,
                    sizeBytes = if (sizeBytes >= 0L) sizeBytes else alt.sizeBytes,
                    lastPlayedAt = if (state == STATE_DONE) System.currentTimeMillis() else alt.lastPlayedAt
                )
            )
        }.onFailure { Log.w(TAG, "Status nicht speicherbar $videoId: ${it.message}") }
        aktualisiere(app)
    }

    suspend fun savePosition(context: Context, videoId: String, positionMs: Long) =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val dao = TikTokOfflineDatabase.get(app).videoDao()
            val alt = runCatching { dao.get(videoId) }.getOrNull() ?: return@withContext
            runCatching {
                dao.upsert(
                    alt.copy(
                        positionMs = positionMs.coerceAtLeast(0L),
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { Log.w(TAG, "Position nicht speicherbar $videoId: ${it.message}") }
        }

    suspend fun deleteItem(context: Context, videoId: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TikTokOfflineDatabase.get(app).videoDao()
        val alt = runCatching { dao.get(videoId) }.getOrNull()
        runCatching {
            alt?.let { file(app, it).delete() }
            alt?.let { partFile(app, it).delete() }
            dao.delete(videoId)
        }.onFailure { Log.w(TAG, "Löschen fehlgeschlagen $videoId: ${it.message}") }
        clearProgress(videoId)
        aktualisiere(app)
    }

    fun setProgress(videoId: String, percent: Int) {
        _progress.value = _progress.value + (videoId to percent.coerceIn(0, 100))
    }

    fun clearProgress(videoId: String) {
        if (!_progress.value.containsKey(videoId)) return
        _progress.value = _progress.value - videoId
    }

    suspend fun cleanupStorage(context: Context, behalten: String? = null) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TikTokOfflineDatabase.get(app).videoDao()
        val limitBytes = maxBytes(app)
        val reihenfolge = runCatching { dao.oldestFirst() }.getOrDefault(emptyList())
        var count = 0
        var sum = 0L
        for (video in reihenfolge) {
            if (video.state == STATE_PENDING || video.state == STATE_DOWNLOADING) continue
            val isFileValid = file(app, video).exists()
            if (video.state == STATE_DONE && isFileValid) {
                count++
                sum += file(app, video).length()
                continue
            }
            runCatching { partFile(app, video).delete() }
            if (video.state == STATE_DONE) {
                runCatching {
                    file(app, video).delete()
                    dao.delete(video.videoId)
                }.onFailure { Log.w(TAG, "Aufräumen fehlgeschlagen ${video.videoId}: ${it.message}") }
                clearProgress(video.videoId)
            }
        }
        val orderFresh = runCatching { dao.oldestFirst() }.getOrDefault(emptyList())
        for (video in orderFresh) {
            if (count < MAX_VIDEOS && sum <= limitBytes) break
            if (video.videoId == behalten) continue
            if (video.state != STATE_DONE) continue
            markSeen(app, video.videoId)
            if (file(app, video).exists()) {
                sum -= file(app, video).length()
            }
            count--
            runCatching {
                file(app, video).delete()
                partFile(app, video).delete()
                dao.delete(video.videoId)
            }.onFailure { Log.w(TAG, "Kürzung fehlgeschlagen ${video.videoId}: ${it.message}") }
        }
        if (count < MAX_VIDEOS && sum <= limitBytes) {
            Log.i(TAG, "Library checked: $count videos, ${sum / (1024L * 1024L)} MB")
        } else {
            Log.i(TAG, "Library trimmed to $count videos, ${sum / (1024L * 1024L)} MB")
        }
        aktualisiere(app)
    }

    fun folder(context: Context): File =
        File(context.filesDir, ORDNER).apply { if (!exists()) mkdirs() }

    fun file(context: Context, video: OfflineVideo): File =
        File(folder(context), video.fileName)

    fun partFile(context: Context, video: OfflineVideo): File =
        File(folder(context), video.fileName + TEIL_ENDUNG)

    fun fileName(videoId: String): String = sanitize(videoId) + ENDUNG

    fun freeBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun maxBytes(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)
            .coerceAtLeast(SCHRITT_BYTES)

    fun setMaxBytes(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_MAX_BYTES, value.coerceAtLeast(SCHRITT_BYTES))
        }
    }

    fun autoLimit(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_LIMIT, DEFAULT_AUTO_LIMIT)
            .coerceIn(0, MAX_AUTO_LIMIT)

    fun setAutoLimit(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_AUTO_LIMIT, value.coerceIn(0, MAX_AUTO_LIMIT))
        }
    }

    fun isAutoActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_AKTIV, true)

    fun setAutoActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_AKTIV, active)
        }
    }

    fun seen(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_GESEHEN, emptySet())
            ?.toSet()
            .orEmpty()

    fun markSeen(context: Context, videoId: String) {
        if (videoId.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_GESEHEN, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (!previous.add(videoId)) return
        val gekappt = if (previous.size > GESEHEN_MAX) {
            previous.toList().takeLast(GESEHEN_MAX).toSet()
        } else {
            previous
        }
        prefs.edit { putStringSet(KEY_GESEHEN, gekappt) }
    }

    fun free(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun totalBytes(rows: List<OfflineVideo>): Long =
        rows.filter { it.state == STATE_DONE }.sumOf { it.sizeBytes }

    private suspend fun bereinige(context: Context, rows: List<OfflineVideo>): List<OfflineVideo> {
        val dao = runCatching { TikTokOfflineDatabase.get(context).videoDao() }.getOrNull()
            ?: return rows
        val behalten = mutableSetOf<String>()
        val korrigiert = mutableListOf<OfflineVideo>()
        for (video in rows) {
            val isFileValid = file(context, video).exists()
            if (video.state == STATE_DONE && !isFileValid) {
                runCatching { dao.delete(video.videoId) }
                clearProgress(video.videoId)
                Log.i(TAG, "Datei fehlt, Eintrag entfernt ${video.videoId}")
                continue
            }
            behalten.add(video.fileName)
            behalten.add(video.fileName + TEIL_ENDUNG)
            val realSize = if (isFileValid) file(context, video).length() else 0L
            if (realSize > 0L && realSize != video.sizeBytes) {
                val neu = video.copy(sizeBytes = realSize)
                runCatching { dao.upsert(neu) }
                korrigiert.add(neu)
            } else {
                korrigiert.add(video)
            }
        }
        folder(context).listFiles()?.forEach { file ->
            if (!behalten.contains(file.name)) {
                runCatching { file.delete() }
                Log.i(TAG, "Verwaiste Datei entfernt ${file.name}")
            }
        }
        return korrigiert.sortedByDescending { it.addedAt }
    }

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (character in raw) {
            val erlaubt = (character in 'a'..'z') || (character in 'A'..'Z') ||
                (character in '0'..'9') || character == '-' || character == '_' || character == '.'
            out.append(if (erlaubt) character else '_')
        }
        return out.toString().take(80).ifEmpty { "video" }
    }
}
