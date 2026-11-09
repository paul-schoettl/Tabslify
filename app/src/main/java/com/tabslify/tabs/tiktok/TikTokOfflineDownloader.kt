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

    private class Abbruch : Exception("Download abgebrochen")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warteschlange = MutableStateFlow<List<OfflineAntrag>>(emptyList())
    private val verworfen = mutableSetOf<String>()
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

    suspend fun enqueue(context: Context, antrag: OfflineAntrag): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (antrag.urls.none { it.isNotBlank() }) return@withContext "keine Video-URL"
        TikTokOfflineStore.registriere(app, antrag)
        Log.i(
            TAG,
            "wartet ${antrag.videoId} ${antrag.title.take(40)} varianten=${antrag.urls.size}"
        )
        einreihen(app, antrag)
        "in Warteschlange"
    }

    fun abbrechen(videoId: String) {
        verworfen.add(videoId)
        Log.i(TAG, "Abbruch angefordert $videoId")
    }

    fun arbeitet(): Boolean = worker?.isActive == true

    fun starteAutoKontrolle(context: Context) {
        val app = context.applicationContext
        scope.launch { autoFuellen(app) }
    }

    suspend fun groesse(context: Context, url: String): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (url.isBlank()) return@withContext 0L
        val kopf = runCatching {
            client.head(url) { kopfzeilen(this, app) }
                .let { laenge(it) }
        }.getOrDefault(0L)
        if (kopf > 0L) return@withContext kopf
        runCatching {
            val antwort = client.get(url) {
                kopfzeilen(this, app)
                header("Range", "bytes=0-0")
            }
            val kanal = antwort.bodyAsChannel()
            if (!antwort.status.isSuccess() || antwort.status != HttpStatusCode.PartialContent) {
                kanal.cancel(CancellationException("kein Bereich"))
                return@runCatching 0L
            }
            val bereich = antwort.headers[HttpHeaders.ContentRange].orEmpty()
            val totale = bereich.substringAfter('/').trim().toLongOrNull() ?: 0L
            kanal.cancel(CancellationException("nur Kopfzeile gelesen"))
            totale
        }.getOrDefault(0L)
    }

    private fun einreihen(context: Context, antrag: OfflineAntrag) {
        if (warteschlange.value.none { it.videoId == antrag.videoId }) {
            warteschlange.value = warteschlange.value + antrag
        }
        planen(context)
    }

    private fun planen(context: Context) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch { verarbeite(context.applicationContext) }
        }
    }

    private suspend fun verarbeite(context: Context) {
        try {
            while (true) {
                val antrag = warteschlange.value.firstOrNull() ?: break
                warteschlange.value = warteschlange.value.filterNot { it.videoId == antrag.videoId }
                try {
                    ladeHerunter(context, antrag)
                } catch (e: Abbruch) {
                    verworfen.remove(antrag.videoId)
                    Log.i(TAG, "Download verworfen ${antrag.videoId}")
                    TikTokOfflineStore.fortschrittWeg(antrag.videoId)
                    mldung(context, "Download abgebrochen", antrag.title, -1, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Download ${antrag.videoId} fehlgeschlagen: ${e.message}")
                    TikTokOfflineStore.markiere(
                        context,
                        antrag.videoId,
                        TikTokOfflineStore.STATE_ERROR,
                        e.message ?: "Download fehlgeschlagen"
                    )
                    TikTokOfflineStore.fortschrittWeg(antrag.videoId)
                    mldung(context, "Download fehlgeschlagen", antrag.title, -1, false)
                }
                autoFuellen(context)
            }
        } finally {
            synchronized(this) { worker = null }
            if (warteschlange.value.isNotEmpty()) planen(context)
        }
    }

    private suspend fun autoFuellen(context: Context) {
        val app = context.applicationContext
        if (!TikTokOfflineStore.autoAktiv(app)) return
        if (warteschlange.value.isNotEmpty()) return
        if (TikTokOfflineStore.fortschritt.value.isNotEmpty()) return
        if (HandyLampClient.isCellularOnly(app)) {
            Log.i(TAG, "Auto wartet: nur mobiles Netz")
            return
        }
        val limit = TikTokOfflineStore.autoLimit(app)
        if (limit <= 0) return
        val zeilen = runCatching {
            TikTokOfflineDatabase.get(app).videoDao().byState(TikTokOfflineStore.STATE_PENDING)
        }.getOrDefault(emptyList())
        if (zeilen.isEmpty()) return
        val frei = TikTokOfflineStore.frei(app)
        if (frei <= 0L) {
            Log.w(TAG, "Auto gestoppt: kein Speicher mehr")
            return
        }
        var genutzt = 0
        for (video in zeilen) {
            if (genutzt >= limit) break
            if (video.streamUrl.isEmpty()) continue
            if (video.sizeBytes > 0L && frei - video.sizeBytes < TikTokOfflineStore.SCHRITT_BYTES) break
            einreihen(
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
            genutzt++
            Log.i(TAG, "Auto lädt ${video.videoId} ${video.title.take(40)}")
        }
    }

    private suspend fun ladeHerunter(context: Context, antrag: OfflineAntrag) {
        val app = context.applicationContext
        verworfen.remove(antrag.videoId)
        val kandidaten = antrag.urls.filter { it.isNotBlank() }
        if (kandidaten.isEmpty()) {
            TikTokOfflineStore.markiere(
                app,
                antrag.videoId,
                TikTokOfflineStore.STATE_ERROR,
                "keine Video-URL"
            )
            return
        }
        val video = TikTokOfflineStore.get(app, antrag.videoId)
            ?: TikTokOfflineStore.registriere(app, antrag)
        val ziel = TikTokOfflineStore.datei(app, video)
        val teil = TikTokOfflineStore.teilDatei(app, video)
        if (ziel.exists() && ziel.length() > 0L) {
            Log.i(TAG, "bereits vorhanden ${antrag.videoId} ${ziel.length()}")
            TikTokOfflineStore.markiere(app, antrag.videoId, TikTokOfflineStore.STATE_DONE, "", ziel.length())
            TikTokOfflineStore.fortschrittWeg(antrag.videoId)
            return
        }
        var gewaehlt = kandidaten.first()
        var kleinste = 0L
        for (url in kandidaten) {
            val groesse = groesse(app, url)
            if (groesse <= 0L) continue
            if (kleinste == 0L || groesse < kleinste) {
                kleinste = groesse
                gewaehlt = url
            }
        }
        val beginn = if (teil.exists()) teil.length() else 0L
        Log.i(
            TAG,
            "lade ${antrag.videoId} ab $beginn Bytes, Kandidaten=${kandidaten.size}, vorab=$kleinste"
        )
        TikTokOfflineStore.markiere(app, antrag.videoId, TikTokOfflineStore.STATE_DOWNLOADING)
        var antwort = client.get(gewaehlt) {
            kopfzeilen(this, app)
            if (beginn > 0L) header("Range", "bytes=$beginn-")
        }
        if (antwort.status.value == 403) {
            Log.i(TAG, "403 bei ${antrag.videoId}, Versuch mit App-Kennung")
            antwort.bodyAsChannel().cancel(CancellationException("403"))
            antwort = client.get(gewaehlt) {
                header("User-Agent", APP_USER_AGENT)
                header("Referer", REFERER)
                if (beginn > 0L) header("Range", "bytes=$beginn-")
            }
        }
        val fortsetzen = beginn > 0L && antwort.status == HttpStatusCode.PartialContent
        if (!antwort.status.isSuccess() && !fortsetzen) {
            val code = antwort.status.value
            antwort.bodyAsChannel().cancel(CancellationException("HTTP $code"))
            throw IllegalStateException("HTTP $code")
        }
        val erwartet = beginn + laenge(antwort)
        if (beginn > 0L && !fortsetzen) teil.delete()
        val kanal = antwort.bodyAsChannel()
        var geladen = if (fortsetzen) beginn else 0L
        var stand = 0L
        var letzteMeldung = 0L
        FileOutputStream(teil, fortsetzen).use { dateiStrom ->
            val puffer = ByteArray(PUFFER)
            TikTokOfflineStore.fortschrittSetzen(antrag.videoId, anteil(geladen, erwartet))
            while (true) {
                if (verworfen.contains(antrag.videoId)) throw Abbruch()
                val gelesen = kanal.readAvailable(puffer, 0, puffer.size)
                if (gelesen < 0) break
                if (gelesen == 0) {
                    delay(20)
                    continue
                }
                dateiStrom.write(puffer, 0, gelesen)
                geladen += gelesen
                if (geladen - stand >= SCHRITT_MELDUNG) {
                    stand = geladen
                    val prozent = anteil(geladen, erwartet)
                    TikTokOfflineStore.fortschrittSetzen(antrag.videoId, prozent)
                    val jetzt = System.currentTimeMillis()
                    if (jetzt - letzteMeldung >= MELDUNG_ALLE_MS) {
                        letzteMeldung = jetzt
                        mldung(context, "Lade Video", antrag.title, prozent, true, erwartet, geladen)
                    }
                }
            }
            dateiStrom.flush()
        }
        kanal.cancel(CancellationException("Download beendet"))
        if (erwartet > 0L && teil.length() < erwartet) {
            val ist = teil.length()
            Log.w(TAG, "unvollständig ${antrag.videoId} $ist von $erwartet")
            throw IllegalStateException("unvollständig ($ist von $erwartet Bytes)")
        }
        if (!teil.renameTo(ziel)) {
            teil.copyTo(ziel, overwrite = true)
            teil.delete()
        }
        val groesse = ziel.length()
        TikTokOfflineStore.markiere(app, antrag.videoId, TikTokOfflineStore.STATE_DONE, "", groesse)
        TikTokOfflineStore.fortschrittWeg(antrag.videoId)
        TikTokOfflineStore.raeumeAuf(app)
        mldung(context, "Video gespeichert", antrag.title, 100, false, groesse, groesse)
    }

    private fun anteil(geladen: Long, erwartet: Long): Int =
        if (erwartet > 0L) ((geladen * 100L) / erwartet).toInt().coerceIn(0, 100) else 0

    private fun laenge(antwort: HttpResponse): Long =
        antwort.headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L

    private fun kopfzeilen(builder: HttpRequestBuilder, context: Context) {
        builder.header("Cookie", TikTokCookieRepository.cachedCookie(context))
        builder.header("User-Agent", USER_AGENT)
        builder.header("Referer", REFERER)
        builder.header("Accept", "*/*")
    }

    private fun mldung(
        context: Context,
        titel: String,
        text: String,
        prozent: Int,
        laeuft: Boolean,
        gesamt: Long = 0L,
        aktuell: Long = 0L
    ) {
        runCatching { kanalAnlegen(context) }
        val baue = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (laeuft) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(titel)
            .setContentText(text)
            .setOngoing(laeuft)
            .setAutoCancel(!laeuft)
            .setOnlyAlertOnce(true)
        if (laeuft) {
            baue.setProgress(100, prozent, gesamt <= 0L)
            if (gesamt > 0L && aktuell > 0L) {
                baue.setContentText("$text · $prozent % · ${mb(aktuell)} von ${mb(gesamt)}")
            }
        }
        runCatching { tNotify(context, NOTIFY_ID, baue.build()) }
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