package com.tabslify.tabs

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.tabslify.core.logging.Log
import android.view.Surface
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val STREAMD_TAG = "CLOUDSA"
private const val PROTOKOLL_VERSION = 2
private const val MSG_CONFIG = 1
private const val MSG_FRAME = 2
private const val MSG_INPUT = 3
private const val MSG_QUALITY = 4
private const val MSG_CURSOR = 5
private const val MSG_PING = 6
private const val MSG_PONG = 7
private const val MSG_CLIPBOARD = 8
private const val MSG_CLIPBOARD_SET = 9
private const val MSG_AUDIO = 10
private const val MSG_AUTH = 11
private const val MSG_TEXT = 12
private const val MSG_ERROR = 14
private const val MSG_VIEW = 15
private const val MSG_AUDIO_STATE = 16
private const val MSG_BYE = 17
private const val MSG_KEYFRAME = 18
private const val FLAG_KEY = 1
private const val EINGABE_ZEIGER = 0
private const val EINGABE_TASTE = 1
private const val EINGABE_RAD = 2
private const val EINGABE_KNOPF = 3

data class StreamdConfig(
    val breite: Int,
    val hoehe: Int,
    val fps: Int,
    val bitrateKbit: Int,
    val codec: Int = 1
)

data class StreamdStatistik(
    val fps: Double = 0.0,
    val kbit: Double = 0.0,
    val rttMs: Long = -1,
    val breite: Int = 0,
    val hoehe: Int = 0,
    val codec: Int = 1,
    val bytes: Long = 0
)

private data class Ausgang(val typ: Int, val daten: ByteArray)

class StreamdClient(
    private val host: String,
    private val port: Int = 48500,
    private val qualitaetHoehe: Int = 0,
    private val codecWunsch: Int = 0,
    private val fpsWunsch: Int = 0,
    private val bitrateWunsch: Int = 0,
    private val authCode: () -> String? = { null }
) : Closeable {

    private var socket: Socket? = null
    private val schreibSperre = Any()
    @Volatile private var ausgang: DataOutputStream? = null
    @Volatile private var laeuft = false
    private var arbeiter: Thread? = null
    private var schreiber: Thread? = null
    private var pinger: Thread? = null
    private var statThread: Thread? = null
    private val sendeschlange = LinkedBlockingQueue<Ausgang>()
    private val bilder = AtomicLong()
    private val bytesGelesen = AtomicLong()
    @Volatile private var letzteAnsicht = -1
    @Volatile private var letzterAudioZustand = -1
    @Volatile private var letzteKeyframeAnfrage = 0L

    @Volatile private var rtt: Long = -1

    @Volatile
    var config: StreamdConfig? = null
        private set

    @Volatile
    var statistik: StreamdStatistik = StreamdStatistik()
        private set

    fun starten(
        surface: Surface,
        onConfig: (StreamdConfig) -> Unit,
        onFehler: (String) -> Unit,
        onCursor: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
        onClipboard: (String) -> Unit = {},
        onAudio: (ByteArray) -> Unit = {},
        onBild: () -> Unit = {}
    ) {
        val alt = arbeiter
        if (alt?.isAlive == true) {
            runCatching { alt.join(600) }
            if (alt.isAlive) return
        }
        laeuft = true
        sendeschlange.clear()
        bilder.set(0)
        bytesGelesen.set(0)
        rtt = -1
        letzteAnsicht = -1
        letzterAudioZustand = -1
        letzteKeyframeAnfrage = 0L
        schreiberStarten()
        statistikStarten()
        arbeiter = Thread({ arbeite(surface, onConfig, onFehler, onCursor, onClipboard, onAudio, onBild) }, "streamd-client").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun freieHoehe(): Int {
        val h = if (qualitaetHoehe > 0) qualitaetHoehe else 1080
        return if (h >= 1080) 1080 else 720
    }

    private fun arbeite(
        surface: Surface,
        onConfig: (StreamdConfig) -> Unit,
        onFehler: (String) -> Unit,
        onCursor: (Float, Float, Boolean) -> Unit,
        onClipboard: (String) -> Unit,
        onAudio: (ByteArray) -> Unit,
        onBild: () -> Unit
    ) {
        var codec: MediaCodec? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 6000)
            s.soTimeout = 15000
            socket = s
            val aus = DataOutputStream(s.getOutputStream())
            ausgang = aus
            val eingang = DataInputStream(s.getInputStream())

            authCode()?.let { code ->
                schreibeKopf(aus, MSG_AUTH, 0, 0, code.toByteArray(Charsets.UTF_8))
            }
            val wunsch = ByteBuffer.allocate(16)
                .putInt(freieHoehe())
                .putInt(codecWunsch)
                .putInt(if (fpsWunsch in 5..60) fpsWunsch else 0)
                .putInt(if (bitrateWunsch in 600..12000) bitrateWunsch else 0)
                .array()
            schreibeKopf(aus, MSG_QUALITY, 0, 0, wunsch)
            aus.flush()

            val kopf = ByteArray(16)
            eingang.readFully(kopf)
            pruefeKopf(kopf)
            val typ = kopf[5].toInt() and 0xff
            val laenge = leseLaenge(kopf)
            val nutz = ByteArray(laenge)
            eingang.readFully(nutz)
            if (typ == MSG_ERROR) {
                throw IOException(String(nutz, Charsets.UTF_8).ifBlank { "Verbindung abgelehnt" })
            }
            if (typ != MSG_CONFIG || nutz.size < 16) throw IOException("Erwartet Config, bekam $typ")
            val cfg = StreamdConfig(
                breite = leseInt(nutz, 0),
                hoehe = leseInt(nutz, 4),
                fps = leseInt(nutz, 8),
                bitrateKbit = leseInt(nutz, 12),
                codec = if (nutz.size >= 20) leseInt(nutz, 16) else 1
            )
            config = cfg
            onConfig(cfg)
            Log.i(STREAMD_TAG, "[STREAM] verbunden: ${cfg.breite}x${cfg.hoehe} @ ${cfg.fps} fps, ${cfg.bitrateKbit} kbit/s, Codec ${cfg.codec}")

            val format = MediaFormat.createVideoFormat(
                if (cfg.codec == 2) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                cfg.breite,
                cfg.hoehe
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val decoder = try {
                MediaCodec.createDecoderByType(
                    if (cfg.codec == 2) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                )
            } catch (e: Exception) {
                throw IOException(
                    if (cfg.codec == 2) "CODEC_HEVC" else (e.message ?: "Decoder konnte nicht erstellt werden"),
                    e
                )
            }
            codec = decoder
            try {
                decoder.configure(format, surface, null, 0)
                decoder.start()
            } catch (e: Exception) {
                runCatching { decoder.release() }
                codec = null
                throw IOException(
                    if (cfg.codec == 2) "CODEC_HEVC" else (e.message ?: "Decoder-Konfiguration fehlgeschlagen"),
                    e
                )
            }

            pingerStarten()
            val info = MediaCodec.BufferInfo()
            val daten = ByteArray(1 shl 20)
            var gestartet = false
            val start = System.currentTimeMillis()

            while (laeuft) {
                eingang.readFully(kopf)
                pruefeKopf(kopf)
                val art = kopf[5].toInt() and 0xff
                val flags = kopf[6].toInt() and 0xff
                val len = leseLaenge(kopf)
                val pts = leseInt(kopf, 12)
                if (len > daten.size) throw IOException("Frame zu gross: $len")
                eingang.readFully(daten, 0, len)
                bytesGelesen.addAndGet(len.toLong())
                when (art) {
                    MSG_CURSOR -> {
                        if (len >= 5) {
                            val sichtbar = daten[0].toInt() != 0
                            val x = ((daten[1].toInt() and 0xff) shl 8) or (daten[2].toInt() and 0xff)
                            val y = ((daten[3].toInt() and 0xff) shl 8) or (daten[4].toInt() and 0xff)
                            onCursor(x / 65535f, y / 65535f, sichtbar)
                        }
                        continue
                    }
                    MSG_CLIPBOARD -> {
                        if (len > 0) onClipboard(String(daten, 0, len, Charsets.UTF_8))
                        continue
                    }
                    MSG_AUDIO -> {
                        if (len > 0) onAudio(daten.copyOf(len))
                        continue
                    }
                    MSG_PONG -> {
                        if (len >= 8) {
                            val ts = ByteBuffer.wrap(daten, 0, 8).long
                            rtt = SystemClock.elapsedRealtime() - ts
                        }
                        continue
                    }
                    MSG_ERROR -> {
                        if (len > 0) throw IOException(String(daten, 0, len, Charsets.UTF_8))
                        continue
                    }
                    MSG_FRAME -> Unit
                    else -> continue
                }

                val key = flags and FLAG_KEY != 0
                if (!gestartet) {
                    if (!key) continue
                    gestartet = true
                }

                var eingabeIndex = -1
                var versuche = 0
                while (eingabeIndex < 0 && versuche < 20 && laeuft) {
                    eingabeIndex = decoder.dequeueInputBuffer(10_000)
                    versuche++
                }
                if (eingabeIndex >= 0) {
                    val pufferRef = decoder.getInputBuffer(eingabeIndex)
                    if (pufferRef != null && len <= pufferRef.capacity()) {
                        pufferRef.clear()
                        pufferRef.put(daten, 0, len)
                        val codecFlags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        decoder.queueInputBuffer(eingabeIndex, 0, len, pts.toLong() * 1000L, codecFlags)
                    } else {
                        decoder.queueInputBuffer(eingabeIndex, 0, 0, 0, 0)
                        keyframeAnfordern()
                        Log.w(STREAMD_TAG, "[STREAM] Frame passt nicht in den Decoder-Puffer: $len")
                    }
                } else {
                    keyframeAnfordern()
                    Log.w(STREAMD_TAG, "[STREAM] Kein Decoder-Puffer frei, Keyframe angefordert")
                }

                while (true) {
                    val ausgabeIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (ausgabeIndex >= 0) {
                        decoder.releaseOutputBuffer(ausgabeIndex, true)
                        bilder.incrementAndGet()
                        onBild()
                    } else if (ausgabeIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(STREAMD_TAG, "[STREAM] Decoder-Format: ${decoder.outputFormat}")
                    } else {
                        break
                    }
                }
                val sekunden = (System.currentTimeMillis() - start) / 1000.0
                if (sekunden > 0.5 && bilder.get() % 300L == 0L) {
                    Log.i(STREAMD_TAG, "[STREAM] ${bilder.get()} Bilder")
                }
            }
        } catch (e: Exception) {
            if (laeuft) {
                Log.w(STREAMD_TAG, "[STREAM] Fehler: ${e.message}")
                onFehler(e.message ?: "Verbindungsfehler")
            }
        } finally {
            laeuft = false
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            ausgang = null
        }
    }

    private fun schreibeKopf(aus: DataOutputStream, typ: Int, flags: Int, pts: Int, nutz: ByteArray) {
        val kopf = ByteArray(16)
        kopf[0] = 'T'.code.toByte()
        kopf[1] = 'S'.code.toByte()
        kopf[2] = 'S'.code.toByte()
        kopf[3] = 'T'.code.toByte()
        kopf[4] = PROTOKOLL_VERSION.toByte()
        kopf[5] = typ.toByte()
        kopf[6] = flags.toByte()
        kopf[7] = 0
        kopf[8] = (nutz.size shr 24).toByte()
        kopf[9] = (nutz.size shr 16).toByte()
        kopf[10] = (nutz.size shr 8).toByte()
        kopf[11] = nutz.size.toByte()
        kopf[12] = (pts shr 24).toByte()
        kopf[13] = (pts shr 16).toByte()
        kopf[14] = (pts shr 8).toByte()
        kopf[15] = pts.toByte()
        aus.write(kopf)
        aus.write(nutz)
    }

    private fun schreiberStarten() {
        if (schreiber?.isAlive == true) return
        schreiber = Thread({
            while (laeuft) {
                val eintrag = try {
                    sendeschlange.poll(300, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                val aus = ausgang ?: continue
                try {
                    synchronized(schreibSperre) {
                        schreibeKopf(aus, eintrag.typ, 0, 0, eintrag.daten)
                        aus.flush()
                    }
                } catch (e: IOException) {
                    Log.w(STREAMD_TAG, "[STREAM] Senden fehlgeschlagen: ${e.message}")
                }
            }
        }, "streamd-sender").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun pingerStarten() {
        if (pinger?.isAlive == true) return
        pinger = Thread({
            while (laeuft) {
                val ts = SystemClock.elapsedRealtime()
                senden(MSG_PING, ByteBuffer.allocate(8).putLong(ts).array())
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "streamd-ping").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun statistikStarten() {
        if (statThread?.isAlive == true) return
        statThread = Thread({
            var letzteBilder = 0L
            var letzteBytes = 0L
            var letzteZeit = SystemClock.elapsedRealtime()
            while (laeuft) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                val jetzt = SystemClock.elapsedRealtime()
                val dt = (jetzt - letzteZeit) / 1000.0
                val cfg = config
                statistik = StreamdStatistik(
                    fps = if (dt > 0) (bilder.get() - letzteBilder) / dt else 0.0,
                    kbit = if (dt > 0) (bytesGelesen.get() - letzteBytes) * 8.0 / 1000.0 / dt else 0.0,
                    rttMs = rtt,
                    breite = cfg?.breite ?: 0,
                    hoehe = cfg?.hoehe ?: 0,
                    codec = cfg?.codec ?: 1,
                    bytes = bytesGelesen.get()
                )
                letzteBilder = bilder.get()
                letzteBytes = bytesGelesen.get()
                letzteZeit = jetzt
            }
        }, "streamd-stats").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun senden(typ: Int, nutz: ByteArray) {
        if (!laeuft) return
        if (sendeschlange.size > 96) sendeschlange.clear()
        sendeschlange.offer(Ausgang(typ, nutz))
    }

    private fun eingabe(nutz: ByteArray) = senden(MSG_INPUT, nutz)

    fun zeiger(x: Float, y: Float) {
        val px = (x.coerceIn(0f, 1f) * 65535f).toInt()
        val py = (y.coerceIn(0f, 1f) * 65535f).toInt()
        eingabe(byteArrayOf(EINGABE_ZEIGER.toByte(), (px shr 8).toByte(), px.toByte(), (py shr 8).toByte(), py.toByte()))
    }

    fun knopf(knopf: Int, gedrueckt: Boolean) {
        eingabe(byteArrayOf(EINGABE_KNOPF.toByte(), knopf.toByte(), (if (gedrueckt) 1 else 0).toByte()))
    }

    fun rad(delta: Int) {
        val d = delta.coerceIn(-32768, 32767)
        eingabe(byteArrayOf(EINGABE_RAD.toByte(), (d shr 8).toByte(), d.toByte()))
    }

    fun taste(androidKeyCode: Int, gedrueckt: Boolean) {
        val nutz = ByteArray(6)
        nutz[0] = EINGABE_TASTE.toByte()
        nutz[1] = (androidKeyCode shr 24).toByte()
        nutz[2] = (androidKeyCode shr 16).toByte()
        nutz[3] = (androidKeyCode shr 8).toByte()
        nutz[4] = androidKeyCode.toByte()
        nutz[5] = (if (gedrueckt) 1 else 0).toByte()
        eingabe(nutz)
    }

    fun text(text: String) {
        if (text.isEmpty()) return
        senden(MSG_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    fun clipboardSet(text: String) {
        if (text.isEmpty()) return
        senden(MSG_CLIPBOARD_SET, text.toByteArray(Charsets.UTF_8))
    }

    fun ansicht(zoomProzent: Int) {
        val stufe = zoomProzent.coerceIn(100, 800)
        if (stufe == letzteAnsicht) return
        letzteAnsicht = stufe
        senden(MSG_VIEW, ByteBuffer.allocate(2).putShort(stufe.toShort()).array())
    }

    fun audioAktiv(an: Boolean) {
        val wert = if (an) 1 else 0
        if (wert == letzterAudioZustand) return
        letzterAudioZustand = wert
        senden(MSG_AUDIO_STATE, byteArrayOf(wert.toByte()))
    }

    fun keyframeAnfordern() {
        val jetzt = SystemClock.elapsedRealtime()
        if (jetzt - letzteKeyframeAnfrage < 1000L) return
        letzteKeyframeAnfrage = jetzt
        senden(MSG_KEYFRAME, ByteArray(0))
    }

    private fun pruefeKopf(kopf: ByteArray) {
        if (kopf[0] != 'T'.code.toByte() || kopf[1] != 'S'.code.toByte() ||
            kopf[2] != 'S'.code.toByte() || kopf[3] != 'T'.code.toByte()
        ) {
            throw IOException("Ungueltiger Stream-Kopf")
        }
        if (kopf[4].toInt() != PROTOKOLL_VERSION) throw IOException("Unbekannte Protokollversion ${kopf[4]}")
    }

    private fun leseLaenge(kopf: ByteArray): Int =
        ((kopf[8].toInt() and 0xff) shl 24) or
            ((kopf[9].toInt() and 0xff) shl 16) or
            ((kopf[10].toInt() and 0xff) shl 8) or
            (kopf[11].toInt() and 0xff)

    private fun leseInt(daten: ByteArray, ab: Int): Int =
        ((daten[ab].toInt() and 0xff) shl 24) or
            ((daten[ab + 1].toInt() and 0xff) shl 16) or
            ((daten[ab + 2].toInt() and 0xff) shl 8) or
            (daten[ab + 3].toInt() and 0xff)

    override fun close() {
        laeuft = false
        sendeschlange.clear()
        val aus = ausgang
        if (aus != null) {
            try {
                synchronized(schreibSperre) {
                    schreibeKopf(aus, MSG_BYE, 0, 0, ByteArray(0))
                    aus.flush()
                }
            } catch (_: Exception) {
            }
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
