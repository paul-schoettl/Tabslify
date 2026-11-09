package com.tabslify.tabs

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.tabslify.core.logging.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StreamdAudio {

    @Volatile
    private var laeuft = false
    private var arbeiter: Thread? = null
    private val schlange = LinkedBlockingQueue<ByteArray>(96)

    fun starten() {
        if (laeuft) return
        laeuft = true
        val alt = arbeiter
        if (alt?.isAlive == true) return
        arbeiter = Thread({ spielen() }, "streamd-audio").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stoppen() {
        laeuft = false
        schlange.clear()
    }

    fun schreibe(daten: ByteArray) {
        if (!laeuft || daten.isEmpty()) return
        if (schlange.size > 64) schlange.clear()
        schlange.offer(daten)
    }

    private fun spielen() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 2)
            format.setByteBuffer(
                "csd-0",
                ByteBuffer.wrap(
                    byteArrayOf(
                        0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64,
                        0x01, 0x02, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(),
                        0x00, 0x00, 0x00, 0x00, 0x00
                    )
                )
            )
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val minPuffer = AudioTrack.getMinBufferSize(
                48_000,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minPuffer <= 0) throw IllegalStateException("AudioTrack-Puffer $minPuffer")
            val ausgabe = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minPuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = ausgabe
            ausgabe.play()
            Log.i("CLOUDSA", "[AUDIO] Opus-Ausgabe aktiv")

            val info = MediaCodec.BufferInfo()
            while (laeuft) {
                val daten = try {
                    schlange.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                var index = -1
                var versuche = 0
                while (index < 0 && versuche < 10 && laeuft) {
                    index = decoder.dequeueInputBuffer(10_000)
                    versuche++
                }
                if (index >= 0) {
                    val puffer = decoder.getInputBuffer(index)
                    if (puffer != null && daten.size <= puffer.capacity()) {
                        puffer.clear()
                        puffer.put(daten)
                        decoder.queueInputBuffer(index, 0, daten.size, 0, 0)
                    } else {
                        decoder.queueInputBuffer(index, 0, 0, 0, 0)
                    }
                }
                var ausgabeIndex = decoder.dequeueOutputBuffer(info, 0)
                while (ausgabeIndex >= 0) {
                    val puffer = decoder.getOutputBuffer(ausgabeIndex)
                    if (puffer != null && info.size > 0 && laeuft) {
                        puffer.position(info.offset)
                        puffer.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        puffer.get(pcm)
                        ausgabe.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(ausgabeIndex, false)
                    ausgabeIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            Log.w("CLOUDSA", "[AUDIO] ${e.message}")
        } finally {
            laeuft = false
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }
}
