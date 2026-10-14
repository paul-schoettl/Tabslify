package com.tabslify.tabs.tiktok

import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class TikTokMessage(
    val id: String,
    val convId: String,
    val convHash: Long,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isOwn: Boolean,
    val mediaUrl: String = ""
)

data class TikTokPage(
    val messages: List<TikTokMessage>,
    val nextCursor: Long,
    val hasMore: Boolean
)

data class TikTokConversation(
    val convId: String,
    val peerId: String,
    val name: String,
    val lastTimestamp: Long,
    val lastMessage: String
)

data class TikTokFeed(
    val conversations: List<TikTokConversation>,
    val messages: List<TikTokMessage>,
    val selfId: String = "",
    val hasMore: Set<String> = emptySet()
)

object TikTokProto {

    private const val CMD_INIT = 203
    private const val SUB_CMD_INIT = 10001
    private const val CMD_COMBO = 204
    private const val SUB_CMD_COMBO = 10011

    fun buildInitRequest(params: Map<String, String>, deviceId: String, combo: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val clientContext = byteArrayOf(0xDA.toByte(), 0x0C, 0x02, 0x08, 0x00)
        writeVarintField(out, 1, if (combo) CMD_COMBO.toLong() else CMD_INIT.toLong())
        writeVarintField(out, 2, if (combo) SUB_CMD_COMBO.toLong() else SUB_CMD_INIT.toLong())
        writeString(out, 3, "1.8.2")
        writeBytes(out, 4, ByteArray(0))
        writeVarintField(out, 5, 3)
        writeVarintField(out, 6, 0)
        writeString(out, 7, "1132b10:master")
        writeBytes(out, 8, clientContext)
        writeString(out, 9, deviceId)
        writeString(out, 11, "web")
        for ((key, value) in params) {
            val entry = ByteArrayOutputStream()
            writeString(entry, 1, key)
            writeString(entry, 2, value)
            writeBytes(out, 15, entry.toByteArray())
        }
        return out.toByteArray()
    }

    fun status(bytes: ByteArray): String {
        val fields = readFields(bytes)
        return fields.firstOrNull { it.number == 4 && it.value is String }?.value as? String ?: ""
    }

    fun parse(bytes: ByteArray): TikTokFeed {
        val raw = mutableListOf<RawMessage>()
        for (field in readFields(bytes)) {
            if (field.number != 6 || field.value !is ByteArray) continue
            val payload = readFields(field.value)
                .firstOrNull { it.number == CMD_INIT && it.value is ByteArray }?.value as? ByteArray ?: continue
            for (record in readFields(payload)) {
                if (record.number == 1 && record.value is ByteArray) {
                    parseMessage(record.value)?.let { raw += it }
                }
            }
        }
        if (raw.isEmpty()) return TikTokFeed(emptyList(), emptyList())

        val self = mostFrequentUid(raw.map { it.convId })
        val messages = raw
            .map { toMessage(it, self) }
            .sortedBy { it.timestamp }
        val conversations = messages.groupBy { it.convId }
            .map { (convId, list) ->
                val last = list.last()
                TikTokConversation(convId, peerOf(convId, self), "", last.timestamp, last.text)
            }
            .sortedByDescending { it.lastTimestamp }
        return TikTokFeed(conversations, messages, self)
    }

    fun buildConversationRequest(
        params: Map<String, String>,
        deviceId: String,
        convId: String,
        convHash: Long,
        cursor: Long,
        count: Int = 20
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val inner = ByteArrayOutputStream().apply {
            writeString(this, 1, convId)
            writeVarintField(this, 2, 1)
            writeVarintField(this, 3, convHash)
            writeVarintField(this, 4, 1)
            writeVarintField(this, 5, cursor)
            writeVarintField(this, 6, count.toLong())
        }.toByteArray()
        val wrapped = ByteArrayOutputStream().apply { writeBytes(this, 301, inner) }.toByteArray()
        writeVarintField(out, 1, 301)
        writeVarintField(out, 2, 10005)
        writeString(out, 3, "1.8.2")
        writeBytes(out, 4, ByteArray(0))
        writeVarintField(out, 5, 3)
        writeVarintField(out, 6, 0)
        writeString(out, 7, "1132b10:master")
        writeBytes(out, 8, wrapped)
        writeString(out, 9, deviceId)
        writeString(out, 11, "web")
        for ((key, value) in params) {
            val entry = ByteArrayOutputStream().apply {
                writeString(this, 1, key)
                writeString(this, 2, value)
            }.toByteArray()
            writeBytes(out, 15, entry)
        }
        writeVarintField(out, 18, 1)
        return out.toByteArray()
    }

    fun parseConversationPage(bytes: ByteArray, self: String): TikTokPage {
        val raw = mutableListOf<RawMessage>()
        var nextCursor = 0L
        var hasMore = false
        for (field in readFields(bytes)) {
            if (field.number != 6 || field.value !is ByteArray) continue
            val payload = readFields(field.value as ByteArray)
                .firstOrNull { it.number == 301 && it.value is ByteArray }?.value as? ByteArray ?: continue
            for (record in readFields(payload)) {
                when {
                    record.number == 1 && record.value is ByteArray ->
                        parseMessage(record.value as ByteArray)?.let { raw += it }
                    record.number == 2 && record.value is Long -> nextCursor = record.value as Long
                    record.number == 3 && record.value is Long -> hasMore = (record.value as Long) != 0L
                }
            }
        }
        return TikTokPage(raw.map { toMessage(it, self) }.sortedBy { it.timestamp }, nextCursor, hasMore)
    }

    private fun toMessage(raw: RawMessage, self: String): TikTokMessage = TikTokMessage(
        id = raw.id,
        convId = raw.convId,
        convHash = raw.convHash,
        senderId = raw.senderId,
        text = raw.text,
        timestamp = raw.timestamp,
        isOwn = raw.senderId == self,
        mediaUrl = raw.mediaUrl
    )

    private data class RawMessage(
        val id: String,
        val convId: String,
        val convHash: Long,
        val senderId: String,
        val text: String,
        val timestamp: Long,
        val mediaUrl: String
    )

    private fun parseMessage(bytes: ByteArray): RawMessage? {
        val fields = readFields(bytes)
        val convId = stringField(fields, 1) ?: return null
        if (!convId.contains(':')) return null
        val content = contentField(fields) ?: return null
        val obj = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val classified = classify(obj) ?: return null
        val id = longField(fields, 3)?.toString().orEmpty()
        val convHash = longField(fields, 5) ?: 0L
        val senderId = longField(fields, 7)?.toString().orEmpty()
        val timestamp = longField(fields, 10) ?: 0L
        return RawMessage(id, convId, convHash, senderId, classified.first, timestamp, classified.second)
    }

    private fun classify(obj: JSONObject): Pair<String, String>? {
        if (obj.has("refmsg_type") && !obj.has("text")) return null
        val itemId = obj.optString("itemId")
        if (itemId.isNotEmpty()) {
            val cover = firstUrl(obj.optJSONObject("cover_url"))
            val title = obj.optString("content_title").ifEmpty { obj.optString("content_name") }
            val kind = if (obj.optInt("aweType", 0) == 810) "🖼️ TikTok-Foto" else "🎬 TikTok-Video"
            val label = if (title.isNotEmpty()) "$kind — $title" else kind
            return label to cover
        }
        if (obj.has("reference_video")) {
            val cover = firstUrl(obj.optJSONObject("reference_video_cover_url"))
            val caption = obj.optString("text")
            val label = if (caption.isNotEmpty()) "🎬 Video — ${cleanText(caption)}" else "🎬 Video"
            return label to cover
        }
        val rawText = obj.optString("text")
        if (rawText.isNotEmpty()) return cleanText(rawText) to ""
        val roomName = obj.optString("room_owner_name")
        if (roomName.isNotEmpty()) return "🔴 Live: $roomName" to ""
        val tips = obj.optString("tips")
        if (tips.isNotEmpty()) return "ℹ️ ${cleanText(tips)}" to ""
        if (obj.has("hack")) return "👉 Angestupst" to ""
        return null
    }

    private fun firstUrl(container: JSONObject?): String {
        val list = container?.optJSONArray("url_list") ?: return ""
        return if (list.length() > 0) list.optString(0) else ""
    }

    private fun cleanText(raw: String): String {
        var value = raw
        if (value.contains("tenor-gif-embed")) {
            val href = tenorUrl(value)
            value = if (href != null) "🎞️ GIF  $href" else "🎞️ GIF"
        }
        return stripPlaceholders(value)
    }

    private fun tenorUrl(value: String): String? {
        val start = value.indexOf("https://tenor.com/view/")
        if (start < 0) return null
        val end = value.indexOf('"', start)
        return if (end > start) value.substring(start, end) else value.substring(start)
    }

    private fun stripPlaceholders(value: String): String {
        if (!value.contains("{{")) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '{' && index + 1 < value.length && value[index + 1] == '{') {
                val close = value.indexOf("}}", index + 2)
                if (close < 0) {
                    builder.append(value, index, value.length)
                    break
                }
                index = close + 2
            } else {
                builder.append(value[index])
                index++
            }
        }
        return builder.toString().trim()
    }

    private fun mostFrequentUid(convIds: List<String>): String {
        val counts = mutableMapOf<String, Int>()
        for (convId in convIds.distinct()) {
            for (uid in convId.split(':')) {
                if (uid.length >= 5) counts[uid] = (counts[uid] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key.orEmpty()
    }

    private fun peerOf(convId: String, self: String): String =
        convId.split(':').lastOrNull { it != self && it.length >= 5 }.orEmpty()

    private fun stringField(fields: List<Field>, number: Int): String? =
        fields.firstOrNull { it.number == number && it.value is String }?.value as? String

    private fun longField(fields: List<Field>, number: Int): Long? =
        fields.firstOrNull { it.number == number && it.value is Long }?.value as? Long

    private fun contentField(fields: List<Field>): String? {
        val value = fields.firstOrNull { it.number == 8 }?.value ?: return null
        return when (value) {
            is String -> value
            is ByteArray -> String(value, Charsets.UTF_8)
            else -> null
        }
    }

    private data class Field(val number: Int, val value: Any)

    private fun readFields(bytes: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var pos = 0
        while (pos < bytes.size) {
            val key = readVarint(bytes, pos) ?: break
            pos = key.second
            val number = (key.first ushr 3).toInt()
            when (key.first and 0x7) {
                0L -> {
                    val value = readVarint(bytes, pos) ?: break
                    pos = value.second
                    fields += Field(number, value.first)
                }
                1L -> {
                    if (pos + 8 > bytes.size) break
                    pos += 8
                }
                2L -> {
                    val length = readVarint(bytes, pos) ?: break
                    pos = length.second
                    val size = length.first.toInt()
                    if (size < 0 || pos + size > bytes.size) break
                    val chunk = bytes.copyOfRange(pos, pos + size)
                    pos += size
                    fields += Field(number, decodeString(chunk))
                }
                5L -> {
                    if (pos + 4 > bytes.size) break
                    pos += 4
                }
                else -> break
            }
        }
        return fields
    }

    private fun decodeString(chunk: ByteArray): Any {
        val candidate = String(chunk, Charsets.UTF_8)
        return if (candidate.all { it.code in 32..126 || it == '\n' || it == '\t' }) candidate else chunk
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val current = bytes[pos].toInt() and 0xFF
            value = value or ((current and 0x7F).toLong() shl shift)
            pos++
            if (current and 0x80 == 0) return value to pos
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeTag(out, field, 0)
        writeVarint(out, value)
    }

    private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
        writeBytes(out, field, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        writeTag(out, field, 2)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarint(out, ((field shl 3) or wire).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var remaining = value
        while (true) {
            if (remaining and 0x7FL.inv() == 0L) {
                out.write(remaining.toInt())
                return
            }
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }
}
