package com.tabslify.tabs.tiktok

import android.content.Context
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

    const val PREFS = "tiktok_offline"
    const val ORDNER = "tiktok_offline"
    const val ENDUNG = ".mp4"
    const val TEIL_ENDUNG = ".teil"

    const val STATE_DONE = "fertig"
    const val STATE_PENDING = "wartet"
    const val STATE_DOWNLOADING = "laedt"
    const val STATE_ERROR = "fehler"

    private const val TAG = "TikTokOffline"
    private const val KEY_MAX_BYTES = "max_bytes"
    private const val KEY_AUTO_LIMIT = "auto_limit"
    private const val KEY_AUTO_AKTIV = "auto_aktiv"

    private val _videos = MutableStateFlow<List<OfflineVideo>>(emptyList())
    val videos: StateFlow<List<OfflineVideo>> = _videos.asStateFlow()

    private val _fortschritt = MutableStateFlow<Map<String, Int>>(emptyMap())
    val fortschritt: StateFlow<Map<String, Int>> = _fortschritt.asStateFlow()

    private val _kandidaten = MutableStateFlow<List<TikTokFeedVideo>>(emptyList())
    val kandidaten: StateFlow<List<TikTokFeedVideo>> = _kandidaten.asStateFlow()

    private var geladen = false

    fun setzeKandidaten(liste: List<TikTokFeedVideo>) {
        _kandidaten.value = liste
    }

    fun kandidat(videoId: String): TikTokFeedVideo? =
        _kandidaten.value.firstOrNull { it.videoId == videoId }

    fun istGeladen(): Boolean = geladen

    suspend fun lade(context: Context): List<OfflineVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ordner(app)
        val zeilen = runCatching { TikTokOfflineDatabase.get(app).videoDao().all() }
            .onFailure { Log.w(TAG, "Bibliothek nicht lesbar: ${it.message}") }
            .getOrDefault(emptyList())
        val sauber = bereinige(app, zeilen)
        geladen = true
        _videos.value = sauber
        sauber
    }

    suspend fun aktualisiere(context: Context): List<OfflineVideo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val zeilen = runCatching { TikTokOfflineDatabase.get(app).videoDao().all() }
            .onFailure { Log.w(TAG, "Bibliothek nicht lesbar: ${it.message}") }
            .getOrDefault(emptyList())
        _videos.value = zeilen
        zeilen
    }

    suspend fun get(context: Context, videoId: String): OfflineVideo? = withContext(Dispatchers.IO) {
        runCatching { TikTokOfflineDatabase.get(context.applicationContext).videoDao().get(videoId) }
            .getOrNull()
    }

    suspend fun registriere(context: Context, antrag: OfflineAntrag): OfflineVideo =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val dao = TikTokOfflineDatabase.get(app).videoDao()
            val alt = runCatching { dao.get(antrag.videoId) }.getOrNull()
            val zeile = OfflineVideo(
                videoId = antrag.videoId,
                title = antrag.title.ifEmpty { "Video" },
                author = antrag.author,
                coverUrl = antrag.coverUrl,
                streamUrl = antrag.urls.firstOrNull().orEmpty(),
                durationMs = antrag.durationMs,
                sizeBytes = alt?.sizeBytes ?: 0L,
                fileName = alt?.fileName?.takeIf { it.isNotEmpty() } ?: dateiName(antrag.videoId),
                state = STATE_PENDING,
                error = "",
                positionMs = alt?.positionMs ?: 0L,
                addedAt = alt?.addedAt ?: System.currentTimeMillis(),
                lastPlayedAt = alt?.lastPlayedAt ?: 0L
            )
            runCatching { dao.upsert(zeile) }
                .onFailure { Log.w(TAG, "Anlegen fehlgeschlagen ${antrag.videoId}: ${it.message}") }
            aktualisiere(app)
            zeile
        }

    suspend fun markiere(
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

    suspend fun speicherePosition(context: Context, videoId: String, positionMs: Long) =
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

    suspend fun loesche(context: Context, videoId: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TikTokOfflineDatabase.get(app).videoDao()
        val alt = runCatching { dao.get(videoId) }.getOrNull()
        runCatching {
            alt?.let { datei(app, it).delete() }
            alt?.let { teilDatei(app, it).delete() }
            dao.delete(videoId)
        }.onFailure { Log.w(TAG, "Löschen fehlgeschlagen $videoId: ${it.message}") }
        fortschrittWeg(videoId)
        aktualisiere(app)
    }

    fun fortschrittSetzen(videoId: String, prozent: Int) {
        _fortschritt.value = _fortschritt.value + (videoId to prozent.coerceIn(0, 100))
    }

    fun fortschrittWeg(videoId: String) {
        if (!_fortschritt.value.containsKey(videoId)) return
        _fortschritt.value = _fortschritt.value - videoId
    }

    suspend fun raeumeAuf(context: Context, behalten: String? = null) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TikTokOfflineDatabase.get(app).videoDao()
        val grenze = maxBytes(app)
        val reihenfolge = runCatching { dao.oldestFirst() }.getOrDefault(emptyList())
        var anzahl = 0
        var summe = 0L
        for (video in reihenfolge) {
            if (video.state == STATE_PENDING || video.state == STATE_DOWNLOADING) continue
            val dateiOk = datei(app, video).exists()
            if (video.state == STATE_DONE && dateiOk) {
                anzahl++
                summe += datei(app, video).length()
                continue
            }
            runCatching { teilDatei(app, video).delete() }
            if (video.state == STATE_DONE) {
                runCatching {
                    datei(app, video).delete()
                    dao.delete(video.videoId)
                }.onFailure { Log.w(TAG, "Aufräumen fehlgeschlagen ${video.videoId}: ${it.message}") }
                fortschrittWeg(video.videoId)
            }
        }
        val reihenfolgeFrisch = runCatching { dao.oldestFirst() }.getOrDefault(emptyList())
        for (video in reihenfolgeFrisch) {
            if (anzahl < MAX_VIDEOS && summe <= grenze) break
            if (video.videoId == behalten) continue
            if (video.state != STATE_DONE) continue
            if (datei(app, video).exists()) {
                summe -= datei(app, video).length()
            }
            anzahl--
            runCatching {
                datei(app, video).delete()
                teilDatei(app, video).delete()
                dao.delete(video.videoId)
            }.onFailure { Log.w(TAG, "Kürzung fehlgeschlagen ${video.videoId}: ${it.message}") }
        }
        if (anzahl < MAX_VIDEOS && summe <= grenze) {
            Log.i(TAG, "Bibliothek geprüft: $anzahl Videos, ${summe / (1024L * 1024L)} MB")
        } else {
            Log.i(TAG, "Bibliothek gekürzt auf $anzahl Videos, ${summe / (1024L * 1024L)} MB")
        }
        aktualisiere(app)
    }

    fun ordner(context: Context): File =
        File(context.filesDir, ORDNER).apply { if (!exists()) mkdirs() }

    fun datei(context: Context, video: OfflineVideo): File =
        File(ordner(context), video.fileName)

    fun teilDatei(context: Context, video: OfflineVideo): File =
        File(ordner(context), video.fileName + TEIL_ENDUNG)

    fun dateiName(videoId: String): String = sanitize(videoId) + ENDUNG

    fun maxBytes(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)
            .coerceAtLeast(SCHRITT_BYTES)

    fun setzeMaxBytes(context: Context, wert: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_MAX_BYTES, wert.coerceAtLeast(SCHRITT_BYTES)).apply()
    }

    fun autoLimit(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_LIMIT, DEFAULT_AUTO_LIMIT)
            .coerceIn(0, MAX_AUTO_LIMIT)

    fun setzeAutoLimit(context: Context, wert: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AUTO_LIMIT, wert.coerceIn(0, MAX_AUTO_LIMIT)).apply()
    }

    fun autoAktiv(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_AKTIV, true)

    fun setzeAutoAktiv(context: Context, aktiv: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_AKTIV, aktiv).apply()
    }

    fun frei(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun summeBytes(zeilen: List<OfflineVideo>): Long =
        zeilen.filter { it.state == STATE_DONE }.sumOf { it.sizeBytes }

    private suspend fun bereinige(context: Context, zeilen: List<OfflineVideo>): List<OfflineVideo> {
        val dao = runCatching { TikTokOfflineDatabase.get(context).videoDao() }.getOrNull()
            ?: return zeilen
        val behalten = mutableSetOf<String>()
        val korrigiert = mutableListOf<OfflineVideo>()
        for (video in zeilen) {
            val dateiOk = datei(context, video).exists()
            if (video.state == STATE_DONE && !dateiOk) {
                runCatching { dao.delete(video.videoId) }
                fortschrittWeg(video.videoId)
                Log.i(TAG, "Datei fehlt, Eintrag entfernt ${video.videoId}")
                continue
            }
            behalten.add(video.fileName)
            behalten.add(video.fileName + TEIL_ENDUNG)
            val echteGroesse = if (dateiOk) datei(context, video).length() else 0L
            if (echteGroesse > 0L && echteGroesse != video.sizeBytes) {
                val neu = video.copy(sizeBytes = echteGroesse)
                runCatching { dao.upsert(neu) }
                korrigiert.add(neu)
            } else {
                korrigiert.add(video)
            }
        }
        ordner(context).listFiles()?.forEach { datei ->
            if (!behalten.contains(datei.name)) {
                runCatching { datei.delete() }
                Log.i(TAG, "Verwaiste Datei entfernt ${datei.name}")
            }
        }
        return korrigiert.sortedByDescending { it.addedAt }
    }

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (zeichen in raw) {
            val erlaubt = (zeichen in 'a'..'z') || (zeichen in 'A'..'Z') ||
                (zeichen in '0'..'9') || zeichen == '-' || zeichen == '_' || zeichen == '.'
            out.append(if (erlaubt) zeichen else '_')
        }
        return out.toString().take(80).ifEmpty { "video" }
    }
}
