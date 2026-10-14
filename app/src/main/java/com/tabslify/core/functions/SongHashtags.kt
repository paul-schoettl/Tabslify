package com.tabslify.core.functions

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.tabslify.core.objects.Config
import com.tabslify.privatetabslifyapp.isOnline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

object SongHashtags {

    const val STATE_IDLE = "idle"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"

    private const val LEGACY_PREFS = "spotify_hashtags"
    private const val STATE_PREFS = "song_hashtag_status"
    private const val KEY_TAGGED = "tagged_paths"
    private const val LOG_TAG = "SongHashtags"
    private const val SERVICE_NAME = "songHashtags"
    private const val ANALYZE_TIMEOUT_MS = 90_000L
    private const val TEXT_TIMEOUT_MS = 25_000L
    private const val MAX_TAGS = 8

    data class TrackInfo(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val previewUrl: String
    )

    data class SongRef(
        val path: String,
        val uri: Uri,
        val title: String,
        val artist: String,
        val album: String
    )

    data class TagResult(
        val path: String,
        val hashtags: String?,
        val written: Boolean,
        val skipped: Boolean = false
    )

    data class BatchProgress(val done: Int, val total: Int)

    fun readHashtags(context: Context, uri: Uri, filePath: String?): String? =
        Id3Tags.readHashtags(context, uri, filePath)

    fun readLegacyHashtags(context: Context, trackId: String): String? =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(trackId, null)

    fun writeFileHashtags(context: Context, song: SongRef, hashtags: String): Boolean =
        Id3Tags.writeHashtags(context, song.uri, song.path, hashtags)

    fun isMarked(context: Context, path: String): Boolean =
        taggedPaths(context).contains(path)

    fun markTagged(context: Context, path: String) {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TAGGED, taggedPaths(context) + path).apply()
    }

    fun taggedPaths(context: Context): Set<String> =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAGGED, emptySet())?.toSet() ?: emptySet()

    suspend fun fetchTrack(context: Context, trackId: String): TrackInfo? {
        if (!isOnline(context) || trackId.isBlank()) return null
        val body = JSONObject().apply {
            put("action", "spotify_tracks")
            put("apiKey", Config.userApiKey(context, "rapidapi"))
            put("payload", JSONObject().apply { put("ids", trackId) })
        }
        val response = withTimeoutOrNull(ANALYZE_TIMEOUT_MS.milliseconds) {
            postProxy(context, body, ANALYZE_TIMEOUT_MS.toInt())
        } ?: return null

        return try {
            val tracks = JSONObject(response).optJSONObject("data")?.optJSONArray("tracks")
            val first = tracks?.optJSONObject(0) ?: return null
            val artists = first.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0) {
                artists.optJSONObject(0)?.optString("name").orEmpty()
            } else {
                ""
            }
            val albumObject = first.optJSONObject("album")
            val images = albumObject?.optJSONArray("images")
            TrackInfo(
                trackId = first.optString("id", trackId),
                title = first.optString("name", "Track"),
                artist = artistName.ifBlank { "Unknown" },
                album = albumObject?.optString("name").orEmpty(),
                cover = if (images != null && images.length() > 0) {
                    images.optJSONObject(0)?.optString("url").orEmpty()
                } else {
                    ""
                },
                previewUrl = first.optString("preview_url")
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Track-Antwort nicht lesbar: ${e.message}")
            null
        }
    }

    suspend fun analyzeTrack(context: Context, trackId: String): String? {
        val track = fetchTrack(context, trackId) ?: return null
        val preview = track.previewUrl
        if (preview.isBlank()) return requestTextHashtags(context, track.title, track.artist, track.album)

        val analyzed = analyzePreview(
            context = context,
            previewUrl = preview,
            details = "Titel: ${track.title}\nKünstler: ${track.artist}\nAlbum: ${track.album}"
        )
        return analyzed ?: requestTextHashtags(context, track.title, track.artist, track.album)
    }

    suspend fun analyzePreview(
        context: Context,
        previewUrl: String,
        details: String
    ): String? {
        if (!isOnline(context) || previewUrl.isBlank()) return null
        val body = JSONObject().apply {
            put("action", "song_analyze")
            put("payload", JSONObject().apply {
                put("previewUrl", previewUrl)
                put("details", details)
            })
        }
        val response = try {
            withTimeoutOrNull(ANALYZE_TIMEOUT_MS.milliseconds) {
                postProxy(context, body, ANALYZE_TIMEOUT_MS.toInt())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorInsert(
                SERVICE_NAME,
                "Vorschau-Analyse fehlgeschlagen: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            return null
        }
        if (response == null) return null
        return parseHashtags(readAnalysis(response))
    }

    suspend fun requestTextHashtags(
        context: Context,
        title: String,
        artist: String,
        album: String
    ): String? {
        if (!isOnline(context)) return null
        val prompt = "Erstelle 5 passende Hashtags für diesen Song.\n" +
            "Titel: $title\nKünstler: $artist\nAlbum: $album\n\n" +
            "Antworte nur mit den Hashtags, jeweils mit # davor und durch Leerzeichen getrennt."
        val body = JSONObject().apply {
            put("action", "gemini")
            put("payload", JSONObject().apply {
                put("model", "gemini-2.5-flash")
                put("system", "Du bist ein Musik-Metadaten-Dienst. Du antwortest auf Deutsch und gibst ausschließlich Hashtags zurück.")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            })
        }
        val response = try {
            withTimeoutOrNull(TEXT_TIMEOUT_MS.milliseconds) {
                postProxy(context, body, TEXT_TIMEOUT_MS.toInt())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorInsert(
                SERVICE_NAME,
                "Text-Analyse fehlgeschlagen: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            return null
        }
        if (response == null) return null
        return parseHashtags(readGeminiText(response))
    }

    suspend fun analyzeAndStore(context: Context, song: SongRef): TagResult {
        val known = readStored(context, song)
        if (known != null) return TagResult(song.path, known, written = false, skipped = true)

        val trackId = Id3Tags.readSpotifyTrackId(context, song.uri, song.path)
        val analyzed = if (trackId != null) {
            analyzeTrack(context, trackId)
        } else {
            requestTextHashtags(context, song.title, song.artist, song.album)
        }
        if (analyzed == null) return TagResult(song.path, null, written = false)

        val written = writeFileHashtags(context, song, analyzed)
        if (written) markTagged(context, song.path)
        return TagResult(song.path, analyzed, written = written)
    }

    suspend fun tagSongs(
        context: Context,
        songs: List<SongRef>,
        onProgress: suspend (BatchProgress) -> Unit = {}
    ): List<TagResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TagResult>()
        var done = 0

        songs.forEach { song ->
            if (readHashtags(context, song.uri, song.path) != null ||
                isMarked(context, song.path)
            ) {
                results.add(TagResult(song.path, null, written = false, skipped = true))
            } else {
                results.add(analyzeAndStore(context, song))
            }
            done++
            onProgress(BatchProgress(done, songs.size))
        }

        results
    }

    private fun readStored(context: Context, song: SongRef): String? {
        readHashtags(context, song.uri, song.path)?.let { return it }
        val legacyTrackId = Id3Tags.readSpotifyTrackId(context, song.uri, song.path)
        return legacyTrackId?.let { parseHashtags(readLegacyHashtags(context, it)) }
    }

    private fun readAnalysis(response: String): String? = try {
        val json = JSONObject(response)
        if (json.optString("status") == "ready") {
            json.optString("hashtags").ifBlank { null }
        } else {
            Log.w(LOG_TAG, "song_analyze: ${json.optString("error").ifBlank { "unbekannt" }}")
            null
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Analyse-Antwort nicht lesbar: ${e.message}")
        null
    }

    private fun readGeminiText(response: String): String? = try {
        val candidates = JSONObject(response).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            null
        } else {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                if (parts != null) {
                    for (index in 0 until parts.length()) {
                        parts.optJSONObject(index)?.optString("text")?.let { append(it) }
                    }
                }
            }
            text.trim().ifBlank { null }
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Gemini-Antwort nicht lesbar: ${e.message}")
        null
    }

    private suspend fun postProxy(context: Context, body: JSONObject, timeoutMs: Int): String? {
        val connection = Config.openApiProxyConnection(context, timeoutMs) ?: return null
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code != 200) Log.w(LOG_TAG, "api-proxy HTTP $code: ${text?.take(200)}")
            text
        } finally {
            connection.disconnect()
        }
    }

    fun parseHashtags(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.contains("[!ERROR]", ignoreCase = true)) return null

        val cleaned = if (text.contains('#')) {
            Regex("#([\\p{L}\\p{N}_-]+)")
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .toList()
        } else {
            text.split(Regex("[\\s,;]+"))
                .map { it.trim().trim('#', '"', '\'', '.') }
                .filter { it.length in 2..40 }
                .map { it.lowercase() }
                .toList()
        }

        return cleaned.distinct().take(MAX_TAGS)
            .joinToString(" ") { "#$it" }
            .takeIf { it.isNotBlank() }
    }
}