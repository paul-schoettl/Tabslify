package com.tabslify.tabs.tiktok

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TikTokSignatur {

    const val AID = 1233
    const val LICENSE_ID = 2142840551
    const val SDK_VERSION = "v05.01.02-alpha.7-ov-android"
    const val SDK_VERSION_INT = 83952160
    const val LADON_SCHLUESSEL =
        "ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c"
    const val ARGUS_SCHLUESSEL =
        "fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304"

    private val HEX_STR = intArrayOf(30, 64, 224, 217, 147, 69, 0, 180)
    private val LADON_KEY = hexZuBytes(LADON_SCHLUESSEL)
    private val ARGUS_KEY = hexZuBytes(ARGUS_SCHLUESSEL)
    private val random = SecureRandom()

    fun signiere(
        params: String,
        payload: String,
        cookie: String,
        unix: Long,
        deviceId: String,
        versionName: String,
        deviceType: String,
        osVersion: String,
        channel: String
    ): Map<String, String> = signiere(
        params,
        payload,
        cookie,
        unix,
        deviceId,
        versionName,
        deviceType,
        osVersion,
        channel,
        ByteArray(4) { random.nextInt(256).toByte() },
        random.nextInt(Int.MAX_VALUE)
    )

    fun signiere(
        params: String,
        payload: String,
        cookie: String,
        unix: Long,
        deviceId: String,
        versionName: String,
        deviceType: String,
        osVersion: String,
        channel: String,
        ladonZufall: ByteArray,
        argusZufall: Int
    ): Map<String, String> {
        val stub = stubOf(payload)
        val header = mutableMapOf<String, String>()
        header["x-ss-req-ticket"] = (unix * 1000L).toString()
        header["x-tt-trace-id"] = traceId(deviceId, AID)
        if (payload.isNotEmpty()) header["x-ss-stub"] = stub
        header["x-khronos"] = unix.toString()
        header["x-gorgon"] = gorgon(params, stub, cookie, unix)
        header["x-ladon"] = ladon(unix, LICENSE_ID, AID, ladonZufall)
        header["x-argus"] = argus(
            params = params,
            stub = stub,
            unix = unix,
            deviceId = deviceId,
            versionName = versionName,
            deviceType = deviceType,
            osVersion = osVersion,
            channel = channel,
            randomValue = argusZufall
        )
        return header
    }



    fun gorgon(params: String, stub: String, cookie: String, unix: Long): String {
        val ts = unix.toInt()
        val state = IntArray(20)
        fillValue(state, 0, params)
        fillHex(state, 4, stub)
        fillValue(state, 8, cookie)
        val khronos = Integer.toHexString(ts)
        for (i in 0 until 4) {
            val position = i * 2
            state[16 + i] = if (position + 1 < khronos.length) {
                khronos.substring(position, position + 2).toInt(16)
            } else {
                0
            }
        }
        val kopie = gorgonTabelle()
        val tmp = IntArray(20)
        for (i in 0 until 20) {
            val a = state[i]
            val b = if (i > 0) tmp[i - 1] else 0
            val c = (kopie[i + 1] + b) and 0xFF
            tmp[i] = c
            val d = kopie[c]
            kopie[i + 1] = d
            val e = (d + d) and 0xFF
            val f = kopie[e]
            state[i] = a xor f
        }
        for (i in 0 until 20) {
            val a = state[i]
            val b = nibbleSwap(a)
            val c = state[(i + 1) % 20]
            val d = b xor c
            val e = bitReverse8(d)
            val f = e xor 20
            state[i] = f.inv() and 0xFF
        }
        val ende = state.joinToString("") { "%02x".format(it and 0xFF) }
        return "8404" + "%02x".format(HEX_STR[7]) + "%02x".format(HEX_STR[3]) +
            "%02x".format(HEX_STR[1]) + "%02x".format(HEX_STR[6]) + ende
    }

    private fun fillValue(target: IntArray, ab: Int, text: String) {
        if (text.isEmpty()) return
        val digest = md5(text.toByteArray(Charsets.UTF_8))
        for (i in 0 until 4) target[ab + i] = digest[i].toInt() and 0xFF
    }

    private fun fillHex(target: IntArray, ab: Int, hex: String) {
        if (hex.isEmpty()) return
        for (i in 0 until 4) {
            target[ab + i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16))
        }
    }

    private fun gorgonTabelle(): IntArray {
        val hexZu = IntArray(256) { it }
        var last = -1
        for (i in 0 until 256) {
            val a = if (i == 0) 0 else if (last != -1) last else hexZu[i - 1]
            val b = HEX_STR[i % 8]
            val aValue = if (a == 85 && i != 1 && last != 85) 0 else a
            val c = (aValue + i + b) and 0xFF
            last = if (c < i) c else -1
            hexZu[i] = hexZu[c]
        }
        return hexZu
    }

    fun ladon(khronos: Long, lcId: Int, aid: Int, random: ByteArray): String {
        val daten = "$khronos-$lcId-$aid"
        val md5hex = md5(random + aid.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .toByteArray(Charsets.UTF_8)
        val sizeBytes = daten.toByteArray(Charsets.UTF_8).size
        val aufgerundet = if (sizeBytes % 16 > 0) sizeBytes + (16 - sizeBytes % 16) else sizeBytes
        val inputBytes = ByteArray(aufgerundet)
        val bytes = daten.toByteArray(Charsets.UTF_8)
        bytes.copyInto(inputBytes)
        val fill = 16 - sizeBytes % 16
        for (i in 0 until fill) inputBytes[sizeBytes + i] = fill.toByte()
        val table = ByteArray(272 + 16)
        md5hex.copyInto(table)
        val temp = ArrayDeque<Long>()
        for (i in 0 until 4) temp.addLast(read64(table, i * 8))
        var b0 = temp.removeFirst()
        var b8 = temp.removeFirst()
        for (i in 0 until 0x22) {
            val x9 = b0
            var x8 = rorNeu(b8, 8)
            x8 += x9
            x8 = x8 xor i.toLong()
            temp.addLast(x8)
            x8 = x8 xor rorNeu(x9, 61)
            write64(table, (i + 1) * 8, x8)
            b0 = x8
            b8 = temp.removeFirst()
        }
        val ausgabe = ByteArray(aufgerundet)
        for (block in 0 until aufgerundet / 16) {
            var d0 = read64(inputBytes, block * 16)
            var d1 = read64(inputBytes, block * 16 + 8)
            for (i in 0 until 0x22) {
                val hash = read64(table, i * 8)
                d1 = hash xor (d0 + java.lang.Long.rotateLeft(d1, 56))
                d0 = d1 xor java.lang.Long.rotateLeft(d0, 3)
            }
            write64(ausgabe, block * 16, d0)
            write64(ausgabe, block * 16 + 8, d1)
        }
        val header = ByteArray(4 + ausgabe.size)
        random.copyInto(header, 0, 0, 4)
        ausgabe.copyInto(header, 4)
        return Base64.getEncoder().encodeToString(header)
    }

    private fun argus(
        params: String,
        stub: String,
        unix: Long,
        deviceId: String,
        versionName: String,
        deviceType: String,
        osVersion: String,
        channel: String,
        randomValue: Int
    ): String {
        val bodyHash = sm3(hexZuBytes(stub)).copyOf(6)
        val queryHash = sm3(params.toByteArray(Charsets.UTF_8)).copyOf(6)
        val fields = ByteArrayOutputStream()
        varint(fields, 1, 0x20200929L shl 1)
        varint(fields, 2, 2L)
        varint(fields, 3, randomValue.toLong())
        bytes(fields, 4, AID.toString().toByteArray(Charsets.UTF_8))
        bytes(fields, 5, deviceId.toByteArray(Charsets.UTF_8))
        bytes(fields, 6, LICENSE_ID.toString().toByteArray(Charsets.UTF_8))
        bytes(fields, 7, SDK_VERSION.toByteArray(Charsets.UTF_8))
        bytes(fields, 8, SDK_VERSION.toByteArray(Charsets.UTF_8))
        varint(fields, 9, SDK_VERSION_INT.toLong())
        bytes(fields, 10, ByteArray(8))
        bytes(fields, 11, "android".toByteArray(Charsets.UTF_8))
        varint(fields, 12, unix shl 1)
        bytes(fields, 13, bodyHash)
        bytes(fields, 14, queryHash)
        bytes(fields, 15, counter(unix))
        bytes(fields, 16, deviceId.toByteArray(Charsets.UTF_8))
        bytes(fields, 20, "none".toByteArray(Charsets.UTF_8))
        varint(fields, 21, 738L)
        bytes(fields, 23, device(deviceType, osVersion, channel, versionName))
        varint(fields, 25, 2L)
        val buffered = pkcs7(fields.toByteArray(), 16)
        val keyWords = LongArray(4) { read64(ARGUS_KEY, it * 8) }
        val encrypted = ByteArray(buffered.size)
        for (block in 0 until buffered.size / 16) {
            val pt = LongArray(2) { read64(buffered, block * 16 + it * 8) }
            val ct = simon(pt, keyWords)
            write64(encrypted, block * 16, ct[0])
            write64(encrypted, block * 16 + 8, ct[1])
        }
        val header = byteArrayOf(
            0xF2.toByte(), 0xF7.toByte(), 0xFC.toByte(), 0xFF.toByte(),
            0xF2.toByte(), 0xF7.toByte(), 0xFC.toByte(), 0xFF.toByte()
        ) + encrypted
        val xorPart = xorUndDrehen(header)
        var buffer = byteArrayOf(
            0xA6.toByte(), 0x6E, 0xAD.toByte(), 0x9F.toByte(), 0x77, 0x01, 0xD0.toByte(),
            0x0C, 0x18
        ) + xorPart + byteArrayOf(0x61, 0x6F)
        buffer = pkcs7(buffer, 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(md5(LADON_KEY.copyOf(16)), "AES"),
            IvParameterSpec(md5(LADON_KEY.copyOfRange(16, 32)))
        )
        val end = cipher.doFinal(buffer)
        val ausgabe = ByteArray(2 + end.size)
        ausgabe[0] = 0xF2.toByte()
        ausgabe[1] = 0x81.toByte()
        end.copyInto(ausgabe, 2)
        return Base64.getEncoder().encodeToString(ausgabe)
    }

    private fun counter(unix: Long): ByteArray {
        val inner = ByteArrayOutputStream()
        varint(inner, 1, 85L)
        varint(inner, 2, 85L)
        varint(inner, 3, 85L)
        varint(inner, 5, 85L)
        varint(inner, 6, 170L)
        varint(inner, 7, (unix shl 1) - 310L)
        return inner.toByteArray()
    }

    private fun device(
        deviceType: String,
        osVersion: String,
        channel: String,
        versionName: String
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        bytes(inner, 1, deviceType.toByteArray(Charsets.UTF_8))
        bytes(inner, 2, osVersion.toByteArray(Charsets.UTF_8))
        bytes(inner, 3, channel.toByteArray(Charsets.UTF_8))
        varint(inner, 4, appVersion(versionName))
        return inner.toByteArray()
    }

    fun appVersion(versionName: String): Long {
        val parts = versionName.split(".")
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val hex = Integer.toHexString(patch * 4) +
            Integer.toHexString(minor * 16) +
            Integer.toHexString(major * 4) + "00"
        val filled = hex.padStart(8, '0')
        var value = 0L
        for (character in filled) {
            value = (value shl 4) or character.digitToInt(16).toLong()
        }
        return value shl 1
    }

    fun traceId(deviceId: String, aid: Int): String {
        val deviceHex = deviceId.toLongOrNull()?.let { Integer.toHexString(it.toInt()) } ?: deviceId
        val random = ByteArray(2) { random.nextInt(16).toByte() }
        val spur = deviceHex + random.joinToString("") { "%02x".format(it.toInt() and 0xFF) } +
            "0" + Integer.toHexString(aid)
        return "00-$spur-${spur.take(16)}-01"
    }

    private fun rorNeu(value: Long, count: Int): Long {
        val n = count % 64
        val low = value shl (64 - n)
        val shifted = value ushr n
        return if (shifted != 0L) shifted else low
    }

    fun stubOf(text: String): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(9, true)
        deflater.setInput(raw)
        deflater.finish()
        val aus = ByteArrayOutputStream()
        aus.write(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0x02, 0xFF.toByte()))
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            aus.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        val crc = CRC32()
        crc.update(raw)
        val vier = ByteArray(4)
        for (i in 0 until 4) vier[i] = ((crc.value ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        for (i in 0 until 4) vier[i] = ((raw.size.toLong() ushr (8 * i)) and 0xFF).toByte()
        aus.write(vier)
        return md5(aus.toByteArray()).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun xorUndDrehen(daten: ByteArray): ByteArray {
        val kopie = daten.copyOf()
        val muster = kopie.copyOf(8)
        for (i in 8 until kopie.size) {
            kopie[i] = (kopie[i].toInt() xor muster[i % 8].toInt()).toByte()
        }
        return kopie.reversedArray()
    }

    private fun simon(pt: LongArray, keyInput: LongArray): LongArray {
        val key = LongArray(72)
        keyInput.copyInto(key, 0, 0, 4)
        for (i in 4 until 72) {
            var tmp = java.lang.Long.rotateRight(key[i - 1], 3)
            tmp = tmp xor key[i - 3]
            tmp = tmp xor java.lang.Long.rotateRight(tmp, 1)
            val bit = (0x3DC94C3A046D678BL ushr ((i - 4) % 62)) and 1L
            key[i] = key[i - 4].inv() xor tmp xor bit xor 3L
        }
        var xi = pt[0]
        var xi1 = pt[1]
        for (i in 0 until 72) {
            val marker = xi1
            val f = java.lang.Long.rotateLeft(xi1, 1) and java.lang.Long.rotateLeft(xi1, 8)
            xi1 = xi xor f xor java.lang.Long.rotateLeft(xi1, 2) xor key[i]
            xi = marker
        }
        return longArrayOf(xi, xi1)
    }

    private fun writeVarint(aus: ByteArrayOutputStream, value: Long) {
        var v = value and 0xFFFFFFFFL
        while (v > 0x80L) {
            aus.write((v and 0x7FL).toInt() or 0x80)
            v = v shr 7
        }
        aus.write((v and 0x7FL).toInt())
    }

    private fun writeKey(aus: ByteArrayOutputStream, index: Int, typ: Int) {
        writeVarint(aus, (index shl 3).toLong() or typ.toLong())
    }

    private fun varint(aus: ByteArrayOutputStream, index: Int, value: Long) {
        writeKey(aus, index, 0)
        writeVarint(aus, value)
    }

    private fun bytes(aus: ByteArrayOutputStream, index: Int, value: ByteArray) {
        writeKey(aus, index, 2)
        writeVarint(aus, value.size.toLong())
        aus.write(value)
    }

    private fun pkcs7(daten: ByteArray, block: Int): ByteArray {
        val fill = block - (daten.size % block)
        val ausgabe = ByteArray(daten.size + fill)
        daten.copyInto(ausgabe)
        for (i in 0 until fill) ausgabe[daten.size + i] = fill.toByte()
        return ausgabe
    }

    private fun nibbleSwap(value: Int): Int =
        ((value and 0x0F) shl 4) or ((value shr 4) and 0x0F)

    private fun bitReverse8(value: Int): Int {
        var v = value and 0xFF
        var r = 0
        repeat(8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r and 0xFF
    }

    private fun read64(daten: ByteArray, versatz: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (daten[versatz + i].toLong() and 0xFF)
        }
        return value
    }

    private fun write64(daten: ByteArray, versatz: Int, value: Long) {
        for (i in 0 until 8) {
            daten[versatz + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun md5(daten: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(daten)

    private fun hexZuBytes(hex: String): ByteArray {
        val free = hex.length / 2
        val ausgabe = ByteArray(free)
        for (i in 0 until free) {
            ausgabe[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return ausgabe
    }

    fun sm3(daten: ByteArray): ByteArray {
        val iv = intArrayOf(
            0x7380166FL.toInt(), 0x4914B2B9L.toInt(), 0x172442D7L.toInt(), 0xDA8A0600L.toInt(),
            0xA96F30BCL.toInt(), 0x163138AAL.toInt(), 0xE38DEE4DL.toInt(), 0xB0FB0E4EL.toInt()
        )
        val bitLength = daten.size.toLong() * 8L
        val buffer = ByteArrayOutputStream()
        buffer.write(daten)
        buffer.write(0x80)
        while (buffer.size() % 64 != 56) buffer.write(0)
        for (i in 7 downTo 0) buffer.write(((bitLength ushr (8 * i)) and 0xFF).toInt())
        val blok = buffer.toByteArray()
        val v = iv.copyOf()
        for (block in blok.indices step 64) {
            val w = IntArray(68)
            for (i in 0 until 16) {
                var value = 0
                for (k in 0 until 4) {
                    value = (value shl 8) or (blok[block + i * 4 + k].toInt() and 0xFF)
                }
                w[i] = value
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor Integer.rotateLeft(w[j - 3], 15)) xor
                    Integer.rotateLeft(w[j - 13], 7) xor w[j - 6]
            }
            val w1 = IntArray(64) { w[it] xor w[it + 4] }
            var a = v[0]
            var b = v[1]
            var c = v[2]
            var d = v[3]
            var e = v[4]
            var f = v[5]
            var g = v[6]
            var h = v[7]
            for (j in 0 until 64) {
                val tj = if (j < 16) 2043430169 else 2055708042
                val ss1 = Integer.rotateLeft(
                    Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(tj, j), 7
                )
                val ss2 = ss1 xor Integer.rotateLeft(a, 12)
                val tt1 = (if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)) +
                    d + ss2 + w1[j]
                val tt2 = (if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)) +
                    h + ss1 + w[j]
                d = c
                c = Integer.rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = Integer.rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }
            v[0] = v[0] xor a
            v[1] = v[1] xor b
            v[2] = v[2] xor c
            v[3] = v[3] xor d
            v[4] = v[4] xor e
            v[5] = v[5] xor f
            v[6] = v[6] xor g
            v[7] = v[7] xor h
        }
        val ausgabe = ByteArray(32)
        for (i in 0 until 8) {
            for (k in 3 downTo 0) {
                ausgabe[i * 4 + k] = ((v[i] ushr (8 * (3 - k))) and 0xFF).toByte()
            }
        }
        return ausgabe
    }

    private fun p0(x: Int): Int = x xor Integer.rotateLeft(x, 9) xor Integer.rotateLeft(x, 17)

    private fun p1(x: Int): Int = x xor Integer.rotateLeft(x, 15) xor Integer.rotateLeft(x, 23)
}
