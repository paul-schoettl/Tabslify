package com.tabslify.core.functions

import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.core.content.edit

object SongHashtags {

    const val STATE_IDLE = "idle"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"

    private const val LEGACY_PREFS = "spotify_hashtags"
    private const val STATE_PREFS = "song_hashtag_status"
    private const val KEY_TAGGED = "tagged_paths"
    private const val LOG_TAG = "SongHashtags"
    private const val SERVICE_NAME = "songHashtags"
    private const val TEXT_TIMEOUT_MS = 25_000L
    private const val MAX_TAGS = 8

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
        val skipped: Boolean = false,
        val quotaExceeded: Boolean = false
    )

    class QuotaExceededException : Exception("Gemini-Quota erschöpft")

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
        prefs.edit {putStringSet(KEY_TAGGED, taggedPaths(context) + path) }
    }

    fun taggedPaths(context: Context): Set<String> =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAGGED, emptySet())?.toSet() ?: emptySet()

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
                put("model", Config.DEF_GEMINI)
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
        val (code, payload) = response
        if (code == 429) throw QuotaExceededException()
        if (payload == null) return null
        return parseHashtags(readGeminiText(payload))
    }

    suspend fun analyzeAndStore(context: Context, song: SongRef): TagResult {
        val known = readStored(context, song)
        if (known != null) return TagResult(song.path, known, written = false, skipped = true)

        val analyzed = requestTextHashtags(context, song.title, song.artist, song.album)
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
                try {
                    results.add(analyzeAndStore(context, song))
                } catch (e: QuotaExceededException) {
                    Log.w(LOG_TAG, "Quota erschöpft, Lauf abgebrochen bei $done/${songs.size}")
                    results.add(
                        TagResult(song.path, null, written = false, quotaExceeded = true)
                    )
                    return@withContext results
                }
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

    private suspend fun postProxy(context: Context, body: JSONObject, timeoutMs: Int): Pair<Int, String?> {
        val connection = Config.openApiProxyConnection(context, timeoutMs) ?: return 0 to null
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code != 200) Log.w(LOG_TAG, "api-proxy HTTP $code: ${text?.take(200)}")
            code to text
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