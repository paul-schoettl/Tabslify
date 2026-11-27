package com.tabslify.tabs.tiktok

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class TikTokMessage(
    val id: String,
    val convId: String,
    val convHash: Long,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isOwn: Boolean,
    val mediaUrl: String = "",
    val mediaKind: String = "",
    val mediaKey: String = "",
    val reactions: List<String> = emptyList(),
    val mediaStream: String = "",
    val mediaDuration: Long = 0L,
    val mediaWave: String = "",
    val mediaVid: String = "",
    val unknownType: String = "",
    val myReaction: String = "",
    val peerReaction: String = "",
    val replyToId: String = "",
    val replyToText: String = "",
    val replyToQuote: String = ""
)

data class TikTokPage(
    val messages: List<TikTokMessage>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val streaks: Map<String, TikTokStreak> = emptyMap()
)

data class TikTokProfile(
    val uid: String,
    val name: String,
    val handle: String,
    val signature: String,
    val avatarUrl: String,
    val following: Boolean
)

data class TikTokConversation(
    val convId: String,
    val peerId: String,
    val name: String,
    val lastTimestamp: Long,
    val lastMessage: String,
    val unread: Int = 0,
    val streak: TikTokStreak = TikTokStreak(),
    val reactions: List<String> = emptyList(),
    val avatarUrl: String = "",
    val handle: String = "",
    val lastKind: String = "",
    val lastIsOwn: Boolean = false
)

data class TikTokConvMeta(
    val convId: String,
    val convType: Int,
    val convShortId: Long,
    val ticket: String,
    val unread: Int = 0,
    val maxIndex: Long = 0L,
    val readIndex: Long = 0L
)

data class TikTokStreak(
    val active: Boolean = false,
    val restorable: Boolean = false,
    val value: Int? = null,
    val changedAt: Long = 0L
)

data class TikTokFeed(
    val conversations: List<TikTokConversation>,
    val messages: List<TikTokMessage>,
    val selfId: String = "",
    val hasMore: Set<String> = emptySet(),
    val convMeta: Map<String, TikTokConvMeta> = emptyMap(),
    val streaks: Map<String, TikTokStreak> = emptyMap()
)

data class TikTokSendResult(
    val statusCode: Int,
    val errorDesc: String,
    val innerStatus: Int,
    val serverMessageId: Long,
    val clientMessageId: String,
    val newTicket: String
)

object TikTokProto {

    private const val CMD_INIT = 203
    private const val SUB_CMD_INIT = 10001
    private const val CMD_COMBO = 204
    private const val SUB_CMD_COMBO = 10011
    private const val CMD_CONVERSATION = 301
    private const val SUB_CMD_CONVERSATION = 10012
    private const val CONV_DIRECTION_OLDER = 1
    private const val CONVERSATION_PAGE_SIZE = 50
    private const val CMD_SEND = 100
    private const val CMD_DELETE = 701
    const val CMD_REACTION = 705
    private const val CMD_RECALL = 702
    private const val CMD_MARK_READ = 2002
    private const val SUB_CMD_MARK_READ = 10000
    private const val BODY_FIELD_MARK_READ = 604
    private const val SEND_MESSAGE_TYPE_TEXT = 7
    private const val STREAK_CMD_NONE = 0
    private const val STREAK_CMD_START = 1
    private const val STREAK_CMD_END = 2
    private const val STREAK_CMD_UPDATE = 3
    private const val FRONTIER_FP_ID = 32
    private const val FRONTIER_SERVICE = 33554513L
    private const val FRONTIER_METHOD = 2
    private const val FRONTIER_AID = 1988
    private const val FRONTIER_APP_KEY = "e252eea4204adef812c32f35a4b80f4b"
    private const val FRONTIER_SALT = "f8a69f1719916z"
    private const val FRONTIER_AUTH_CMD = 10001
    private const val FRONTIER_INDEX_CMD = 10002
    private const val FRONTIER_DEVICE_CMD = 10015
    private const val FRONTIER_DEVICE_SERVICE = 5L
    private const val MESSAGE_TYPE_STREAK_STATUS = 2000L
    private const val KIND_STICKER = "sticker"
    private const val KIND_PHOTO = "photo"
    private const val KIND_VIDEO = "video"
    private const val KIND_VOICE = "voice"
    private const val MAX_BESCHRIEBUNG = 60000
    private const val MAX_VORSCHAU = 24
    private const val MAX_TEXT = 200
    private const val MAX_TIEFE = 8
    private const val MAX_UNTERFELD = 262144

    private const val PROTO_DECRYPT_KEY = 3

    private val SYSTEM_SCENES = setOf(
        "nudge",
        "greeting_card",
        "streak_pet_egg_invitation",
        "PRM_ci_share_feed_invitation",
        "story_reaction"
    )

    private val HEX_ZEICHEN = "0123456789abcdef".toCharArray()

    fun latestStreak(current: TikTokStreak, incoming: TikTokStreak): TikTokStreak {
        val newerFirst = incoming.changedAt >= current.changedAt
        val newer = if (newerFirst) incoming else current
        val older = if (newerFirst) current else incoming
        return newer.copy(value = newer.value ?: older.value)
    }

    fun buildRequest(
        params: Map<String, String>,
        deviceId: String,
        cmd: Int,
        subCmd: Long,
        body: ByteArray,
        wrapperField: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, cmd.toLong())
        writeVarintField(out, 2, subCmd)
        writeString(out, 3, "1.8.2")
        writeBytes(out, 4, ByteArray(0))
        writeVarintField(out, 5, 3)
        writeVarintField(out, 6, 0)
        writeString(out, 7, "1132b10:master")
        if (wrapperField > 0) {
            val wrapped = ByteArrayOutputStream().apply { writeBytes(this, wrapperField, body) }
            writeBytes(out, 8, wrapped.toByteArray())
        } else {
            writeBytes(out, 8, body)
        }
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

    fun buildInitRequest(params: Map<String, String>, deviceId: String, combo: Boolean = false): ByteArray {
        val clientContext = byteArrayOf(0xDA.toByte(), 0x0C, 0x02, 0x08, 0x00)
        return buildRequest(
            params = params,
            deviceId = deviceId,
            cmd = if (combo) CMD_COMBO else CMD_INIT,
            subCmd = if (combo) SUB_CMD_COMBO.toLong() else SUB_CMD_INIT.toLong(),
            body = clientContext,
            wrapperField = 0
        )
    }

    fun buildMarkReadRequest(
        params: Map<String, String>,
        deviceId: String,
        meta: TikTokConvMeta,
        readMessageIndex: Long
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeString(this, 1, meta.convId)
            writeVarintField(this, 2, meta.convShortId)
            writeVarintField(this, 3, meta.convType.toLong())
            writeVarintField(this, 4, readMessageIndex)
        }.toByteArray()
        return buildRequest(params, deviceId, CMD_MARK_READ, SUB_CMD_MARK_READ.toLong(), body, BODY_FIELD_MARK_READ)
    }

    fun status(bytes: ByteArray): String {
        val fields = readFields(bytes)
        return fields.firstOrNull { it.number == 4 && it.value is String }?.value as? String ?: ""
    }

    fun parseResponseUrl(bytes: ByteArray): String {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        if (text.contains("http")) {
            Regex("https?://[^\\s\\\"'<>\\\\]+").find(text)?.let { matches ->
                return matches.value.replace("\\u0026", "&").replace("\\/", "/")
            }
        }
        return alleUrls(bytes).firstOrNull { it.contains("tos-") || it.contains(".m4a") }.orEmpty()
    }

    fun parse(bytes: ByteArray): TikTokFeed {
        val raw = mutableListOf<RawMessage>()
        val metas = mutableMapOf<String, TikTokConvMeta>()
        for ((number, value) in readFields(bytes)) {
            if (number != 6 || value !is ByteArray) continue
            val payload = readFields(value)
                .firstOrNull { it.number == CMD_INIT && it.value is ByteArray }?.value as? ByteArray ?: continue
            for ((number1, value1) in readFields(payload)) {
                when (number1) {
                    1 if value1 is ByteArray ->
                        parseMessage(value1)?.let { raw += it }

                    2 if value1 is ByteArray ->
                        parseConversation(value1)?.let { metas[it.convId] = it }
                }
            }
        }
        val streaks = applyStreakBadges(raw)
        raw.sortedBy { it.timestamp }.forEach { item ->
            val previous = streaks[item.convId] ?: TikTokStreak()
            if (item.streakCmd != STREAK_CMD_NONE) {
                streaks[item.convId] = latestStreak(
                    previous,
                    streakFromCommand(previous, item.streakCmd, item.streakValue, item.timestamp)
                )
            }
            val afterCommand = streaks[item.convId] ?: TikTokStreak()
            if (afterCommand.value == null) {
                streakFromScene(item.scene, item.timestamp)
                    ?.let { streaks[item.convId] = latestStreak(afterCommand, it) }
            }
        }
        val withoutSystem = raw.filter { item ->
            item.streakCmd == STREAK_CMD_NONE && item.badge == null &&
                !(item.silent && item.scene.isNotEmpty() && item.scene !in SYSTEM_SCENES)
        }
        val self = mostFrequentUid(withoutSystem.map { it.convId })
        val visible = withoutSystem.filter { item ->
            item.invisibleFor != self
        }

        if (visible.isEmpty() && metas.isEmpty()) return TikTokFeed(emptyList(), emptyList(), streaks = streaks)

        val messages = visible
            .map { toMessage(it, self) }
            .sortedBy { it.timestamp }
        val conversations = messages.groupBy { it.convId }
            .map { (convId, list) ->
                val last = list.last()
                TikTokConversation(
                    convId = convId,
                    peerId = peerOf(convId, self).ifEmpty { self },
                    name = "",
                    lastTimestamp = last.timestamp,
                    lastMessage = last.text,
                    unread = metas[convId]?.unread ?: 0,
                    streak = streaks[convId] ?: TikTokStreak(),
                    reactions = list.flatMap { it.reactions }.distinct(),
                    lastKind = last.mediaKind,
                    lastIsOwn = last.isOwn
                )
            }
            .sortedByDescending { it.lastTimestamp }
        return TikTokFeed(conversations, messages, self, convMeta = metas, streaks = streaks)
    }

    fun buildReactionRequest(
        params: Map<String, String>,
        deviceId: String,
        convId: String,
        convType: Int,
        convShortId: Long,
        serverMessageId: Long,
        emoji: String,
        add: Boolean
    ): ByteArray {
        val content = ByteArrayOutputStream().apply {
            writeVarintField(this, 1, if (add) 0L else 1L)
            writeString(this, 2, "e:$emoji")
        }.toByteArray()
        val property = ByteArrayOutputStream().apply {
            writeString(this, 1, convId)
            writeVarintField(this, 2, convType.toLong())
            writeVarintField(this, 3, convShortId)
            writeVarintField(this, 4, serverMessageId)
            writeBytes(this, 6, content)
        }.toByteArray()
        return buildRequest(
            params,
            deviceId,
            CMD_REACTION,
            0L,
            ByteArrayOutputStream().apply { writeBytes(this, 1, property) }.toByteArray(),
            CMD_REACTION
        )
    }

    fun buildRecallRequest(
        params: Map<String, String>,
        deviceId: String,
        meta: TikTokConvMeta,
        serverMessageId: Long
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeString(this, 1, meta.convId)
            writeVarintField(this, 2, meta.convShortId)
            writeVarintField(this, 3, meta.convType.toLong())
            writeVarintField(this, 4, serverMessageId)
        }.toByteArray()
        return buildRequest(params, deviceId, CMD_RECALL, 0L, body, CMD_RECALL)
    }

    fun buildDeleteRequest(
        params: Map<String, String>,
        deviceId: String,
        meta: TikTokConvMeta,
        messageId: Long
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeString(this, 1, meta.convId)
            writeVarintField(this, 2, meta.convShortId)
            writeVarintField(this, 3, meta.convType.toLong())
            writeVarintField(this, 4, messageId)
        }.toByteArray()
        return buildRequest(params, deviceId, CMD_DELETE, 0L, body, CMD_DELETE)
    }

    fun buildSendRequest(
        params: Map<String, String>,
        deviceId: String,
        meta: TikTokConvMeta,
        contentJson: String,
        clientMessageId: String
    ): ByteArray {
        val sendBody = ByteArrayOutputStream().apply {
            writeString(this, 1, meta.convId)
            writeVarintField(this, 2, meta.convType.toLong())
            writeVarintField(this, 3, meta.convShortId)
            writeString(this, 4, contentJson)
            writeVarintField(this, 6, SEND_MESSAGE_TYPE_TEXT.toLong())
            writeString(this, 7, meta.ticket)
            writeString(this, 8, clientMessageId)
        }.toByteArray()
        return buildRequest(params, deviceId, CMD_SEND, System.currentTimeMillis(), sendBody, CMD_SEND)
    }

    fun parseSendResponse(bytes: ByteArray): TikTokSendResult {
        var statusCode = -1
        var errorDesc = ""
        var body: ByteArray? = null
        for ((number, value) in readFields(bytes)) {
            when (number) {
                3 if value is Long -> statusCode = value.toInt()
                4 if value is String -> errorDesc = value
                6 if value is ByteArray -> body = value
            }
        }
        var innerStatus = -1
        var serverMessageId = 0L
        var clientMessageId = ""
        var newTicket = ""
        if (body != null) {
            val sendBody = readFields(body)
                .firstOrNull { it.number == CMD_SEND && it.value is ByteArray }?.value as? ByteArray
            if (sendBody != null) {
                val fields = readFields(sendBody)
                innerStatus = longField(fields, 3)?.toInt() ?: -1
                serverMessageId = longField(fields, 1) ?: 0L
                clientMessageId = stringField(fields, 4).orEmpty()
                newTicket = stringField(fields, 9).orEmpty()
            }
        }
        return TikTokSendResult(statusCode, errorDesc, innerStatus, serverMessageId, clientMessageId, newTicket)
    }

    private fun parseConversation(bytes: ByteArray): TikTokConvMeta? {
        val fields = readFields(bytes)
        val convId = stringField(fields, 1) ?: return null
        if (!convId.contains(':')) return null
        val shortId = longField(fields, 2) ?: 0L
        val type = longField(fields, 3)?.toInt() ?: 1
        val ticket = stringField(fields, 4).orEmpty()
        val maxIndex = longField(fields, 11) ?: 0L
        val readIndex = bytesField(fields, 51)?.let { settingInfo ->
            val info = readFields(settingInfo)
            longField(info, 17) ?: longField(info, 16) ?: 0L
        } ?: 0L
        val unread = (maxIndex - readIndex).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return TikTokConvMeta(convId, type, shortId, ticket, unread, maxIndex, readIndex)
    }

    fun statusCode(bytes: ByteArray): Int =
        longField(readFields(bytes), 3)?.toInt() ?: -1

    fun errorDesc(bytes: ByteArray): String = stringField(readFields(bytes), 4).orEmpty()

    fun describeFrame(bytes: ByteArray): String {
        val out = StringBuilder()
        describe(bytes, "", 0, out)
        return out.toString().trimEnd()
    }

    private fun describe(
        bytes: ByteArray,
        path: String,
        depthLevel: Int,
        out: StringBuilder
    ) {
        if (depthLevel > MAX_TIEFE || out.length > MAX_BESCHRIEBUNG) return
        if (bytes.isEmpty()) {
            out.append(path).append(" = leer\n")
            return
        }
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            val unpacked = gunzip(bytes)
            if (unpacked == null) {
                out.append(path).append(" = gzip unlesbar ").append(bytes.size).append("B\n")
                return
            }
            out.append(path).append(" = gzip ").append(unpacked.size).append("B\n")
            describe(unpacked, "$path!gz", depthLevel + 1, out)
            return
        }
        val fields = readFields(bytes)
        if (fields.isEmpty()) {
            out.append(path).append(" = roh ").append(bytes.size).append("B ")
                .append(hexPreview(bytes)).append('\n')
            return
        }
        for (field in fields) {
            if (out.length > MAX_BESCHRIEBUNG) return
            when (val value = field.value) {
                is Long -> out.append(path).append('.').append(field.number)
                    .append(" = ").append(value).append('\n')

                is String -> out.append(path).append('.').append(field.number)
                    .append(" text ").append(textPreview(value)).append('\n')

                is ByteArray -> {
                    val lesbar = lesbarerText(value)
                    if (lesbar != null) {
                        out.append(path).append('.').append(field.number)
                            .append(" text ").append(textPreview(lesbar)).append('\n')
                    } else {
                        out.append(path).append('.').append(field.number)
                            .append(" bytes[").append(value.size).append("]\n")
                        if (value.size > MAX_UNTERFELD) {
                            out.append(path).append('.').append(field.number)
                                .append(" zu large zum Aufschluesseln ")
                                .append(hexPreview(value)).append('\n')
                        } else {
                            describe(value, "$path.${field.number}", depthLevel + 1, out)
                        }
                    }
                }

                else -> out.append(path).append('.').append(field.number)
                    .append(" = ").append(value).append('\n')
            }
        }
    }

    private fun textPreview(text: String): String {
        val flach = text.replace('\n', ' ').replace('\t', ' ').trim()
        if (flach.length <= MAX_TEXT) return flach
        return flach.take(MAX_TEXT) + " …"
    }

    private fun lesbarerText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        var sichtbar = 0
        for (character in text) {
            if (character == '\n' || character == '\t') {
                sichtbar++
            } else if (character.code >= 32 && character.code != 0xFFFD) {
                sichtbar++
            }
        }
        if (sichtbar * 20 < text.length * 19) return null
        return text
    }

    private fun hexPreview(bytes: ByteArray): String {
        val count = minOf(bytes.size, MAX_VORSCHAU)
        val out = StringBuilder(count * 3 + 2)
        for (index in 0 until count) {
            if (index > 0) out.append(' ')
            val value = bytes[index].toInt() and 0xFF
            out.append(HEX_ZEICHEN[value ushr 4])
            out.append(HEX_ZEICHEN[value and 0x0F])
        }
        if (bytes.size > count) out.append(" …")
        return out.toString()
    }

    fun buildConversationRequest(
        params: Map<String, String>,
        deviceId: String,
        meta: TikTokConvMeta,
        anchorIndex: Long,
        count: Int = CONVERSATION_PAGE_SIZE
    ): ByteArray {
        val inner = ByteArrayOutputStream().apply {
            writeString(this, 1, meta.convId)
            writeVarintField(this, 2, meta.convType.toLong())
            writeVarintField(this, 3, meta.convShortId)
            writeVarintField(this, 4, CONV_DIRECTION_OLDER.toLong())
            writeVarintField(this, 5, anchorIndex)
            writeVarintField(this, 6, count.toLong())
        }.toByteArray()
        return buildRequest(params, deviceId, CMD_CONVERSATION, SUB_CMD_CONVERSATION.toLong(), inner, CMD_CONVERSATION)
    }

    fun parseConversationPage(bytes: ByteArray, self: String): TikTokPage {
        val raw = mutableListOf<RawMessage>()
        var nextCursor = 0L
        var hasMore = false
        for ((number, value) in readFields(bytes)) {
            if (number != 6 || value !is ByteArray) continue
            val payload = readFields(value)
                .firstOrNull { it.number == 301 && it.value is ByteArray }?.value as? ByteArray ?: continue
            for ((number1, value1) in readFields(payload)) {
                when (number1) {
                    1 if value1 is ByteArray -> parseMessage(value1)?.let { raw += it }
                    2 if value1 is Long -> nextCursor = value1
                    3 if value1 is Long -> hasMore = value1 != 0L
                }
            }
        }
        val streaks = applyStreakBadges(raw)
        raw.sortedBy { it.timestamp }.forEach { item ->
            val previous = streaks[item.convId] ?: TikTokStreak()
            if (item.streakCmd != STREAK_CMD_NONE) {
                streaks[item.convId] = latestStreak(
                    previous,
                    streakFromCommand(previous, item.streakCmd, item.streakValue, item.timestamp)
                )
            }
            val afterCommand = streaks[item.convId] ?: TikTokStreak()
            if (afterCommand.value == null) {
                streakFromScene(item.scene, item.timestamp)
                    ?.let { streaks[item.convId] = latestStreak(afterCommand, it) }
            }
        }
        val visible = raw.filter { item ->
            item.streakCmd == STREAK_CMD_NONE && item.badge == null &&
                !(item.silent && item.scene.isNotEmpty() &&
                    item.scene !in SYSTEM_SCENES) &&
                item.invisibleFor != self
        }
        return TikTokPage(
            visible.map { toMessage(it, self) }.sortedBy { it.timestamp },
            nextCursor,
            hasMore,
            streaks
        )
    }

    private fun toMessage(raw: RawMessage, self: String): TikTokMessage {
        val mine = raw.reactionUsers.entries
            .firstOrNull { (_, users) -> self.isNotEmpty() && users.any { it == self } }
            ?.key.orEmpty()
        val peer = raw.reactionUsers.entries
            .firstOrNull { (_, users) -> users.any { it.isNotEmpty() && it != self } }
            ?.key.orEmpty()
        return TikTokMessage(
            id = raw.id,
            convId = raw.convId,
            convHash = raw.convHash,
            senderId = raw.senderId,
            text = raw.text,
            timestamp = raw.timestamp,
            isOwn = raw.senderId == self,
            mediaUrl = raw.mediaUrl,
            mediaKind = raw.mediaKind,
            mediaKey = raw.mediaKey,
            mediaVid = raw.mediaVid,
            reactions = raw.reactions,
            mediaStream = raw.mediaStream,
            mediaDuration = raw.mediaDuration,
            mediaWave = raw.mediaWave,
            unknownType = raw.unknownType,
            myReaction = mine,
            peerReaction = peer,
            replyToId = raw.replyToId,
            replyToText = raw.replyToText,
            replyToQuote = raw.replyToQuote
        )
    }

    private data class RawMessage(
        val id: String,
        val convId: String,
        val convHash: Long,
        val senderId: String,
        val text: String,
        val timestamp: Long,
        val mediaUrl: String,
        val mediaKind: String,
        val mediaKey: String,
        val streakCmd: Int,
        val streakValue: Int,
        val scene: String,
        val silent: Boolean,
        val badge: StreakBadge? = null,
        val invisibleFor: String = "",
        val reactions: List<String> = emptyList(),
        val mediaStream: String = "",
        val mediaDuration: Long = 0L,
        val mediaWave: String = "",
        val mediaVid: String = "",
        val unknownType: String = "",
        val reactionUsers: Map<String, List<String>> = emptyMap(),
        val replyToId: String = "",
        val replyToText: String = "",
        val replyToQuote: String = ""
    )

    private data class StreakBadge(
        val days: Int,
        val active: Boolean,
        val restorable: Boolean,
        val scene: String,
        val level: Int? = null
    )

    private data class Classified(
        val text: String,
        val mediaUrl: String = "",
        val mediaKind: String = "",
        val mediaKey: String = "",
        val mediaStream: String = "",
        val mediaDuration: Long = 0L,
        val mediaWave: String = "",
        val mediaVid: String = "",
        val unknownType: String = "",
        val myReaction: String = "",
        val peerReaction: String = ""
    )

    private fun parseMessage(bytes: ByteArray): RawMessage? {
        val fields = readFields(bytes)
        val convId = stringField(fields, 1) ?: return null
        if (!convId.contains(':')) return null
        if (isDone(fields)) return null
        val response = responseFromF18(fields)
        val contentPb = bytesField(fields, 20)
        val badge = streakBadgeFrom(bytes)
        val scene = stringField(fields, 21).orEmpty().ifEmpty { badge?.scene.orEmpty() }
        val streak = contentPb?.let { parseStreak(it) } ?: (0 to 0)
        val content = contentField(fields)
        val obj = content?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (obj == null && streak.first == 0 && scene.isEmpty() && badge == null) return null
        val medien = medienAusF24(bytesField(fields, 24))
        val vid = bytesField(fields, 24)?.let { stringField(readFields(it), 1) }.orEmpty()
        val card = contentPb?.let { parseContentPb(it, vid) }
        val messageType = longField(fields, 12) ?: 0L
        val rohCard = if (scene == "nudge") {
            Classified(nudgeText(obj, contentPb, card))
        } else if (obj != null) {
            classify(
                obj = obj,
                card = card,
                scene = scene,
                pbFields = contentPb?.let { describeFields(it) }.orEmpty(),
                messageType = messageType
            ) ?: Classified("")
        } else {
            Classified("")
        }
        val classified = when {
            rohCard.mediaKind == KIND_VOICE && medien.play.isNotEmpty() -> rohCard.copy(
                mediaUrl = medien.play,
                mediaStream = medien.play,
                mediaDuration = if (medien.durationMs > 0L) medien.durationMs else rohCard.mediaDuration
            )

            card?.mediaKind == KIND_VIDEO && medien.play.isNotEmpty() -> rohCard.copy(
                mediaUrl = medien.cover.ifEmpty { rohCard.mediaUrl },
                mediaStream = medien.play,
                mediaDuration = medien.durationMs
            )

            else -> rohCard
        }
        if (obj == null && classified.text.isEmpty() && classified.mediaUrl.isEmpty() && streak.first == 0 && scene.isEmpty() && badge == null) return null
        val id = longField(fields, 3)?.toString().orEmpty()
        val convHash = longField(fields, 5) ?: 0L
        val senderId = longField(fields, 7)?.toString().orEmpty()
        val timestamp = longField(fields, 10) ?: 0L
        val reactionsWithUser = reactionsByF15(fields)
        return RawMessage(
            id = id,
            convId = convId,
            convHash = convHash,
            senderId = senderId,
            text = classified.text,
            timestamp = timestamp,
            mediaUrl = classified.mediaUrl,
            mediaKind = classified.mediaKind,
            mediaKey = classified.mediaKey,
            streakCmd = streak.first,
            streakValue = streak.second,
            scene = scene,
            silent = obj == null,
            badge = badge,
            invisibleFor = mapValuesOf(fields, "s:invisible").orEmpty(),
            reactions = reactionsWithUser.keys.toList(),
            mediaStream = classified.mediaStream,
            mediaDuration = classified.mediaDuration,
            mediaWave = classified.mediaWave,
            mediaVid = classified.mediaVid,
            unknownType = classified.unknownType,
            reactionUsers = reactionsWithUser,
            replyToId = response.first,
            replyToText = response.second,
            replyToQuote = response.third
        )
    }

    private fun responseFromF18(fields: List<Field>): Triple<String, String, String> {
        val blob = bytesField(fields, 18) ?: return Triple("", "", "")
        val entry = readFields(blob)
        val zielId = longField(entry, 1)?.toString().orEmpty()
        val raw = stringField(entry, 2).orEmpty()
        val obj = raw.takeIf { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Triple(zielId, "", "")
        val quote = obj.optString("refmsg_template_quote")
        val content = obj.optString("refmsg_content")
        val text = quote.ifEmpty {
            content.takeIf { it.startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("text")
                .orEmpty()
        }
        return Triple(zielId, cleanText(text), quote)
    }

    private fun describeFields(bytes: ByteArray): String =
        readFields(bytes).joinToString(",") { it.number.toString() }

    private fun streakBadgeFrom(bytes: ByteArray): StreakBadge? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val content = contentField(readFields(bytes))
        val obj = content?.let { runCatching { JSONObject(it) }.getOrNull() }
        val tips = obj?.optString("tips").orEmpty()
        val numMatch = Regex("\"streak_badge_streak_num\":\"(\\d+)\"").find(text)
        val levelMatch = Regex("\"streak_badge_streak_level\":\"(\\d+)\"").find(text)
        val style = Regex("\"streak_badge_style\":\"(\\w+)\"").find(text)?.groupValues?.get(1).orEmpty()
        val ausTips = Regex("(\\d+)-t(?:ä|a)gi?ge").find(tips)?.groupValues?.get(1)?.toIntOrNull()
        val hasHint = numMatch != null || levelMatch != null ||
            style.isNotEmpty() || ausTips != null
        if (!hasHint) return null
        val anker = text.indexOf("streak_badge")
        val head = if (anker > 0) text.substring(maxOf(0, anker - 300), anker) else ""
        return StreakBadge(
            days = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: ausTips ?: 0,
            active = style == "orange",
            restorable = obj?.toString()?.contains("\"action\":200001") == true ||
                text.contains("\"streak_badge_style\":\"grey\""),
            scene = Regex("streak_(?:new|end|milestone|interaction)[a-z_]*")
                .find(head)?.value.orEmpty(),
            level = levelMatch?.groupValues?.get(1)?.toIntOrNull()
        )
    }

    private fun applyStreakBadges(raw: List<RawMessage>): MutableMap<String, TikTokStreak> {
        val streaks = mutableMapOf<String, TikTokStreak>()
        val afterTime = raw.sortedByDescending { it.timestamp }
        for (item in afterTime) {
            val badge = item.badge ?: continue
            if (streaks.containsKey(item.convId)) continue
            val zahl = badge.days.takeIf { it > 0 }
                ?: afterTime.firstOrNull {
                    it.convId == item.convId && (it.badge?.days ?: 0) > 0
                }?.badge?.days
            streaks[item.convId] = TikTokStreak(
                active = badge.active,
                restorable = badge.restorable,
                value = zahl,
                changedAt = item.timestamp
            )
        }
        return streaks
    }

    private fun parseContentPb(bytes: ByteArray, vid: String = ""): Classified? {
        val fields = readFields(bytes)
        val sticker = bytesField(fields, 7)?.let { readFields(it) }
        if (sticker != null) {
            val animated = sticker.firstOrNull { it.number == 1 && it.value is ByteArray }
                ?.value?.let { urlIn(it as ByteArray, 2) }.orEmpty()
            val fallback = sticker.firstOrNull { it.number == 4 && it.value is ByteArray }
                ?.value?.let { deepUrl(it as ByteArray) }.orEmpty()
            val url = animated.ifEmpty { fallback }
            if (url.isNotEmpty()) return Classified("😀 Sticker", url, KIND_STICKER)
        }
        val picture = bytesField(fields, 4)?.let { readFields(it) }
        if (picture != null) {
            val imageBytes = picture.firstOrNull { it.number == 1 && it.value is ByteArray }
                ?.value as? ByteArray
            val thumbBytes = picture.firstOrNull { it.number == 2 && it.value is ByteArray }
                ?.value as? ByteArray
            val full = imageBytes?.let { urls(it, 2).firstOrNull() }.orEmpty()
            val thumb = thumbBytes?.let { urls(it, 2).firstOrNull() }.orEmpty()
            val url = full.ifEmpty { thumb }
            if (url.isNotEmpty()) {
                val key = imageBytes?.let { decryptKeyOf(it) }.orEmpty()
                return Classified("🖼️ Bild", url, KIND_PHOTO, key)
            }
        }
        val video = bytesField(fields, 5)
        if (video != null) {
            val valUrls = alleUrls(bytes)
            val stream = valUrls.firstOrNull { it.contains("/kmoat/") }
                ?: valUrls.firstOrNull { it.contains("/video/fplay/") }.orEmpty()
            val cover = valUrls.firstOrNull { it != stream && it.contains("image") }
                ?: valUrls.firstOrNull { it != stream }
                ?: stream
            val duration = 0L
            if (cover.isNotEmpty() || stream.isNotEmpty()) {
                return Classified(
                    text = "🎬 Video",
                    mediaUrl = cover.ifEmpty { stream },
                    mediaKind = KIND_VIDEO,
                    mediaStream = stream,
                    mediaDuration = duration
                )
            }
        }
        val voice = bytesField(fields, 14)
        if (voice != null) {
            val voiceFelder = readFields(voice)
            val text = durationTextIn(bytesVon(voiceFelder, 102) ?: ByteArray(0))
                ?: "Sprachnachricht"
            val durationMs = durationFromText(text)
            val time = Regex("(\\d{1,2}:\\d{2})").find(text)?.groupValues?.get(1).orEmpty()
            return Classified(
                text = "🎤 Sprache" + if (time.isNotEmpty()) " $time" else "",
                mediaKind = KIND_VOICE,
                mediaKey = voiceKey(voiceFelder),
                mediaDuration = durationMs,
                mediaVid = vid
            )
        }
        val imageCard = bytesField(fields, 2)?.let { readFields(it) }
        if (imageCard != null) {
            val imageBytes = imageCard.firstOrNull { it.number == 1 && it.value is ByteArray }
                ?.value as? ByteArray
            val url = imageBytes?.let { urls(it, 2).firstOrNull() }.orEmpty()
            if (url.isNotEmpty()) {
                val key = imageBytes?.let { decryptKeyOf(it) }.orEmpty()
                return Classified("🖼️ Bild", url, KIND_PHOTO, key)
            }
        }
        return null
    }

    private fun voiceKey(voiceFelder: List<Field>): String =
        stringField(voiceFelder, PROTO_DECRYPT_KEY).orEmpty()

    private fun decryptKeyOf(bytes: ByteArray): String {
        val value = readFields(bytes).firstOrNull { it.number == 6 }?.value ?: return ""
        if (value is String) return value
        if (value !is ByteArray) return ""
        val text = String(value, Charsets.UTF_8)
        val isHex = text.isNotEmpty() && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return if (isHex) text else hexOf(value)
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

    private fun streakFromScene(scene: String, changedAt: Long): TikTokStreak? {
        if (!scene.contains("streak")) return null
        return when {
            scene.contains("streak_new") || scene.contains("milestone") ||
                scene.contains("interaction_unlock") ->
                TikTokStreak(active = true, changedAt = changedAt)

            scene.contains("end_with_restore") || scene.contains("end_no_restore") ->
                TikTokStreak(changedAt = changedAt)

            else -> null
        }
    }

    private fun streakFromCommand(current: TikTokStreak, cmd: Int, value: Int, changedAt: Long): TikTokStreak =
        when (cmd) {
            STREAK_CMD_START -> TikTokStreak(active = true, value = value.takeIf { it > 0 }, changedAt = changedAt)
            STREAK_CMD_UPDATE -> current.copy(active = true, restorable = false, value = value.takeIf { it > 0 } ?: current.value, changedAt = changedAt)
            STREAK_CMD_END -> TikTokStreak(changedAt = changedAt)
            else -> current
        }

    data class FrontierStreak(
        val convId: String = "",
        val cmdType: Int = 0,
        val counter: Int = 0,
        val startTime: Long = 0L,
        val action: Int = 0,
        val scene: String = ""
    )

    fun frontierAccessKey(deviceId: String): String {
        val raw = "$FRONTIER_FP_ID$FRONTIER_APP_KEY$deviceId$FRONTIER_SALT"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) out.append("%02x".format(byte.toInt() and 0xFF))
        return out.toString()
    }

    fun frontierUrl(deviceId: String): String =
        "wss://im-ws.tiktok.com/ws/v2?fpid=$FRONTIER_FP_ID&service=$FRONTIER_SERVICE" +
            "&method=$FRONTIER_METHOD&aid=$FRONTIER_AID&device_id=$deviceId" +
            "&access_key=${frontierAccessKey(deviceId)}&device_platform=web"

    fun buildFrontierEnvelope(
        cmd: Int,
        sequence: Long,
        body: ByteArray,
        service: Long = FRONTIER_SERVICE,
        extra: List<Pair<Int, ByteArray>> = emptyList()
    ): ByteArray = ByteArrayOutputStream().apply {
        writeVarintField(this, 1, cmd.toLong())
        writeVarintField(this, 2, sequence)
        writeVarintField(this, 3, service)
        writeVarintField(this, 4, 2)
        writeString(this, 7, "2")
        for ((first, second) in extra) writeBytes(this, first, second)
        writeBytes(this, 8, body)
    }.toByteArray()

    fun buildFrontierDeviceRegister(
        params: Map<String, String>,
        sequence: Long
    ): ByteArray {
        val entries = params.map { (key, value) ->
            ByteArrayOutputStream().apply {
                writeString(this, 1, key)
                writeString(this, 2, value)
            }.toByteArray()
        }
        return buildFrontierEnvelope(
            FRONTIER_DEVICE_CMD,
            sequence,
            ByteArray(0),
            service = FRONTIER_DEVICE_SERVICE,
            extra = entries.map { 5 to it }
        )
    }

    fun buildFrontierIndexRegister(
        uid: String,
        indices: List<Long>,
        sequence: Long
    ): ByteArray {
        val identity = ByteArrayOutputStream().apply {
            writeVarintField(this, 1, 2)
            writeString(this, 2, uid)
            writeString(this, 3, uid)
            writeVarintField(this, 4, sequence)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            writeBytes(this, 1, identity)
            for (index in indices) {
                writeBytes(this, 2, ByteArrayOutputStream().apply {
                    writeVarintField(this, 1, 4)
                    writeVarintField(this, 2, 0)
                    writeVarintField(this, 3, 1)
                    writeVarintField(this, 4, index)
                }.toByteArray())
            }
        }.toByteArray()
        return buildFrontierEnvelope(FRONTIER_INDEX_CMD, sequence, body)
    }

    fun buildFrontierAuth(uid: String, sequence: Long): ByteArray {
        val identity = ByteArrayOutputStream().apply {
            writeVarintField(this, 1, 2)
            writeString(this, 2, uid)
            writeString(this, 3, uid)
            writeVarintField(this, 4, sequence)
        }.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            writeBytes(this, 1, identity)
            for (level in 0..2) {
                writeBytes(this, 2, ByteArrayOutputStream().apply {
                    writeVarintField(this, 1, 3)
                    writeVarintField(this, 3, level.toLong())
                }.toByteArray())
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeVarintField(this, 1, FRONTIER_AUTH_CMD.toLong())
            writeVarintField(this, 2, sequence)
            writeVarintField(this, 3, FRONTIER_SERVICE)
            writeVarintField(this, 4, 2)
            writeString(this, 7, "2")
            writeBytes(this, 8, payload)
        }.toByteArray()
    }

    data class FrontierReaction(
        val serverMessageId: Long,
        val convShortId: Long,
        val emoji: String,
        val add: Boolean
    )

    private val PROPERTY_KEY = "s:property_modify".toByteArray(Charsets.ISO_8859_1)

    private val SERVER_ID = Regex("\"ServerMessageId\"\\s*:\\s*(\\d+)")
    private val CONV_ID = Regex("\"ConversationShortId\"\\s*:\\s*(\\d+)")
    private val MODIFY = Regex("\\{[^{}]*\"Op\"\\s*:\\s*(\\d+)[^{}]*\"Key\"\\s*:\\s*\"([^\"]*)\"[^{}]*\\}")

    private fun byteIndexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    fun parseFrontierReactions(bytes: ByteArray): List<FrontierReaction> {
        val out = mutableListOf<FrontierReaction>()
        var pos = 0
        while (pos < bytes.size) {
            val at = byteIndexOf(bytes, PROPERTY_KEY, pos)
            if (at < 0) break
            pos = at + 1
            var i = at + PROPERTY_KEY.size
            try {
                if (i >= bytes.size || (bytes[i].toInt() and 0xFF) != 0x12) continue
                i++
                var len = 0L
                var shift = 0
                while (i < bytes.size && shift <= 35) {
                    val b = bytes[i].toInt() and 0xFF
                    i++
                    len = len or ((b.toLong() and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 == 0) break
                }
                if (len <= 0L || len > (bytes.size - i).toLong()) continue
                val text = String(bytes, i, len.toInt(), Charsets.UTF_8)
                val msgId = SERVER_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val convId = CONV_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (msgId <= 0L) continue
                for (m in MODIFY.findAll(text)) {
                    val key = m.groupValues[2]
                    if (!key.startsWith("e:") || key.length <= 2) continue
                    out.add(
                        FrontierReaction(
                            serverMessageId = msgId,
                            convShortId = convId,
                            emoji = key.substring(2),
                            add = m.groupValues[1].toInt() == 0
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun parseFrontierStreaks(bytes: ByteArray): List<FrontierStreak> {
        val out = mutableListOf<FrontierStreak>()
        val frame = runCatching { readFields(bytes) }.getOrElse { return emptyList() }
        collectFrontierStreaks(bytesField(frame, 8) ?: bytes, 0, out)
        return out
    }

    fun parseFrontierMessages(bytes: ByteArray, self: String): List<TikTokMessage> {
        val out = LinkedHashMap<String, TikTokMessage>()
        if (self.isEmpty()) return emptyList()
        val frame = runCatching { readFields(bytes) }.getOrElse { return emptyList() }
        val payload = bytesField(frame, 8) ?: bytes
        collectFrontierMessages(payload, self, 0, out)
        return out.values.toList()
    }

    private fun collectFrontierMessages(
        bytes: ByteArray,
        self: String,
        depth: Int,
        out: MutableMap<String, TikTokMessage>
    ) {
        if (depth > 8 || out.size >= 32) return
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            gunzip(bytes)?.let { collectFrontierMessages(it, self, depth + 1, out) }
            return
        }
        val fields = runCatching { readFields(bytes) }.getOrElse { return }
        val looksLikeMessage = bytesField(fields, 1) != null &&
            stringField(fields, 1)?.contains(':') == true &&
            longField(fields, 3) != null
        if (looksLikeMessage) {
            val message = runCatching { parseMessage(bytes) }.getOrNull()
            if (message != null && message.id.isNotEmpty()) {
                out.putIfAbsent(message.id, toMessage(message, self))
            }
        }
        for ((_, value) in fields) {
            val chunk = value as? ByteArray ?: continue
            if (chunk.isEmpty()) continue
            collectFrontierMessages(chunk, self, depth + 1, out)
        }
    }

    private fun collectFrontierStreaks(bytes: ByteArray, depth: Int, out: MutableList<FrontierStreak>) {
        if (depth > 8 || out.size >= 16) return
        if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            gunzip(bytes)?.let { collectFrontierStreaks(it, depth + 1, out) }
            return
        }
        val fields = runCatching { readFields(bytes) }.getOrElse { return }
        streakFromMessageBody(fields)?.let { out.add(it) }
        streakFromCounterValue(fields)?.let { push ->
            if (out.none { it.convId == push.convId && it.counter == push.counter }) out.add(push)
        }
        for ((_, value) in fields) {
            val chunk = value as? ByteArray ?: continue
            if (chunk.isEmpty()) continue
            collectFrontierStreaks(chunk, depth + 1, out)
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }.getOrNull()

    private fun streakFromMessageBody(fields: List<Field>): FrontierStreak? {
        val convId = stringField(fields, 1) ?: return null
        if (!convId.contains(':')) return null
        val scene = stringField(fields, 21).orEmpty()
        val status = longField(fields, 12) ?: 0L
        val command = bytesField(fields, 20)?.let { bytesField(readFields(it), 200) }
        if (command == null && status != MESSAGE_TYPE_STREAK_STATUS && scene.isEmpty()) return null
        var cmdType = 0
        var counter = 0
        var startTime = 0L
        var action = 0
        if (command != null) {
            val commandFields = readFields(command)
            cmdType = longField(commandFields, 1)?.toInt() ?: 0
            action = longField(commandFields, 3)?.toInt() ?: 0
            bytesField(commandFields, 2)?.let { value ->
                val valueFields = readFields(value)
                counter = longField(valueFields, 3)?.toInt() ?: 0
                startTime = longField(valueFields, 2) ?: 0L
            }
        }
        return FrontierStreak(convId, cmdType, counter, startTime, action, scene)
    }

    private fun streakFromCounterValue(fields: List<Field>): FrontierStreak? {
        val counter = longField(fields, 3) ?: return null
        val convId = stringField(fields, 101) ?: return null
        if (!convId.contains(':') || counter <= 0L) return null
        return FrontierStreak(convId, 0, counter.toInt(), longField(fields, 2) ?: 0L, 0, "")
    }

    private fun parseStreak(bytes: ByteArray): Pair<Int, Int> {
        val cmd = bytesField(readFields(bytes), 200) ?: return 0 to 0
        val fields = readFields(cmd)
        val cmdType = longField(fields, 1)?.toInt() ?: 0
        val value = bytesField(fields, 2)
            ?.let { longField(readFields(it), 3)?.toInt() }
            ?: 0
        return cmdType to value
    }

    private fun mapEntry(bytes: ByteArray): Pair<String, String>? {
        val fields = readFields(bytes)
        val key = stringField(fields, 1) ?: return null
        val value = stringField(fields, 2) ?: return null
        return key to value
    }

    private fun reactionsFromF15(fields: List<Field>): List<String> {
        val out = LinkedHashSet<String>()
        for (field in fields) {
            if (field.number != 15 || field.value !is ByteArray) continue
            val entry = readFields(field.value)
            val key = stringField(entry, 1) ?: continue
            if (!key.startsWith("e:") || key.length <= 2) continue
            val emoji = key.substring(2)
            if (emoji.all { it.code in 32..126 }) continue
            out.add(emoji)
        }
        return out.toList()
    }

    private fun reactionsByF15(fields: List<Field>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (field in fields) {
            if (field.number != 15 || field.value !is ByteArray) continue
            val entry = readFields(field.value)
            val key = stringField(entry, 1) ?: continue
            if (!key.startsWith("e:") || key.length <= 2) continue
            val emoji = key.substring(2)
            if (emoji.all { it.code in 32..126 }) continue
            val users = mutableListOf<String>()
            val payload = bytesField(entry, 2)
            if (payload != null) {
                for ((number, value1) in readFields(payload)) {
                    if (number != 1 || value1 !is ByteArray) continue
                    val info = readFields(value1)
                    val uid = longField(info, 4)?.toString()
                        ?: longField(info, 1)?.toString()
                        ?: continue
                    users += uid
                }
            }
            out.getOrPut(emoji) { mutableListOf() }.addAll(users)
        }
        val result = LinkedHashMap<String, List<String>>()
        for ((emoji, list) in out) {
            result[emoji] = list.distinct()
        }
        return result
    }

    private fun mapValuesOf(fields: List<Field>, key: String): String? =
        fields
            .filter { it.number == 9 && it.value is ByteArray }
            .mapNotNull { mapEntry(it.value as ByteArray) }
            .firstOrNull { it.first == key }
            ?.second

    private fun isDone(fields: List<Field>): Boolean =
        mapValuesOf(fields, "s:is_recalled") == "1"

    private fun bytesVon(fields: List<Field>, number: Int): ByteArray? =
        fields.firstOrNull { it.number == number && it.value is ByteArray }?.value as? ByteArray

    private fun longVon(fields: List<Field>, number: Int): Long? =
        fields.firstOrNull { it.number == number && it.value is Long }?.value as? Long

    private fun bytesField(fields: List<Field>, number: Int): ByteArray? =
        fields.firstOrNull { it.number == number && it.value is ByteArray }?.value as? ByteArray

    private class MedienUrls(val play: String, val cover: String, val durationMs: Long)

    private fun medienAusF24(bytes: ByteArray?): MedienUrls {
        if (bytes == null) return MedienUrls("", "", 0L)
        var play = ""
        var cover = ""
        var duration = 0L
        for (field in readFields(bytes)) {
            if (field.number != 20 || field.value !is ByteArray) continue
            val entry = readFields(field.value)
            val rolle = stringField(entry, 1) ?: continue
            val url = stringField(entry, 2)?.takeIf { it.startsWith("http") }.orEmpty()
                .ifEmpty { alleUrls(field.value).firstOrNull().orEmpty() }
            when (rolle) {
                "play" -> {
                    if (play.isEmpty()) play = url
                    if (duration == 0L) duration = longVon(entry, 22) ?: 0L
                }
                "cover" -> if (cover.isEmpty()) cover = url
            }
        }
        return MedienUrls(play, cover, duration)
    }

    private fun alleUrls(bytes: ByteArray, depthLevel: Int = 8, acc: MutableList<String> = mutableListOf()): List<String> {
        if (depthLevel <= 0 || bytes.isEmpty()) return acc
        for ((_, value1) in readFields(bytes)) {
            val value = value1 as? ByteArray ?: continue
            if (value.isEmpty()) continue
            val text = runCatching { String(value, Charsets.UTF_8) }.getOrNull()
            if (text != null && text.contains("http")) {
                Regex("https?://[^\\s\\u0000-\\u001f]+").findAll(text)
                    .forEach { matches ->
                        val url = matches.value.trim('"', '\'')
                        if (url.isNotEmpty() && acc.none { it == url }) acc.add(url)
                    }
            }
            alleUrls(value, depthLevel - 1, acc)
        }
        return acc
    }

    private fun durationTextIn(bytes: ByteArray, depth: Int = 4): String? {
        if (depth <= 0 || bytes.isEmpty()) return null
        for ((_, value1) in readFields(bytes)) {
            val value = value1 as? ByteArray ?: continue
            if (value.isEmpty()) continue
            val text = runCatching { String(value, Charsets.UTF_8) }.getOrNull()
            if (!text.isNullOrEmpty() && Regex("\\d{1,2}:\\d{2}").containsMatchIn(text)) {
                return text.trim()
            }
            durationTextIn(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun durationFromText(text: String): Long {
        val matches = Regex("(\\d{1,2}):(\\d{2})").find(text) ?: return 0L
        val minuten = matches.groupValues[1].toLongOrNull() ?: return 0L
        val sekunden = matches.groupValues[2].toLongOrNull() ?: return 0L
        return (minuten * 60L + sekunden) * 1000L
    }

    private fun urls(bytes: ByteArray, number: Int): List<String> =
        readFields(bytes)
            .filter { it.number == number && it.value is String }
            .map { it.value as String }
            .filter { it.startsWith("http") }

    private fun urlIn(bytes: ByteArray, number: Int): String = urls(bytes, number).firstOrNull().orEmpty()

    private fun deepUrl(bytes: ByteArray, depth: Int = 3): String {
        if (depth <= 0) return ""
        for ((_, value1) in readFields(bytes)) {
            val value = value1 as? ByteArray ?: continue
            val direct = runCatching { String(value, Charsets.UTF_8) }.getOrNull()
            if (direct != null && direct.startsWith("http")) return direct
            val nested = deepUrl(value, depth - 1)
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    private fun classify(
        obj: JSONObject,
        card: Classified?,
        scene: String,
        pbFields: String,
        messageType: Long
    ): Classified? {
        if (obj.has("refmsg_type") && !obj.has("text") && card == null) return null
        val itemId = obj.optString("itemId")
        if (itemId.isNotEmpty()) {
            val cover = firstUrl(obj.optJSONObject("cover_url"))
            val title = obj.optString("content_title").ifEmpty { obj.optString("content_name") }
            val kind = if (obj.optInt("aweType", 0) == 810) "🖼️ TikTok-Foto" else "🎬 TikTok-Video"
            val label = if (title.isNotEmpty()) "$kind — $title" else kind
            return Classified(label, cover, KIND_PHOTO)
        }
        if (obj.has("reference_video")) {
            val commentImages = obj.optJSONArray("comment_image_list")
            val firstCommentImage = commentImages?.optJSONObject(0)
            if (firstCommentImage != null) {
                val cover = firstUrl(firstCommentImage.optJSONObject("image") ?: firstCommentImage)
                    .ifEmpty { firstUrl(firstCommentImage) }
                val caption = obj.optString("comment")
                val label = if (caption.isNotEmpty()) "🖼️ Bildkommentar — ${cleanText(caption)}" else "🖼️ Bildkommentar"
                return Classified(label, cover, KIND_PHOTO)
            }
            val cover = firstUrl(obj.optJSONObject("reference_video_cover_url"))
            val caption = obj.optString("text")
            val label = if (caption.isNotEmpty()) "🎬 Video — ${cleanText(caption)}" else "🎬 Video"
            return Classified(label, cover, KIND_PHOTO)
        }
        if (card != null) return card
        val rawText = obj.optString("text")
        if (rawText.isNotEmpty()) {
            val clean = cleanText(rawText)
            val quote = obj.optJSONObject("refmsg")?.optString("text")?.let { cleanText(it) }.orEmpty()
            val isReply = obj.optInt("aweType", 0) == 703 || quote.isNotEmpty()
            return Classified(if (isReply && quote.isNotEmpty()) "↩ $quote\n$clean" else clean)
        }
        val roomName = obj.optString("room_owner_name")
        if (roomName.isNotEmpty()) return Classified("🔴 Live: $roomName")
        val tips = obj.optString("tips")
        if (tips.isNotEmpty()) return Classified("ℹ️ ${cleanText(tips)}")
        val sceneLabel = systemSceneLabel(scene, obj)
        if (sceneLabel.isNotEmpty()) return Classified(sceneLabel)
        if (rawText.isEmpty() && tips.isEmpty()) {
            return Classified(
                text = "Unbekannter Nachrichtentyp",
                unknownType = describeUnknown(scene, messageType, pbFields, obj)
            )
        }
        return null
    }

    private fun nudgeText(obj: JSONObject?, contentPb: ByteArray?, card: Classified?): String {
        val ausJson = obj?.optString("text").orEmpty()
        if (ausJson.isNotEmpty()) return "👉 ${cleanText(ausJson)}"
        val ausPb = contentPb?.let { pb ->
            val sticker = bytesField(readFields(pb), 7) ?: return@let ""
            val hint = bytesField(readFields(sticker), 2) ?: return@let ""
            val outer = bytesField(readFields(hint), 1) ?: return@let ""
            val outerFields = readFields(outer)
            val text = stringField(outerFields, 1).orEmpty()
            if (text.isNotEmpty()) text
            else bytesField(outerFields, 1)
                ?.let { runCatching { String(it, Charsets.UTF_8).trim() }.getOrNull() }
                .orEmpty()
        }.orEmpty()
        if (ausPb.length > 3 && ausPb.none { it.code < 32 }) return "👉 $ausPb"
        val ausKarte = card?.text.orEmpty()
        return if (ausKarte.isNotEmpty() && !ausKarte.startsWith("😀")) ausKarte else "👉 Angestupst"
    }

    private fun deepText(bytes: ByteArray, depth: Int = 6): String? {
        if (depth <= 0) return null
        for ((_, value1) in readFields(bytes)) {
            val value = value1 as? ByteArray ?: continue
            val raw = runCatching { String(value, Charsets.UTF_8) }.getOrNull()
            val text = raw?.trim().orEmpty()
            if (text.length > 3 && text.none { it.code < 32 }) return text
            deepText(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun systemSceneLabel(scene: String, obj: JSONObject): String = when (scene) {
        "nudge" -> {
            val hint = obj.optString("text").ifEmpty { obj.optString("fallback_tips") }
            if (hint.isNotEmpty()) "👉 ${cleanText(hint)}" else "👉 Angestupst"
        }

        "greeting_card" -> "👋 Grußkarte"
        "streak_pet_egg_invitation" -> "🐾 Serienhaustier-Einladung"
        "PRM_ci_share_feed_invitation" -> "📨 Einladung eines geteilten Feeds"
        "story_reaction" -> "📖 Story-Reaktion"
        else -> ""
    }

    private fun describeUnknown(
        scene: String,
        messageType: Long,
        pbFields: String,
        obj: JSONObject
    ): String {
        val keys = obj.keys().asSequence().sorted().joinToString(",")
        return "scene=${scene.ifEmpty { "-" }} type=$messageType pb=${pbFields.ifEmpty { "-" }} json=$keys"
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

    private fun stringField(fields: List<Field>, number: Int): String? {
        val value = fields.firstOrNull { it.number == number }?.value ?: return null
        return when (value) {
            is String -> value
            is ByteArray -> String(value, Charsets.UTF_8)
            else -> null
        }
    }

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
