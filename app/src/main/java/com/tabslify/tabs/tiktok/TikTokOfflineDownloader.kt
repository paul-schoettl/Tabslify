package com.tabslify.tabs.tiktok

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tabslify.core.logging.Log
import androidx.core.app.NotificationCompat
import com.tabslify.core.objects.tNotify
import com.tabslify.widget.HandyLampClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

object TikTokOfflineDownloader {

    private const val TAG = "TikTokOffline"
    private const val CHANNEL_ID = "tiktok_offline"
    private const val NOTIFY_ID = 0x71C4
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0"
    internal const val APP_USER_AGENT =
        "com.zhiliaoapp.musically/2023508030 (Linux; U; Android 13; de_DE; Pixel 7; " +
            "Build/TD1A.220804.031; Cronet/58.0.2991.0)"
    internal const val REFERER = "https://www.tiktok.com/"
    private const val PUFFER = 32 * 1024
    private const val MELDUNG_ALLE_MS = 1000L
    private const val SCHRITT_MELDUNG = 256L * 1024L

    private class DownloadCancelled : Exception("Download abgebrochen")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = MutableStateFlow<List<OfflineAntrag>>(emptyList())
    private val discarded = mutableSetOf<String>()
    private var worker: Job? = null

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 600_000
            }
        }
    }

    suspend fun enqueue(context: Context, request: OfflineAntrag): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (request.urls.none { it.isNotBlank() }) return@withContext "keine Video-URL"
        TikTokOfflineStore.register(app, request)
        Log.i(
            TAG,
            "wartet ${request.videoId} ${request.title.take(40)} varianten=${request.urls.size}"
        )
        enqueueRequest(app, request)
        "in Warteschlange"
    }

    fun cancel(videoId: String) {
        discarded.add(videoId)
        Log.i(TAG, "Abbruch angefordert $videoId")
    }

    fun arbeitet(): Boolean = worker?.isActive == true

    fun starteAutoKontrolle(context: Context) {
        val app = context.applicationContext
        scope.launch { autoFill(app) }
    }

    suspend fun sizeBytes(context: Context, url: String): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (url.isBlank()) return@withContext 0L
        val header = runCatching {
            client.head(url) { headers(this, app) }
                .let { length(it) }
        }.getOrDefault(0L)
        if (header > 0L) return@withContext header
        runCatching {
            val response = client.get(url) {
                headers(this, app)
                header("Range", "bytes=0-0")
            }
            val kanal = response.bodyAsChannel()
            if (!response.status.isSuccess() || response.status != HttpStatusCode.PartialContent) {
                kanal.cancel(CancellationException("kein Bereich"))
                return@runCatching 0L
            }
            val section = response.headers[HttpHeaders.ContentRange].orEmpty()
            val totale = section.substringAfter('/').trim().toLongOrNull() ?: 0L
            kanal.cancel(CancellationException("nur Kopfzeile gelesen"))
            totale
        }.getOrDefault(0L)
    }

    private fun enqueueRequest(context: Context, request: OfflineAntrag) {
        if (queue.value.none { it.videoId == request.videoId }) {
            queue.value = queue.value + request
        }
        startWorker(context)
    }

    private fun startWorker(context: Context) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch { processQueue(context.applicationContext) }
        }
    }

    private suspend fun processQueue(context: Context) {
        try {
            while (true) {
                val request = queue.value.firstOrNull() ?: break
                queue.value = queue.value.filterNot { it.videoId == request.videoId }
                try {
                    download(context, request)
                } catch (e: DownloadCancelled) {
                    discarded.remove(request.videoId)
                    Log.i(TAG, "Download discarded ${request.videoId}")
                    TikTokOfflineStore.clearProgress(request.videoId)
                    sendUpdate(context, "Download abgebrochen", request.title, -1, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Download ${request.videoId} failed: ${e.message}")
                    TikTokOfflineStore.markState(
                        context,
                        request.videoId,
                        TikTokOfflineStore.STATE_ERROR,
                        e.message ?: "Download fehlgeschlagen"
                    )
                    TikTokOfflineStore.clearProgress(request.videoId)
                    sendUpdate(context, "Download fehlgeschlagen", request.title, -1, false)
                }
                autoFill(context)
            }
        } finally {
            synchronized(this) { worker = null }
            if (queue.value.isNotEmpty()) startWorker(context)
        }
    }

    private suspend fun autoFill(context: Context) {
        val app = context.applicationContext
        if (!TikTokOfflineStore.isAutoActive(app)) return
        if (queue.value.isNotEmpty()) return
        if (TikTokOfflineStore.progress.value.isNotEmpty()) return
        if (HandyLampClient.isCellularOnly(app)) {
            Log.i(TAG, "Auto waits: cellular only")
            return
        }
        val limit = TikTokOfflineStore.autoLimit(app)
        if (limit <= 0) return
        val rows = runCatching {
            TikTokOfflineDatabase.get(app).videoDao().byState(TikTokOfflineStore.STATE_PENDING)
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        val freeBytes = TikTokOfflineStore.freeBytes(app)
        if (freeBytes <= 0L) {
            Log.w(TAG, "Auto stopped: out of storage")
            return
        }
        var used = 0
        for (video in rows) {
            if (used >= limit) break
            if (video.streamUrl.isEmpty()) continue
            if (video.sizeBytes > 0L && freeBytes - video.sizeBytes < TikTokOfflineStore.SCHRITT_BYTES) break
            enqueueRequest(
                app,
                OfflineAntrag(
                    videoId = video.videoId,
                    title = video.title,
                    author = video.author,
                    coverUrl = video.coverUrl,
                    durationMs = video.durationMs,
                    urls = listOf(video.streamUrl)
                )
            )
            used++
            Log.i(TAG, "Auto lädt ${video.videoId} ${video.title.take(40)}")
        }
    }

    private suspend fun download(context: Context, request: OfflineAntrag) {
        val app = context.applicationContext
        discarded.remove(request.videoId)
        val candidates = request.urls.filter { it.isNotBlank() }
        if (candidates.isEmpty()) {
            TikTokOfflineStore.markState(
                app,
                request.videoId,
                TikTokOfflineStore.STATE_ERROR,
                "keine Video-URL"
            )
            return
        }
        val video = TikTokOfflineStore.get(app, request.videoId)
            ?: TikTokOfflineStore.register(app, request)
        val target = TikTokOfflineStore.file(app, video)
        val part = TikTokOfflineStore.partFile(app, video)
        if (target.exists() && target.length() > 0L) {
            Log.i(TAG, "bereits vorhanden ${request.videoId} ${target.length()}")
            TikTokOfflineStore.markState(app, request.videoId, TikTokOfflineStore.STATE_DONE, "", target.length())
            TikTokOfflineStore.clearProgress(request.videoId)
            return
        }
        var chosen = candidates.first()
        var kleinste = 0L
        for (url in candidates) {
            val sizeBytes = sizeBytes(app, url)
            if (sizeBytes <= 0L) continue
            if (kleinste == 0L || sizeBytes < kleinste) {
                kleinste = sizeBytes
                chosen = url
            }
        }
        val beginn = if (part.exists()) part.length() else 0L
        Log.i(
            TAG,
            "lade ${request.videoId} ab $beginn Bytes, Kandidaten=${candidates.size}, vorab=$kleinste"
        )
        TikTokOfflineStore.markState(app, request.videoId, TikTokOfflineStore.STATE_DOWNLOADING)
        var response = client.get(chosen) {
            headers(this, app)
            if (beginn > 0L) header("Range", "bytes=$beginn-")
        }
        if (response.status.value == 403) {
            Log.i(TAG, "403 bei ${request.videoId}, Versuch mit App-Kennung")
            response.bodyAsChannel().cancel(CancellationException("403"))
            response = client.get(chosen) {
                header("User-Agent", APP_USER_AGENT)
                header("Referer", REFERER)
                if (beginn > 0L) header("Range", "bytes=$beginn-")
            }
        }
        val resume = beginn > 0L && response.status == HttpStatusCode.PartialContent
        if (!response.status.isSuccess() && !resume) {
            val code = response.status.value
            response.bodyAsChannel().cancel(CancellationException("HTTP $code"))
            throw IllegalStateException("HTTP $code")
        }
        val expected = beginn + length(response)
        if (beginn > 0L && !resume) part.delete()
        val kanal = response.bodyAsChannel()
        var loaded = if (resume) beginn else 0L
        var currentState = 0L
        var lastMessage = 0L
        FileOutputStream(part, resume).use { fileStream ->
            val buffer = ByteArray(PUFFER)
            TikTokOfflineStore.setProgress(request.videoId, fraction(loaded, expected))
            while (true) {
                if (discarded.contains(request.videoId)) throw DownloadCancelled()
                val read = kanal.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) {
                    delay(20)
                    continue
                }
                fileStream.write(buffer, 0, read)
                loaded += read
                if (loaded - currentState >= SCHRITT_MELDUNG) {
                    currentState = loaded
                    val percent = fraction(loaded, expected)
                    TikTokOfflineStore.setProgress(request.videoId, percent)
                    val now = System.currentTimeMillis()
                    if (now - lastMessage >= MELDUNG_ALLE_MS) {
                        lastMessage = now
                        sendUpdate(context, "Lade Video", request.title, percent, true, expected, loaded)
                    }
                }
            }
            fileStream.flush()
        }
        kanal.cancel(CancellationException("Download beendet"))
        if (expected > 0L && part.length() < expected) {
            val ist = part.length()
            Log.w(TAG, "unvollständig ${request.videoId} $ist von $expected")
            throw IllegalStateException("unvollständig ($ist von $expected Bytes)")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        val sizeBytes = target.length()
        TikTokOfflineStore.markState(app, request.videoId, TikTokOfflineStore.STATE_DONE, "", sizeBytes)
        TikTokOfflineStore.clearProgress(request.videoId)
        TikTokOfflineStore.cleanupStorage(app)
        sendUpdate(context, "Video gespeichert", request.title, 100, false, sizeBytes, sizeBytes)
    }

    private fun fraction(loaded: Long, expected: Long): Int =
        if (expected > 0L) ((loaded * 100L) / expected).toInt().coerceIn(0, 100) else 0

    private fun length(response: HttpResponse): Long =
        response.headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L

    private fun headers(builder: HttpRequestBuilder, context: Context) {
        builder.header("Cookie", TikTokCookieRepository.cachedCookie(context))
        builder.header("User-Agent", USER_AGENT)
        builder.header("Referer", REFERER)
        builder.header("Accept", "*/*")
    }

    private fun sendUpdate(
        context: Context,
        title: String,
        text: String,
        percent: Int,
        isPlaying: Boolean,
        total: Long = 0L,
        current: Long = 0L
    ) {
        runCatching { kanalAnlegen(context) }
        val build = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (isPlaying) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(isPlaying)
            .setAutoCancel(!isPlaying)
            .setOnlyAlertOnce(true)
        if (isPlaying) {
            build.setProgress(100, percent, total <= 0L)
            if (total > 0L && current > 0L) {
                build.setContentText("$text · $percent % · ${formatMb(current)} von ${formatMb(total)}")
            }
        }
        runCatching { tNotify(context, NOTIFY_ID, build.build()) }
    }

    private fun kanalAnlegen(context: Context) {
        val dienst = context.getSystemService(NotificationManager::class.java) ?: return
        if (dienst.getNotificationChannel(CHANNEL_ID) != null) return
        dienst.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "TikTok Offline-Videos",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Zeigt laufende und gespeicherte Video-Downloads" }
        )
    }
}