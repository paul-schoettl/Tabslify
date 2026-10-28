package com.tabslify.spotifydownloader_own.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.tabslify.core.functions.Id3Tags
import com.tabslify.core.functions.SongHashtags
import com.tabslify.core.objects.Config
import com.tabslify.spotifydownloader_own.domain.DownloadRepository
import com.tabslify.spotifydownloader_own.domain.DownloadState
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import kotlin.math.exp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadRepositoryImpl(
    private val httpClient: HttpClient,
    private val context: Context
) : DownloadRepository {

    private companion object {
        const val SONGS_BUCKET = "songs"
        const val QUEUE_TIMEOUT_MS = 180_000L
        const val POLL_INTERVAL_MS = 2_000L
        const val WAIT_CEILING = 54
        const val TRANSFER_START = 56
        const val TRANSFER_END = 95
    }

    override fun downloadTrack(
        spotifyUrl: String,
        confirmWithoutAi: suspend () -> Boolean
    ): Flow<DownloadState> = channelFlow {
        send(DownloadState.Searching)

        try {
            val trackId = spotifyUrl.split("/track/").getOrNull(1)?.split("?")?.getOrNull(0)
            if (trackId.isNullOrBlank()) {
                send(DownloadState.Error("Ungültige Spotify URL"))
                return@channelFlow
            }

            send(DownloadState.Downloading(6, "Auftrag gesendet"))
            val jobId = queueJob(trackId)
            if (jobId <= 0L) {
                send(DownloadState.Error("Auftrag konnte nicht angelegt werden"))
                return@channelFlow
            }

            val deadline = System.currentTimeMillis() + QUEUE_TIMEOUT_MS
            val waitStart = System.currentTimeMillis()
            var job: JSONObject? = null
            val ticker = launch {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - waitStart
                    val eased = 8 + (46 * (1 - exp(-elapsed / 9_000.0))).toInt()
                    send(
                        DownloadState.Downloading(
                            progress = eased.coerceAtMost(WAIT_CEILING),
                            detail = "MiniPC lädt · ${elapsed / 1000} s"
                        )
                    )
                    delay(300)
                }
            }
            try {
                var pollFailures = 0
                while (System.currentTimeMillis() < deadline) {
                    val current = try {
                        readJob(jobId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pollFailures++
                        if (pollFailures >= 3) throw e
                        null
                    }
                    if (current != null) pollFailures = 0
                    val status = current?.optString("status").orEmpty()
                    if (status == "done" || status == "failed") {
                        job = current
                        break
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                ticker.cancel()
            }

            val finished = job
            if (finished == null) {
                send(DownloadState.Error("Zeitüberschreitung – MiniPC nicht erreichbar?"))
                return@channelFlow
            }
            if (finished.optString("status") != "done") {
                val reason = finished.optString("error").ifBlank { "unbekannt" }
                send(DownloadState.Error("Server: $reason"))
                return@channelFlow
            }

            val storagePath = finished.optString("storage_path")
            if (storagePath.isBlank()) {
                send(DownloadState.Error("Server lieferte keine Datei"))
                return@channelFlow
            }

            val trackTitle = finished.optString("title").ifBlank { "Track" }
            val artist = finished.optString("artist").ifBlank { "Unknown" }
            val album = finished.optString("album")
            val coverUrl = finished.optString("cover").ifBlank { null }
            val safeArtist = artist.replace(Regex("[/\\\\:*?\"<>|]"), "_").replace(" ", "_")
            val safeTitle = trackTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_").replace(" ", "_")
            val fileName = "${safeArtist}_${safeTitle}.mp3"

            if (fileExistsInMediaStore(fileName)) {
                deleteStoredFile(storagePath)
                send(DownloadState.Error("Datei existiert bereits lokal"))
                return@channelFlow
            }

            send(DownloadState.Downloading(TRANSFER_START - 1, "Tag-Analyse läuft"))
            val hashtags = try {
                SongHashtags.requestTextHashtags(context, trackTitle, artist, album)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (hashtags.isNullOrBlank() && !confirmWithoutAi()) {
                deleteStoredFile(storagePath)
                send(DownloadState.Error("Abgebrochen"))
                return@channelFlow
            }

            send(DownloadState.Downloading(TRANSFER_START, "0,0 MB"))
            val bytes = downloadAudio(storagePath) { received, total ->
                val fraction = if (total > 0) {
                    (received.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    (1.0 - exp(-received / 6_000_000.0)).toFloat()
                }
                val percent = TRANSFER_START + (fraction * (TRANSFER_END - TRANSFER_START)).toInt()
                val detail = if (total > 0) {
                    "${megabytes(received)} / ${megabytes(total)} MB"
                } else {
                    "${megabytes(received)} MB"
                }
                send(DownloadState.Downloading(progress = percent, detail = detail))
            }
            if (bytes.isEmpty()) {
                send(DownloadState.Error("Leere Datei erhalten"))
                return@channelFlow
            }

            val coverBytes = coverUrl?.let {
                try {
                    withTimeout(15.seconds) { URL(it).readBytes() }
                } catch (_: Exception) {
                    null
                }
            }

            send(DownloadState.Downloading(97, "Tag wird geschrieben"))
            val fileUri = saveAudioBytes(
                fileName = fileName,
                bytes = bytes,
                trackId = trackId,
                title = trackTitle,
                artist = artist,
                album = album,
                coverBytes = coverBytes,
                hashtags = hashtags
            )
            deleteStoredFile(storagePath)

            send(DownloadState.Converting)
            delay(500.milliseconds)
            send(
                DownloadState.Success(
                    trackId = trackId,
                    title = trackTitle,
                    artist = artist,
                    fileName = fileName,
                    fileUri = fileUri,
                    note = hashtags?.takeIf { it.isNotBlank() } ?: "ohne KI-Analyse"
                )
            )
        } catch (e: Exception) {
            send(DownloadState.Error("Download fehlgeschlagen: ${e.localizedMessage}"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun queueJob(trackId: String): Long {
        val data = Config.client.from("song_jobs").insert(
            buildJsonObject { put("track_id", trackId) }
        ) {
            select(Columns.list("id"))
        }.data
        return JSONArray(data).optJSONObject(0)?.optLong("id") ?: 0L
    }

    private suspend fun readJob(jobId: Long): JSONObject? {
        val data = Config.client.from("song_jobs")
            .select(columns = Columns.list("id,status,title,artist,album,cover,storage_path,error")) {
                filter { eq("id", jobId) }
                limit(1L)
            }.data
        return JSONArray(data).optJSONObject(0)
    }

    private suspend fun downloadAudio(
        storagePath: String,
        onProgress: suspend (Long, Long) -> Unit
    ): ByteArray {
        val url = "${Config.SUPABASE_URL}/storage/v1/object/$SONGS_BUCKET/$storagePath"
        val response = httpClient.get(url) {
            headers {
                append("apikey", Config.SUPABASE_PUBLISHABLE_KEY)
                append("Authorization", "Bearer ${Config.SUPABASE_PUBLISHABLE_KEY}")
            }
            timeout {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 180_000
            }
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Datei-Download: ${response.status}")
        }
        val total = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var lastEmit = 0L
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            received += read
            val now = System.currentTimeMillis()
            if (now - lastEmit > 120) {
                onProgress(received, total)
                lastEmit = now
            }
        }
        onProgress(received, total)
        return output.toByteArray()
    }

    private fun megabytes(value: Long): String =
        String.format(Locale.GERMANY, "%.1f", value / 1024.0 / 1024.0)

    private suspend fun deleteStoredFile(path: String) {
        runCatching {
            Config.client.storage.from(SONGS_BUCKET).delete(listOf(path))
        }
    }

    private suspend fun saveAudioBytes(
        fileName: String,
        bytes: ByteArray,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        coverBytes: ByteArray?,
        hashtags: String?
    ): android.net.Uri {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Tabslify")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Konnte MediaStore Eintrag nicht erstellen")

        try {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(buildId3Tag(trackId, title, artist, album, coverBytes, hashtags))
                    outputStream.write(stripId3(bytes))
                    outputStream.flush()
                } ?: throw Exception("Konnte Datei nicht schreiben")
            }

            val updateValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
            }
            resolver.update(uri, updateValues, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun stripId3(bytes: ByteArray): ByteArray {
        if (bytes.size < 10) return bytes
        if (bytes[0] != 0x49.toByte() || bytes[1] != 0x44.toByte() || bytes[2] != 0x33.toByte()) {
            return bytes
        }
        val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
        val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        val end = 10 + size + footer
        return if (end in 10..bytes.size) bytes.copyOfRange(end, bytes.size) else bytes
    }

    private fun buildId3Tag(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        coverBytes: ByteArray?,
        hashtags: String?
    ): ByteArray {
        val frames = ByteArrayOutputStream()

        fun writeFrameHeader(id: String, size: Int) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(
                byteArrayOf(
                    ((size shr 24) and 0xFF).toByte(),
                    ((size shr 16) and 0xFF).toByte(),
                    ((size shr 8) and 0xFF).toByte(),
                    (size and 0xFF).toByte(),
                    0x00, 0x00
                )
            )
        }

        fun encodeTextFrame(id: String, text: String) {
            val textBytes = byteArrayOf(0x03) + text.toByteArray(Charsets.UTF_8)
            writeFrameHeader(id, textBytes.size)
            frames.write(textBytes)
        }

        encodeTextFrame("TIT2", title)
        encodeTextFrame("TPE1", artist)
        encodeTextFrame("TALB", album)

        val hashtagsValue = hashtags?.trim()
        if (!hashtagsValue.isNullOrEmpty()) {
            val txxx = ByteArrayOutputStream()
            txxx.write(0x03)
            txxx.write(Id3Tags.HASHTAG_DESCRIPTION.toByteArray(Charsets.ISO_8859_1))
            txxx.write(0x00)
            txxx.write(hashtagsValue.toByteArray(Charsets.UTF_8))
            val txxxBytes = txxx.toByteArray()
            writeFrameHeader("TXXX", txxxBytes.size)
            frames.write(txxxBytes)
        }

        val ufidOwner = "http://www.spotify.com".toByteArray(Charsets.ISO_8859_1)
        val ufidId = "spotify:track:$trackId".toByteArray(Charsets.ISO_8859_1)
        val ufidContent = ufidOwner + byteArrayOf(0x00) + ufidId
        writeFrameHeader("UFID", ufidContent.size)
        frames.write(ufidContent)

        if (coverBytes != null) {
            val mimeBytes = "image/jpeg".toByteArray(Charsets.ISO_8859_1)

            val apicHeader = ByteArrayOutputStream().apply {
                write(0x00)
                write(mimeBytes)
                write(0x00)
                write(0x03)
                write(0x00)
            }

            val apicContent = apicHeader.toByteArray() + coverBytes
            writeFrameHeader("APIC", apicContent.size)
            frames.write(apicContent)
        }

        val framesBytes = frames.toByteArray() + ByteArray(Id3Tags.PADDING_BYTES)
        val tagSize = framesBytes.size

        fun toSyncsafe(n: Int) = byteArrayOf(
            ((n shr 21) and 0x7F).toByte(),
            ((n shr 14) and 0x7F).toByte(),
            ((n shr 7) and 0x7F).toByte(),
            (n and 0x7F).toByte()
        )

        val header = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00) + toSyncsafe(tagSize)
        return header + framesBytes
    }

    private suspend fun fileExistsInMediaStore(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    cursor.count > 0
                } ?: false
            } catch (_: Exception) {
                false
            }
        }
}