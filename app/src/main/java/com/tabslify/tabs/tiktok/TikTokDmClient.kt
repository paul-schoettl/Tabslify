package com.tabslify.tabs.tiktok

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TikTokDmException(val status: Int, message: String) : Exception(message)

object TikTokDmClient {

    private const val INIT_URL = "https://im-api.tiktok.com/v2/message/get_by_user_init"
    private const val CONV_URL = "https://im-api.tiktok.com/v1/message/get_by_conversation"
    private const val SEND_URL = "https://im-api.tiktok.com/v1/message/send"
    private const val CONV_MARK_READ_URL = "https://im-api.tiktok.com/v3/conversation/mark_read"
    private const val RECALL_URL = "https://im-api.tiktok.com/v1/message/recall"
    private const val DELETE_URL = "https://im-api.tiktok.com/v1/message/delete"
    private const val REACTION_URL = "https://im-api.tiktok.com/v1/message/set_property"
    private const val TAG = "TikTokTab"
    private const val PROFILE_URL = "https://www.tiktok.com/tiktok/v1/im/user/profile/"
    private const val UNMASK_URL =
        "https://api16-normal-useastred.tiktokv.eu/tiktok/v1/im/media_message/unmask"
    private const val UNMASK_INBOX_TYP = 0
    private const val UNMASK_SZENE = 3
    private const val APP_VERSION_NAME = "39.8.2"
    private const val APP_VERSION_CODE = "390802"
    private const val APP_CHANNEL = "googleplay"
    private const val APP_DEVICE_TYPE = "Pixel 7"
    private const val APP_OS_VERSION = "13"
    private const val APP_USER_AGENT =
        "com.zhiliaoapp.musically/2023508030 (Linux; U; Android 13; de_DE; Pixel 7; " +
            "Build/TD1A.220804.031; Cronet/58.0.2991.0)"
    private const val PREFS_STREAK = "tiktok_streaks"
    private const val CACHE_MESSAGE_LIMIT = 2000
    private const val CACHE_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
    private const val FEED_MESSAGE_LIMIT = 5000
    private const val URL_ERNEUERUNG_SPERRE_MS = 5000L
    private const val POLL_INTERVAL_MS = 30_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0"

    private val _feed = MutableStateFlow(TikTokFeed(emptyList(), emptyList()))
    val feed: StateFlow<TikTokFeed> = _feed.asStateFlow()

    private val _loadingConversation = MutableStateFlow<String?>(null)
    val loadingConversation: StateFlow<String?> = _loadingConversation.asStateFlow()

    private val hasMoreConv = mutableMapOf<String, Boolean>()
    private var selfId = ""
    private var context: Context? = null
    private var pollScope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var pollRefs = 0
    private var letzteUrlErneuerung = 0L
    private var cachedFingerprint: String = ""
    private var streakFingerprintSaved: String = ""

    fun currentSelfId(): String = selfId

    fun frontierParams(session: TikTokSession): Map<String, String> = buildParams(session)

    fun frontierIndices(): List<Long> =
        _feed.value.convMeta.values
            .map { it.convShortId }
            .filter { it > 0L }
            .distinct()

    fun currentConversation(convId: String): TikTokConversation? =
        _feed.value.conversations.firstOrNull { it.convId == convId }

    fun startPolling(context: Context) {
        val app = context.applicationContext
        this@TikTokDmClient.context = app
        synchronized(this) {
            pollRefs++
            if (pollJob?.isActive == true) return
            pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            pollJob = pollScope?.launch {
                TikTokFrontier.start(app)
                while (isActive) {
                    try {
                        load(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Laden fehlgeschlagen: ${e.message}")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stopPolling() {
        synchronized(this) {
            if (pollRefs > 0) pollRefs--
            if (pollRefs > 0 || pollJob?.isActive != true) return
            pollJob?.cancel()
            pollJob = null
            pollScope?.cancel()
            pollScope = null
            TikTokFrontier.stop()
        }
    }

    suspend fun load(context: Context): TikTokFeed = withContext(Dispatchers.IO) {
        this@TikTokDmClient.context = context.applicationContext
        try {
            val session = TikTokCookieRepository.session(context)
            val parsed = fetch(session)
            val named = resolveNames(session.cookie, parsed)
            val previous = _feed.value
            if (previous.selfId.isNotEmpty() && previous.selfId != named.selfId) {
                hasMoreConv.clear()
                _feed.value = TikTokFeed(emptyList(), emptyList())
            }
            selfId = named.selfId
            val current = _feed.value
            val byId = LinkedHashMap<String, TikTokMessage>()
            for (message in current.messages) byId[messageKey(message)] = message
            for (message in named.messages) byId[messageKey(message)] = message
            val streaks = mergeStreaks(
                mergeStreaks(current.streaks, persistedStreaks(named.selfId)),
                named.streaks
            )
            val messages = byId.values.sortedBy { it.timestamp }.let { list ->
                if (list.size > FEED_MESSAGE_LIMIT) {
                    android.util.Log.i("TikTokTab", "Feed auf $FEED_MESSAGE_LIMIT Nachrichten begrenzt (war ${list.size})")
                    list.takeLast(FEED_MESSAGE_LIMIT)
                } else list
            }
            val merged = named.copy(
                messages = messages,
                hasMore = current.hasMore,
                streaks = streaks,
                conversations = named.conversations.map { conversation ->
                    conversation.copy(
                        streak = streaks[conversation.convId]
                            ?: current.conversations.firstOrNull { it.convId == conversation.convId }?.streak
                            ?: TikTokStreak()
                    )
                }
            )
            _feed.value = applyStreaks(merged)
            persistIfChanged(named.selfId, _feed.value)
            _feed.value
        } catch (error: TikTokDmException) {
            val cached = readCache()
            if (cached != null) cached else throw error
        } catch (error: TikTokCookieException) {
            val cached = readCache()
            if (cached != null) cached else throw error
        }
    }

    private fun feedFingerprint(feed: TikTokFeed): String {
        val last = feed.messages.lastOrNull()
        return "${'$'}{feed.messages.size}|${'$'}{last?.timestamp ?: 0L}|${'$'}{last?.id.orEmpty()}"
    }

    private fun streakFingerprint(streaks: Map<String, TikTokStreak>): String =
        streaks.entries.sortedBy { it.key }.joinToString(";") { entry ->
            "${'$'}{entry.key}=${'$'}{entry.value.active},${'$'}{entry.value.restorable}," +
                "${'$'}{entry.value.value},${'$'}{entry.value.changedAt}"
        }

    private fun persistIfChanged(selfId: String, feed: TikTokFeed) {
        val messages = feedFingerprint(feed)
        if (messages != cachedFingerprint) {
            cachedFingerprint = messages
            scopeWrite { writeCache(feed) }
        }
        val streaks = streakFingerprint(feed.streaks)
        if (streaks != streakFingerprintSaved) {
            streakFingerprintSaved = streaks
            persistStreaks(selfId, feed.streaks)
        }
    }

    private fun scopeWrite(block: suspend () -> Unit) {
        pollScope?.launch(Dispatchers.IO) { runCatching { block() } }
    }

    private fun fensterGrenzen(serverseitig: List<TikTokMessage>): Map<String, Long> =
        serverseitig.groupBy { it.convId }
            .mapValues { (_, liste) -> liste.minOf { it.timestamp } }

    private suspend fun cacheFensterRaeumen(convId: String, serverseitig: List<TikTokMessage>) {
        val ctx = context ?: return
        runCatching {
            val dao = TikTokCacheDatabase.get(ctx).messageDao()
            val behalten = serverseitig
                .filter { it.convId == convId }
                .map { messageKey(it) }
            if (behalten.isEmpty()) {
                dao.deleteByConversation(convId)
                return@runCatching
            }
            val grenze = serverseitig
                .filter { it.convId == convId }
                .minOfOrNull { it.timestamp }
                ?: return@runCatching
            dao.deleteStaleImFenster(convId, grenze, behalten)
        }.onFailure {
            Log.w(TAG, "Veraltete Nachrichten nicht bereinigt: ${it.message}")
        }
    }

    private suspend fun cacheLoeschung(messageId: Long) {
        val ctx = context ?: return
        runCatching {
            TikTokCacheDatabase.get(ctx).messageDao().deleteByKey(messageId.toString())
        }.onFailure {
            Log.w(TAG, "Cache-Bereinigung fehlgeschlagen: ${it.message}")
        }
    }

    private suspend fun writeCache(feed: TikTokFeed) {
        val ctx = context ?: return
        runCatching {
            val db = TikTokCacheDatabase.get(ctx)
            val rows = feed.messages.takeLast(CACHE_MESSAGE_LIMIT).map { it.toCached(messageKey(it)) }
            val neueste = rows.groupBy { it.convId }.mapValues { (_, liste) ->
                liste.maxByOrNull { it.timestamp }
            }
            db.conversationDao().upsertAll(feed.conversations.map { conversation ->
                val aktuell = neueste[conversation.convId]
                val zeile = conversation.toCached()
                if (aktuell == null) zeile
                else zeile.copy(lastTimestamp = aktuell.timestamp, preview = aktuell.text)
            })
            db.messageDao().insertAll(rows)
            val cutoff = System.currentTimeMillis() - CACHE_RETENTION_MS
            db.conversationDao().deleteOlderThan(cutoff)
            db.messageDao().deleteOlderThan(cutoff)
        }
    }

    private suspend fun readCache(): TikTokFeed? {
        val ctx = context ?: return null
        return runCatching {
            val db = TikTokCacheDatabase.get(ctx)
            val conversations = db.conversationDao().all()
            if (conversations.isEmpty()) return@runCatching null
            val messages = db.messageDao().all().map { it.toMessage() }
            TikTokFeed(
                conversations = conversations.map { row ->
                    TikTokConversation(
                        row.convId,
                        row.peerId,
                        row.name,
                        row.lastTimestamp,
                        row.preview,
                        row.unread,
                        TikTokStreak(
                            active = row.streakActive,
                            restorable = row.streakRestorable,
                            value = row.streakValue.takeIf { it > 0 }
                        )
                    )
                },
                messages = messages,
                selfId = selfId,
                convMeta = emptyMap()
            )
        }.getOrNull()
    }

    private fun TikTokConversation.toCached() = CachedConversation(
        convId = convId,
        peerId = peerId,
        name = name,
        lastTimestamp = lastTimestamp,
        preview = lastMessage,
        unread = unread,
        streakValue = streak.value ?: 0,
        streakActive = streak.active,
        streakRestorable = streak.restorable,
        updatedAt = System.currentTimeMillis()
    )

    private fun TikTokMessage.toCached(key: String) = CachedMessage(
        key = key,
        messageId = id,
        convId = convId,
        convHash = convHash,
        senderId = senderId,
        text = text,
        timestamp = timestamp,
        isOwn = isOwn,
        mediaUrl = mediaUrl,
        mediaKind = mediaKind,
        mediaKey = mediaKey,
        reactions = reactions.joinToString(","),
        mediaStream = mediaStream,
        mediaDuration = mediaDuration,
        mediaWave = mediaWave,
        mediaVid = mediaVid
    )

    private fun CachedMessage.toMessage() = TikTokMessage(
        id = messageId,
        convId = convId,
        convHash = convHash,
        senderId = senderId,
        text = text,
        timestamp = timestamp,
        isOwn = isOwn,
        mediaUrl = mediaUrl,
        mediaKind = mediaKind,
        mediaKey = mediaKey,
        reactions = reactions.split(',').filter { it.isNotEmpty() },
        mediaStream = mediaStream,
        mediaDuration = mediaDuration,
        mediaWave = mediaWave,
        mediaVid = mediaVid
    )

    private fun applyStreaks(feed: TikTokFeed): TikTokFeed =
        feed.copy(
            conversations = feed.conversations.map { conversation ->
                feed.streaks[conversation.convId]?.let { conversation.copy(streak = it) } ?: conversation
            }
        )

    private fun persistedStreaks(ownerId: String): Map<String, TikTokStreak> = runCatching {
        if (ownerId.isEmpty()) return emptyMap()
        val prefs = context?.getSharedPreferences(PREFS_STREAK, Context.MODE_PRIVATE)
            ?: return emptyMap()
        val stored = prefs.getString(PREFS_STREAK, null) ?: return emptyMap()
        val obj = JSONObject(stored).optJSONObject(ownerId) ?: return emptyMap()
        val out = HashMap<String, TikTokStreak>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val convId = keys.next()
            val entry = obj.optJSONObject(convId) ?: continue
            out[convId] = TikTokStreak(
                active = entry.optInt("active", 0) != 0,
                restorable = entry.optInt("restorable", 0) != 0,
                value = entry.takeIf { it.has("value") }?.optInt("value"),
                changedAt = entry.optLong("changedAt", 0L)
            )
        }
        out
    }.getOrDefault(emptyMap())

    private fun persistStreaks(ownerId: String, streaks: Map<String, TikTokStreak>) {
        if (ownerId.isEmpty()) return
        val prefs = context?.getSharedPreferences(PREFS_STREAK, Context.MODE_PRIVATE) ?: return
        val obj = runCatching {
            val stored = prefs.getString(PREFS_STREAK, null)
            if (stored.isNullOrEmpty()) JSONObject() else JSONObject(stored)
        }.getOrElse { JSONObject() }
        val account = JSONObject()
        for ((convId, streak) in streaks) {
            if (!hasStreakEvidence(streak)) continue
            account.put(
                convId,
                JSONObject()
                    .put("active", if (streak.active) 1 else 0)
                    .put("restorable", if (streak.restorable) 1 else 0)
                    .put("changedAt", streak.changedAt)
                    .apply { streak.value?.let { put("value", it) } }
            )
        }
        if (account.length() == 0) obj.remove(ownerId) else obj.put(ownerId, account)
        prefs.edit().putString(PREFS_STREAK, obj.toString()).apply()
    }

    suspend fun applyFrontierReaction(
        serverMessageId: Long,
        convShortId: Long,
        emoji: String,
        add: Boolean
    ) = withContext(Dispatchers.IO) {
        val current = _feed.value
        val key = serverMessageId.toString()
        var getroffen = false
        val messages = current.messages.map { m ->
            if (m.id != key) m
            else {
                getroffen = true
                m.copy(
                    reactions = if (add) (m.reactions + emoji).distinct()
                    else m.reactions.filterNot { it == emoji }
                )
            }
        }
        if (!getroffen) return@withContext
        val conversations = current.conversations.map { c ->
            c.copy(
                reactions = messages
                    .filter { it.convId == c.convId }
                    .flatMap { it.reactions }
                    .distinct()
            )
        }
        _feed.value = current.copy(messages = messages, conversations = conversations)
        writeCache(_feed.value)
    }

    suspend fun saveStreak(convId: String, incoming: TikTokStreak) = withContext(Dispatchers.IO) {
        val current = _feed.value
        val existing = current.streaks[convId] ?: TikTokStreak()
        val streak = newerStreak(existing, incoming)
        val streaks = current.streaks + (convId to streak)
        _feed.value = applyStreaks(current.copy(streaks = streaks))
        persistStreaks(current.selfId.ifEmpty { selfId }, streaks)
    }

    private fun mergeStreaks(
        existing: Map<String, TikTokStreak>,
        incoming: Map<String, TikTokStreak>
    ): Map<String, TikTokStreak> = incoming.entries.fold(existing) { merged, (convId, state) ->
        merged + (convId to newerStreak(merged[convId] ?: TikTokStreak(), state))
    }

    private fun newerStreak(current: TikTokStreak, incoming: TikTokStreak): TikTokStreak =
        TikTokProto.latestStreak(current, incoming)

    private fun hasStreakEvidence(streak: TikTokStreak): Boolean =
        streak.active || streak.restorable || streak.value != null

    private fun messageKey(message: TikTokMessage): String =
        if (message.id.isNotEmpty()) message.id else "${message.convId}:${message.timestamp}:${message.text}"

    suspend fun setReaction(
        context: Context,
        message: TikTokMessage,
        emoji: String,
        add: Boolean
    ): String = withContext(Dispatchers.IO) {
        val session = TikTokCookieRepository.session(context)
        val meta = convMeta(context, message.convId) ?: throw TikTokDmException(409, "Chat-Metadaten fehlen")
        val id = message.id.toLongOrNull() ?: throw TikTokDmException(400, "Nachricht ohne Server-ID")
        val body = TikTokProto.buildReactionRequest(
            buildParams(session),
            session.deviceId,
            meta.convId,
            meta.convType,
            meta.convShortId,
            id,
            emoji,
            add
        )
        ensureOk(post(REACTION_URL, session.cookie, body), "Reaktion")
        val updated = if (add) {
            _feed.value.messages.map {
                if (it.id == message.id) it.copy(reactions = (it.reactions + emoji).distinct()) else it
            }
        } else {
            _feed.value.messages.map {
                if (it.id == message.id) it.copy(reactions = it.reactions - emoji) else it
            }
        }
        _feed.value = _feed.value.copy(
            messages = updated,
            conversations = _feed.value.conversations.map { conversation ->
                if (conversation.convId != message.convId) conversation
                else conversation.copy(
                    reactions = updated
                        .filter { it.convId == conversation.convId }
                        .flatMap { it.reactions }
                        .distinct()
                )
            }
        )
        if (add) "Reaktion gesetzt" else "Reaktion entfernt"
    }

    suspend fun recall(context: Context, convId: String, messageId: Long): String =
        withContext(Dispatchers.IO) {
            val session = TikTokCookieRepository.session(context)
            val meta = convMeta(context, convId) ?: throw TikTokDmException(409, "Chat-Metadaten fehlen")
            val body = TikTokProto.buildRecallRequest(
                buildParams(session), session.deviceId, meta, messageId
            )
            ensureOk(post(RECALL_URL, session.cookie, body), "Nachricht zurückrufen")
            _feed.value = _feed.value.copy(
                messages = _feed.value.messages.filterNot {
                    it.convId == convId && it.id == messageId.toString()
                }
            )
            cacheLoeschung(messageId)
            "Nachricht zurückgerufen"
        }

    suspend fun deleteMessage(context: Context, convId: String, messageId: Long): String =
        withContext(Dispatchers.IO) {
            val session = TikTokCookieRepository.session(context)
            val meta = convMeta(context, convId) ?: throw TikTokDmException(409, "Chat-Metadaten fehlen")
            val body = TikTokProto.buildDeleteRequest(
                buildParams(session), session.deviceId, meta, messageId
            )
            ensureOk(post(DELETE_URL, session.cookie, body), "Nachricht löschen")
            _feed.value = _feed.value.copy(
                messages = _feed.value.messages.filterNot {
                    it.convId == convId && it.id == messageId.toString()
                }
            )
            cacheLoeschung(messageId)
            "Nachricht gelöscht"
        }

    suspend fun markRead(context: Context, convId: String) = withContext(Dispatchers.IO) {
        val meta = convMeta(context, convId) ?: return@withContext
        val runCatchingSession = runCatching { TikTokCookieRepository.session(context) }.getOrNull()
            ?: return@withContext
        val newest = _feed.value.messages
            .filter { it.convId == convId }
            .maxOfOrNull { it.timestamp }
            ?: return@withContext
        val body = TikTokProto.buildMarkReadRequest(
            buildParams(runCatchingSession),
            runCatchingSession.deviceId,
            meta,
            newest * 1000L
        )
        val response = runCatching {
            ensureOk(post(CONV_MARK_READ_URL, runCatchingSession.cookie, body), "Als gelesen markieren")
        }
        if (response.isSuccess) {
            _feed.value = _feed.value.copy(
                conversations = _feed.value.conversations.map {
                    if (it.convId == convId) it.copy(unread = 0) else it
                },
                convMeta = _feed.value.convMeta + (convId to meta.copy(unread = 0))
            )
        } else {
            Log.w(TAG, "markRead fehlgeschlagen: ${response.exceptionOrNull()?.message}")
        }
    }

    private fun ensureOk(bytes: ByteArray, what: String) {
        val code = TikTokProto.statusCode(bytes)
        if (code == 0) return
        val desc = TikTokProto.errorDesc(bytes)
        Log.w(
            TAG,
            "$what abgelehnt: code=$code desc='$desc' roh=${bytes.joinToString("") { "%02x".format(it) }}"
                .take(400)
        )
        throw TikTokDmException(code, "$what fehlgeschlagen (Code $code${if (desc.isNotEmpty()) ": $desc" else ""})")
    }

    suspend fun erneuereMedienUrl(context: Context, convId: String) {
        val jetzt = System.currentTimeMillis()
        if (jetzt - letzteUrlErneuerung < URL_ERNEUERUNG_SPERRE_MS) return
        letzteUrlErneuerung = jetzt
        runCatching { openConversation(context, convId) }
            .onFailure { Log.w(TAG, "Medien-URL-Erneuerung fehlgeschlagen: ${it.message}") }
    }

    suspend fun openConversation(context: Context, convId: String) = withContext(Dispatchers.IO) {
        _loadingConversation.value = convId
        try {
            val session = TikTokCookieRepository.session(context)
            val meta = convMeta(context, convId) ?: return@withContext
            val page = fetchConversationPage(session, meta, System.currentTimeMillis() * 1000L)
            hasMoreConv[convId] = page.hasMore
            merge(convId, page, replace = true)
            cacheFensterRaeumen(convId, page.messages)
        } finally {
            _loadingConversation.value = null
        }
    }

    suspend fun loadMore(context: Context, convId: String) = withContext(Dispatchers.IO) {
        if (hasMoreConv[convId] != true) return@withContext
        val oldest = _feed.value.messages
            .filter { it.convId == convId }
            .minOfOrNull { it.timestamp }
            ?: return@withContext
        _loadingConversation.value = convId
        try {
            val session = TikTokCookieRepository.session(context)
            val meta = convMeta(context, convId) ?: return@withContext
            val page = fetchConversationPage(session, meta, oldest * 1000L)
            hasMoreConv[convId] = page.hasMore
            merge(convId, page, replace = false)
        } finally {
            _loadingConversation.value = null
        }
    }

    suspend fun resolvePeerName(context: Context, peerId: String): String? = withContext(Dispatchers.IO) {
        if (peerId.isBlank()) return@withContext null
        val session = runCatching { TikTokCookieRepository.session(context) }.getOrNull()
            ?: return@withContext null
        fetchProfiles(session.cookie, listOf(peerId))[peerId]
    }

    suspend fun encryptedMediaFile(context: Context, url: String, keyHex: String): File? =
        withContext(Dispatchers.IO) {
            if (keyHex.isEmpty()) return@withContext null
            val dir = File(context.cacheDir, "tiktok_media").apply { mkdirs() }
            val target = File(dir, mediaCacheName(url))
            if (target.exists() && target.length() > 0L) return@withContext target
            val encrypted = runCatching { getBytes(url, sessionCookie(context)) }.getOrNull()
            if (encrypted == null) {
                Log.w(TAG, "Medium nicht ladbar (Cookie oder URL abgelaufen): ${hostOf(url)}")
                return@withContext null
            }
            val plain = runCatching { decryptGcm(encrypted, keyHex) }.getOrNull()
            if (plain == null) {
                Log.w(TAG, "Medium nicht entschlüsselbar: ${hostOf(url)}")
                target.delete()
                return@withContext null
            }
            runCatching { target.writeBytes(plain) }.getOrNull()
            if (target.exists()) target else null
        }

    private fun mediaCacheName(url: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { String.format("%02x", it.toInt() and 0xFF) } + ".img"
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    private suspend fun sessionCookie(context: Context): String =
        TikTokCookieRepository.session(context).cookie

    private fun decryptGcm(data: ByteArray, keyHex: String): ByteArray {
        val key = ByteArray(keyHex.length / 2) {
            ((keyHex[it * 2].digitToInt(16) shl 4) or keyHex[it * 2 + 1].digitToInt(16)).toByte()
        }
        val iv = data.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }

    suspend fun send(context: Context, convId: String, peerId: String, text: String): String =
        withContext(Dispatchers.IO) {
            val foreign = TikTokSendPolicy.blockedForeign(convId)
            if (foreign.isNotEmpty()) {
                throw TikTokDmException(
                    403,
                    "Senden ist im Testmodus auf eigene Accounts beschränkt (${foreign.joinToString(",")})"
                )
            }
            if (!TikTokSendPolicy.recipientAllowed(peerId)) {
                throw TikTokDmException(403, "Senden ist ausschließlich an programmi erlaubt")
            }
            val clean = text.trim()
            if (clean.isEmpty()) throw TikTokDmException(400, "Leere Nachricht")
            val session = TikTokCookieRepository.session(context)
            var meta = _feed.value.convMeta[convId]
            if (meta == null || meta.ticket.isEmpty() || meta.convShortId == 0L) {
                meta = fetch(session).convMeta[convId]
            }
            if (meta == null || meta.ticket.isEmpty() || meta.convShortId == 0L) {
                throw TikTokDmException(409, "Chat-Metadaten (Ticket/ShortId) nicht gefunden")
            }
            val content = JSONObject().put("aweType", 0).put("text", clean).toString()
            val clientMessageId = "${System.currentTimeMillis()}${(100..999).random()}"
            val body = TikTokProto.buildSendRequest(buildParams(session), session.deviceId, meta, content, clientMessageId)
            val result = TikTokProto.parseSendResponse(post(SEND_URL, session.cookie, body))
            if (result.newTicket.isNotEmpty()) {
                _feed.value = _feed.value.copy(
                    convMeta = _feed.value.convMeta + (convId to meta.copy(ticket = result.newTicket))
                )
            }
            if (result.statusCode != 0) {
                throw TikTokDmException(
                    result.statusCode,
                    "TikTok: ${result.errorDesc.ifEmpty { "Status ${result.statusCode}" }}"
                )
            }
            if (result.innerStatus != 0) {
                throw TikTokDmException(result.innerStatus, "TikTok: Nachricht abgelehnt (Status ${result.innerStatus})")
            }
            if (result.serverMessageId > 0L) {
                val message = TikTokMessage(
                    id = result.serverMessageId.toString(),
                    convId = convId,
                    convHash = meta.convShortId,
                    senderId = _feed.value.selfId,
                    text = clean,
                    timestamp = System.currentTimeMillis(),
                    isOwn = true
                )
                merge(convId, TikTokPage(listOf(message), 0L, false), replace = false)
            }
            "Gesendet ($clientMessageId)"
        }

    private suspend fun convMeta(context: Context, convId: String): TikTokConvMeta? {
        val session = TikTokCookieRepository.session(context)
        return convMeta(session, convId)
    }

    private fun convMeta(session: TikTokSession, convId: String): TikTokConvMeta? {
        val cached = _feed.value.convMeta[convId]
        if (cached != null) return cached
        val fresh = fetch(session).convMeta[convId] ?: return null
        _feed.value = _feed.value.copy(convMeta = _feed.value.convMeta + (convId to fresh))
        return fresh
    }

    private fun mediaUrl(
        session: TikTokSession,
        meta: TikTokConvMeta,
        nachricht: TikTokMessage
    ): String {
        if (nachricht.id.isEmpty() || meta.convId.isEmpty()) return ""
        val felder = appParams(session) + mapOf(
            "conversation_type" to meta.convType.toString(),
            "conversation_id" to meta.convId,
            "conversation_short_id" to meta.convShortId.toString(),
            "inbox_type" to UNMASK_INBOX_TYP.toString(),
            "selected_server_message_id" to nachricht.id,
            "scene" to UNMASK_SZENE.toString()
        )
        val query = felder.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "$key=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
        val unix = System.currentTimeMillis() / 1000L
        val kopf = runCatching {
            TikTokSignatur.signiere(
                params = query,
                payload = "",
                cookie = session.cookie,
                unix = unix,
                deviceId = session.deviceId,
                versionName = APP_VERSION_NAME,
                deviceType = APP_DEVICE_TYPE,
                osVersion = APP_OS_VERSION,
                channel = APP_CHANNEL
            )
        }.getOrNull() ?: return ""
        val antwort = runCatching {
            postMitKopf("$UNMASK_URL?$query", session.cookie, APP_USER_AGENT, kopf)
        }.getOrNull() ?: return ""
        val url = TikTokProto.parseAntwortUrl(antwort)
        Log.i(
            TAG,
            "Unmask msg=${nachricht.id} status=${TikTokProto.statusCode(antwort)} " +
                "url=${if (url.isEmpty()) "-" else "ja"}"
        )
        return url
    }

    private fun appParams(session: TikTokSession): Map<String, String> {
        val zeit = System.currentTimeMillis()
        val kennung = session.deviceId.ifEmpty { "0" }
        val hex = kennung.toLongOrNull()?.let { String.format("%016x", it) } ?: kennung
        return linkedMapOf(
            "ab_version" to APP_VERSION_NAME,
            "ac" to "wifi",
            "ac2" to "wifi5g",
            "aid" to "1233",
            "app_language" to "de",
            "app_name" to "musical_ly",
            "app_type" to "normal",
            "build_number" to APP_VERSION_NAME,
            "carrier_region" to "DE",
            "cdid" to "cdid-$kennung",
            "channel" to APP_CHANNEL,
            "current_region" to "DE",
            "device_brand" to "Google",
            "device_id" to kennung,
            "device_platform" to "android",
            "device_type" to APP_DEVICE_TYPE,
            "dpi" to "420",
            "host_abi" to "arm64-v8a",
            "iid" to kennung,
            "install_id" to kennung,
            "is_pad" to "0",
            "language" to "de",
            "last_install_time" to (zeit / 1000L - 86_400L * 30L).toString(),
            "locale" to "de_DE",
            "manifest_version_code" to APP_VERSION_CODE,
            "openudid" to hex,
            "os" to "android",
            "os_api" to "33",
            "os_version" to APP_OS_VERSION,
            "region" to "DE",
            "residence" to "DE",
            "resolution" to "1080*2400",
            "ssmix" to "0",
            "timezone_name" to "Europe/Berlin",
            "timezone_offset" to "7200",
            "ts" to (zeit / 1000L).toString(),
            "uoo" to "0",
            "update_version_code" to APP_VERSION_CODE,
            "version_code" to APP_VERSION_CODE,
            "version_name" to APP_VERSION_NAME,
            "_rticket" to zeit.toString()
        )
    }

    private fun postMitKopf(
        url: String,
        cookie: String,
        userAgent: String,
        kopf: Map<String, String>
    ): ByteArray {
        val quelle = quelleVon(url)
        TikTokDump.schreibe(context, quelle, "anfrage", url.toByteArray())
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Cookie", cookie)
            for ((name, wert) in kopf) connection.setRequestProperty(name, wert)
            connection.outputStream.use { it.write(ByteArray(0)) }
            val code = connection.responseCode
            val antwort = if (code in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            TikTokDump.schreibe(context, quelle, "antwort", antwort, code)
            return antwort
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun merge(convId: String, page: TikTokPage, replace: Boolean) {        val current = _feed.value
        val kept = current.messages.filterNot { it.convId == convId }
        val existing = if (replace) emptyList() else current.messages.filter { it.convId == convId }
        val byId = LinkedHashMap<String, TikTokMessage>()
        for (message in existing + page.messages) {
            val key = message.id.ifEmpty { "${message.convId}:${message.timestamp}:${message.text}" }
            byId[key] = message
        }
        val merged = (kept + byId.values).sortedBy { it.timestamp }
        val streaks = mergeStreaks(current.streaks, page.streaks.filterKeys { it == convId })
        _feed.value = applyStreaks(
            current.copy(
                messages = merged,
                hasMore = hasMoreConv.filterValues { it }.keys.toSet(),
                streaks = streaks,
                conversations = current.conversations.map {
                    streaks[it.convId]?.let { state -> it.copy(streak = state) } ?: it
                }
            )
        )
        persistIfChanged(current.selfId.ifEmpty { selfId }, _feed.value)
        reportIncoming(context, current.messages, merged, current.selfId.ifEmpty { selfId })
    }

    private suspend fun reportIncoming(
        ctx: Context?,
        before: List<TikTokMessage>,
        after: List<TikTokMessage>,
        self: String
    ) {
        if (ctx == null || self.isEmpty()) return
        val known = before.mapTo(HashSet()) { messageKey(it) }
        val fresh = after.filter { !known.contains(messageKey(it)) && !it.isOwn }
        val names = _feed.value.conversations.associateBy { it.convId }
        for (message in fresh) {
            val conversation = names[message.convId]
            runCatching {
                TikTokPushStore.report(
                    ctx,
                    message.convId,
                    conversation?.peerId.orEmpty(),
                    conversation?.name.orEmpty(),
                    message.text
                )
            }
        }
    }

    private fun fetch(session: TikTokSession): TikTokFeed {
        val params = buildParams(session)
        val body = TikTokProto.buildInitRequest(params, session.deviceId)
        return TikTokProto.parse(post(INIT_URL, session.cookie, body))
    }

    private fun fetchConversationPage(
        session: TikTokSession,
        meta: TikTokConvMeta,
        anchorIndex: Long
    ): TikTokPage {
        val params = buildParams(session)
        val body = TikTokProto.buildConversationRequest(params, session.deviceId, meta, anchorIndex)
        val seite = TikTokProto.parseConversationPage(post(CONV_URL, session.cookie, body), selfId)
        return seite.copy(messages = seite.messages.map { nachricht ->
            if (nachricht.mediaKind != "voice" || nachricht.mediaStream.isNotEmpty()) return@map nachricht
            val url = mediaUrl(session, meta, nachricht)
            if (url.isEmpty()) nachricht else nachricht.copy(mediaUrl = url, mediaStream = url)
        })
    }

    private fun resolveNames(cookie: String, feed: TikTokFeed): TikTokFeed {
        val peerIds = feed.conversations.map { it.peerId }.filter { it.isNotEmpty() }.distinct()
        if (peerIds.isEmpty()) return feed
        val names = fetchProfiles(cookie, peerIds)
        if (names.isEmpty()) return feed
        return feed.copy(conversations = feed.conversations.map { conversation ->
            val name = names[conversation.peerId]
            if (name.isNullOrEmpty()) conversation else conversation.copy(name = name)
        })
    }

    private fun buildParams(session: TikTokSession): Map<String, String> {
        val cookies = parseCookies(session.cookie)
        val verifyFp = cookies["s_v_web_id"].orEmpty()
        val msToken = cookies["msToken"].orEmpty()
        return linkedMapOf(
            "aid" to "1988",
            "app_name" to "tiktok_web",
            "channel" to "web",
            "device_platform" to "web_pc",
            "device_id" to session.deviceId,
            "region" to "DE",
            "priority_region" to "DE",
            "os" to "linux",
            "referer" to "https://www.tiktok.com/messages",
            "root_referer" to "",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "de",
            "browser_platform" to "Linux x86_64",
            "browser_name" to "Mozilla",
            "browser_version" to "5.0 (X11)",
            "browser_online" to "true",
            "verifyFp" to verifyFp,
            "app_language" to "de-DE",
            "webcast_language" to "de-DE",
            "tz_name" to "Europe/Berlin",
            "is_page_visible" to "true",
            "focus_state" to "true",
            "is_fullscreen" to "false",
            "history_len" to "2",
            "user_is_login" to "true",
            "data_collection_enabled" to "true",
            "from_appID" to "1988",
            "locale" to "de-DE",
            "user_agent" to USER_AGENT,
            "Web-Sdk-Ms-Token" to msToken
        )
    }

    private fun fetchProfiles(cookie: String, uids: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (chunk in uids.chunked(20)) {
            val ids = chunk.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "$PROFILE_URL?aid=1988&user_ids=${URLEncoder.encode(ids, "UTF-8")}"
            val body = get(url, cookie) ?: continue
            val users = runCatching { JSONObject(body).optJSONArray("users") }.getOrNull() ?: continue
            for (index in 0 until users.length()) {
                val profile = users.optJSONObject(index)?.optJSONObject("im_user_profile") ?: continue
                val uid = profile.optString("user_id").ifEmpty { profile.optString("user_id_str") }
                if (uid.isEmpty()) continue
                val name = profile.optString("nick_name").ifEmpty { profile.optString("unique_id") }
                if (name.isNotEmpty()) result[uid] = name
            }
        }
        return result
    }

    private fun parseCookies(cookie: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in cookie.split(';')) {
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            result[trimmed.substring(0, separator)] = trimmed.substring(separator + 1)
        }
        return result
    }

    private fun post(url: String, cookie: String, body: ByteArray): ByteArray {
        val quelle = quelleVon(url)
        TikTokDump.schreibe(context, quelle, "anfrage", body)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("Accept", "application/x-protobuf")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Origin", "https://www.tiktok.com")
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cookie", cookie)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                TikTokDump.schreibe(context, quelle, "antwort", detail.toByteArray(), code)
                throw TikTokDmException(code, detail.ifEmpty { "HTTP $code" })
            }
            val antwort = connection.inputStream.use { it.readBytes() }
            TikTokDump.schreibe(context, quelle, "antwort", antwort, TikTokProto.statusCode(antwort))
            return antwort
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytes(url: String, cookie: String): ByteArray? {
        val quelle = quelleVon(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cookie", cookie)
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            TikTokDump.schreibe(context, quelle, "antwort", bytes)
            bytes
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, cookie: String): String? {
        val quelle = quelleVon(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Origin", "https://www.tiktok.com")
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cookie", cookie)
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            TikTokDump.schreibe(context, quelle, "antwort", text.toByteArray())
            text
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun quelleVon(url: String): String = when {
        url.contains("get_by_user_init") -> "init"
        url.contains("get_by_conversation") -> "conversation"
        url.endsWith("/send") -> "send"
        url.contains("mark_read") -> "markread"
        url.contains("recall") -> "recall"
        url.contains("delete") -> "delete"
        url.contains("set_property") -> "eigenschaft"
        url.contains("/profile/") -> "profil"
        else -> url.substringAfterLast('/').take(24).ifEmpty { "unbekannt" }
    }
}
