package com.tabslify.tabs.tiktok

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.tabslify.core.logging.Log
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
    private const val CONV_MARK_READ_URL = "https://api16-normal-useastred.tiktokv.eu/v3/conversation/mark_read"
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
    private const val ACTIVE_POLL_INTERVAL_MS = 5_000L
    private const val MARK_READ_INTERVAL_MS = 15_000L
    private const val FRONTIER_FOLLOWUP_DELAY_MS = 1_200L
    private const val STREAK_REFRESH_SPERRE_MS = 3L * 60L * 1000L
    private const val STREAK_REFRESH_MAX = 4
    private const val STREAK_MAX_FEHLVERSUCHE = 3
    private const val STREAK_BACKLOG_RUNDEN = 6
    private const val STREAK_BACKLOG_PAUSE_MS = 1_500L
    private const val STREAK_REFRESH_SEITEN = 4
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0"

    private val _feed = MutableStateFlow(TikTokFeed(emptyList(), emptyList()))
    val feed: StateFlow<TikTokFeed> = _feed.asStateFlow()

    private val _loadingConversation = MutableStateFlow<String?>(null)
    val loadingConversation: StateFlow<String?> = _loadingConversation.asStateFlow()

    private val hasMoreConv = mutableMapOf<String, Boolean>()
    private var selfId = ""
    private var context: Application? = null
    private var pollScope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var pollRefs = 0
    private var lastUrlRenewal = 0L
    private var cachedFingerprint: String = ""
    private var streakFingerprintSaved: String = ""
    private var openConvId: String = ""
    private var openConvJob: Job? = null
    private var followUpJob: Job? = null
    private var isForeground: Boolean = true
    private var lastMarkReadAt: Long = 0L
    private var lastMarkReadConv: String = ""

    fun currentSelfId(): String = selfId

    fun frontierParams(session: TikTokSession): Map<String, String> = buildParams(session)

    fun frontierIndices(): List<Long> =
        _feed.value.convMeta.values
            .map { it.convShortId }
            .filter { it > 0L }
            .distinct()

    fun currentConversation(convId: String): TikTokConversation? =
        _feed.value.conversations.firstOrNull { it.convId == convId }

    fun openConversationId(): String = openConvId

    fun startPolling(context: Context) {
        val app = context.applicationContext
        this@TikTokDmClient.context = app as Application
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
            openConvJob?.cancel()
            openConvJob = null
            followUpJob?.cancel()
            followUpJob = null
            TikTokFrontier.stop()
        }
    }

    fun setOpenConversation(convId: String) {
        val app = context ?: return
        synchronized(this) {
            if (openConvId == convId) return
            openConvId = convId
            openConvJob?.cancel()
            openConvJob = null
            if (convId.isEmpty()) return
            val scope = pollScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
                pollScope = it
            }
            openConvJob = scope.launch {
                while (isActive) {
                    delay(ACTIVE_POLL_INTERVAL_MS)
                    if (openConvId != convId) break
                    if (!isForeground) continue
                    runCatching { openConversation(app, convId, mergeOnly = true) }
                        .onFailure { Log.w(TAG, "Live sync failed: ${it.message}") }
                    if (openConvId != convId) break
                    val unread = _feed.value.conversations
                        .firstOrNull { it.convId == convId }?.unread ?: 0
                    if (unread > 0 && canMarkRead(convId)) {
                        runCatching { markRead(app, convId) }
                            .onFailure { Log.w(TAG, "Live mark-read failed: ${it.message}") }
                    }
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    private fun canMarkRead(convId: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastMarkReadAt < MARK_READ_INTERVAL_MS) return false
        lastMarkReadAt = now
        lastMarkReadConv = convId
        return true
    }

    fun refreshOpenConversationSoon(convId: String = openConvId) {
        if (convId.isEmpty()) return
        val app = context ?: return
        val scope = pollScope ?: return
        synchronized(this) {
            if (followUpJob?.isActive == true) return
            followUpJob = scope.launch {
                delay(FRONTIER_FOLLOWUP_DELAY_MS)
                if (openConvId == convId) {
                    runCatching { openConversation(app, convId, mergeOnly = true) }
                        .onFailure { Log.w(TAG, "Frontier follow-up failed: ${it.message}") }
                }
                load(app)
                if (openConvId == convId) {
                    val unread = _feed.value.conversations
                        .firstOrNull { it.convId == convId }
                        ?.unread ?: 0
                    if (unread > 0 && canMarkRead(convId)) {
                        runCatching { markRead(app, convId) }
                            .onFailure { Log.w(TAG, "Auto mark-read failed: ${it.message}") }
                    }
                }
            }
        }
    }

    private val streakVersuche = mutableMapOf<String, Long>()
    private val streakFehlversuche = mutableMapOf<String, Int>()

    fun startStreakBacklog(context: Context) {
        val app = context.applicationContext as Application
        this@TikTokDmClient.context = app
        val scope = pollScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            pollScope = it
        }
        scope.launch {
            val session = runCatching { TikTokCookieRepository.session(app) }.getOrNull()
                ?: return@launch
            repeat(STREAK_BACKLOG_RUNDEN) {
                val versucheVorher = streakVersuche.size
                backlogStreaks(app, _feed.value, session)
                if (streakVersuche.isEmpty() && streakFehlversuche.size == versucheVorher) {
                    return@launch
                }
                delay(STREAK_BACKLOG_PAUSE_MS)
            }
        }
    }

    private suspend fun backlogStreaks(
        context: Context,
        feed: TikTokFeed,
        session: TikTokSession
    ) {
        val now = System.currentTimeMillis()
        val offen = feed.conversations
            .filter { it.streak.value == null }
            .filter { (streakFehlversuche[it.convId] ?: 0) < STREAK_MAX_FEHLVERSUCHE }
            .filter { conversation ->
                val last = streakVersuche[conversation.convId] ?: 0L
                now - last >= STREAK_REFRESH_SPERRE_MS
            }
            .sortedBy { streakVersuche[it.convId] ?: 0L }
        if (offen.isEmpty()) return
        var loaded = 0
        for (conversation in offen) {
            if (loaded >= STREAK_REFRESH_MAX) break
            val meta = feed.convMeta[conversation.convId] ?: continue
            if (meta.convShortId == 0L) continue
            loaded++
            streakVersuche[conversation.convId] = now
            runCatching {
                sucheStreakZahl(session, meta)?.let { newValue ->
                    val current = _feed.value
                    val before = current.streaks[meta.convId] ?: conversation.streak
                    val neu = if (!before.active && !before.restorable) {
                        before.copy(value = newValue, active = true)
                    } else {
                        before.copy(value = newValue)
                    }
                    val gemergt = current.streaks + (meta.convId to neu)
                    _feed.value = applyStreaks(
                        current.copy(
                            streaks = gemergt,
                            conversations = current.conversations.map { c ->
                                gemergt[c.convId]?.let { c.copy(streak = it) } ?: c
                            }
                        )
                    )
                    persistIfChanged(current.selfId.ifEmpty { selfId }, _feed.value)
                    streakVersuche.remove(meta.convId)
                    streakFehlversuche.remove(meta.convId)
                    Log.i(TAG, "Serienzahl nachgeladen: ${meta.convId.takeLast(8)} -> $newValue")
                } ?: run {
                    val previous = streakFehlversuche[meta.convId] ?: 0
                    streakFehlversuche[meta.convId] = previous + 1
                    Log.i(
                        TAG,
                        "Keine Serienzahl: ${meta.convId.takeLast(8)} " +
                            "(Versuch ${previous + 1}/$STREAK_MAX_FEHLVERSUCHE)"
                    )
                }
            }.onFailure {
                Log.w(TAG, "Serienzahl nicht nachladbar: ${it.message}")
            }
        }
    }

    private suspend fun sucheStreakZahl(
        session: TikTokSession,
        meta: TikTokConvMeta
    ): Int? {
        val self = selfId.ifEmpty { _feed.value.selfId }
        if (self.isEmpty()) return null
        var anchor = System.currentTimeMillis() * 1000L
        for (page in 0 until STREAK_REFRESH_SEITEN) {
            val body = buildParams(session).let { params ->
                TikTokProto.buildConversationRequest(
                    params,
                    session.deviceId,
                    meta,
                    anchor
                )
            }
            val page = TikTokProto.parseConversationPage(
                post(CONV_URL, session.cookie, body),
                self
            )
            if (page.messages.isEmpty()) return null
            val value = page.streaks[meta.convId]
            if (value != null && value.value != null) return value.value
            val oldest = page.messages.minOfOrNull { it.timestamp } ?: return null
            val newAnchor = oldest * 1000L
            if (newAnchor >= anchor) return null
            anchor = newAnchor
            if (!page.hasMore) return null
        }
        return null
    }

    suspend fun load(context: Context): TikTokFeed = withContext(Dispatchers.IO) {        this@TikTokDmClient.context = context.applicationContext as Application
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
                    Log.i("TikTokTab", "Feed auf $FEED_MESSAGE_LIMIT Nachrichten begrenzt (war ${list.size})")
                    list.takeLast(FEED_MESSAGE_LIMIT)
                } else list
            }
            val merged = named.copy(
                messages = messages,
                hasMore = current.hasMore,
                streaks = streaks,
                conversations = named.conversations.map { conversation ->
                    val alt = current.conversations.firstOrNull { it.convId == conversation.convId }
                    conversation.copy(
                        name = conversation.name.ifEmpty { alt?.name.orEmpty() },
                        avatarUrl = conversation.avatarUrl.ifEmpty { alt?.avatarUrl.orEmpty() },
                        handle = conversation.handle.ifEmpty { alt?.handle.orEmpty() },
                        streak = streaks[conversation.convId]
                            ?: alt?.streak
                            ?: TikTokStreak()
                    )
                }
            )
            _feed.value = applyStreaks(merged)
            persistIfChanged(named.selfId, _feed.value)
            backlogStreaks(context, _feed.value, session)
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
            .mapValues { (_, list) -> list.minOf { it.timestamp } }

    private suspend fun clearCacheWindow(convId: String, serverseitig: List<TikTokMessage>) {
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
            val limitBytes = serverseitig
                .filter { it.convId == convId }
                .minOfOrNull { it.timestamp }
                ?: return@runCatching
            dao.deleteStaleImFenster(convId, limitBytes, behalten)
        }.onFailure {
            Log.w(TAG, "Veraltete Nachrichten nicht bereinigt: ${it.message}")
        }
    }

    private suspend fun cacheDeletion(messageId: Long) {
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
            val newest = rows.groupBy { it.convId }.mapValues { (_, list) ->
                list.maxByOrNull { it.timestamp }
            }
            val stored = db.conversationDao().all().associateBy { it.convId }
            db.conversationDao().upsertAll(feed.conversations.map { conversation ->
                val current = newest[conversation.convId]
                val alt = stored[conversation.convId]
                val row = conversation.toCached(feed.convMeta[conversation.convId])
                val withNames = if (row.name.isEmpty() && alt != null) {
                    row.copy(name = alt.name)
                } else {
                    row
                }
                val withImage = if (withNames.avatarUrl.isEmpty() && alt != null) {
                    withNames.copy(avatarUrl = alt.avatarUrl, handle = withNames.handle.ifEmpty { alt.handle })
                } else {
                    withNames
                }
                if (current == null) withImage
                else withImage.copy(lastTimestamp = current.timestamp, preview = current.text)
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
                        convId = row.convId,
                        peerId = row.peerId,
                        name = row.name,
                        lastTimestamp = row.lastTimestamp,
                        lastMessage = row.preview,
                        unread = row.unread,
                        streak = TikTokStreak(
                            active = row.streakActive,
                            restorable = row.streakRestorable,
                            value = row.streakValue.takeIf { it > 0 }
                        ),
                        reactions = emptyList(),
                        avatarUrl = row.avatarUrl,
                        handle = row.handle,
                        lastKind = row.lastKind,
                        lastIsOwn = row.lastIsOwn
                    )
                },
                messages = messages,
                selfId = selfId,
                convMeta = emptyMap()
            )
        }.getOrNull()
    }

    private fun TikTokConversation.toCached(meta: TikTokConvMeta?) = CachedConversation(
        convId = convId,
        peerId = peerId,
        name = name,
        lastTimestamp = lastTimestamp,
        preview = lastMessage,
        unread = unread,
        streakValue = streak.value ?: 0,
        streakActive = streak.active,
        streakRestorable = streak.restorable,
        updatedAt = System.currentTimeMillis(),
        maxIndex = meta?.maxIndex ?: 0L,
        readIndex = meta?.readIndex ?: 0L,
        avatarUrl = avatarUrl,
        handle = handle,
        lastKind = lastKind,
        lastIsOwn = lastIsOwn
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
        mediaVid = mediaVid,
        unknownType = unknownType,
        myReaction = myReaction,
        peerReaction = peerReaction,
        replyToId = replyToId,
        replyToText = replyToText,
        replyToQuote = replyToQuote
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
        mediaVid = mediaVid,
        unknownType = unknownType,
        myReaction = myReaction,
        peerReaction = peerReaction,
        replyToId = replyToId,
        replyToText = replyToText,
        replyToQuote = replyToQuote
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
        prefs.edit { putString(PREFS_STREAK, obj.toString()) }
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
                val neu = if (add) (m.reactions + emoji).distinct()
                else m.reactions.filterNot { it == emoji }
                m.copy(
                    reactions = neu,
                    peerReaction = if (add) emoji else if (m.peerReaction == emoji) "" else m.peerReaction
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

    suspend fun applyFrontierMessages(messages: List<TikTokMessage>) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        val current = _feed.value
        val known = current.messages.mapTo(HashSet()) { messageKey(it) }
        val fresh = messages.filter { !known.contains(messageKey(it)) }
        if (fresh.isEmpty()) return@withContext
        val merged = (current.messages + fresh).sortedBy { it.timestamp }
        _feed.value = applyStreaks(
            current.copy(
                messages = merged,
                conversations = mergeConversationRows(current.conversations, fresh)
            )
        )
        persistIfChanged(current.selfId.ifEmpty { selfId }, _feed.value)
        writeCache(_feed.value)
        Log.i(TAG, "Frontier messages: ${fresh.size}")
    }

    private fun mergeConversationRows(
        existing: List<TikTokConversation>,
        fresh: List<TikTokMessage>
    ): List<TikTokConversation> {
        if (fresh.isEmpty()) return existing
        val byConv = fresh.groupBy { it.convId }
        val openId = openConvId
        val updated = existing.map { conversation ->
            val newMessages = byConv[conversation.convId] ?: return@map conversation
            val latest = newMessages.maxByOrNull { it.timestamp } ?: return@map conversation
            val incoming = newMessages.count { !it.isOwn }
            val nextUnread = when {
                conversation.convId == openId -> 0
                incoming == 0 -> conversation.unread
                else -> (conversation.unread + incoming).coerceAtMost(999)
            }
            conversation.copy(
                lastTimestamp = maxOf(conversation.lastTimestamp, latest.timestamp),
                lastMessage = latest.text.ifEmpty { conversation.lastMessage },
                lastKind = latest.mediaKind.ifEmpty { conversation.lastKind },
                lastIsOwn = latest.isOwn,
                unread = nextUnread
            )
        }
        val knownIds = existing.mapTo(HashSet()) { it.convId }
        val created = byConv.entries
            .filter { it.key !in knownIds }
            .map { (convId, list) ->
                val latest = list.maxByOrNull { it.timestamp } ?: list.first()
                TikTokConversation(
                    convId = convId,
                    peerId = list.firstOrNull { it.senderId.isNotEmpty() && !it.isOwn }?.senderId.orEmpty(),
                    name = "",
                    lastTimestamp = latest.timestamp,
                    lastMessage = latest.text,
                    unread = if (convId == openId) 0 else list.count { !it.isOwn },
                    lastKind = latest.mediaKind,
                    lastIsOwn = latest.isOwn
                )
            }
        return (updated + created).sortedByDescending { it.lastTimestamp }
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
        val before = message.myReaction
        if (add && before.isNotEmpty() && before != emoji) {
            val removeItem = TikTokProto.buildReactionRequest(
                buildParams(session),
                session.deviceId,
                meta.convId,
                meta.convType,
                meta.convShortId,
                id,
                before,
                false
            )
            ensureOk(post(REACTION_URL, session.cookie, removeItem), "Alte Reaktion removeItem")
            applyOwnReaction(message.id, message.convId, before, false)
        }
        if (!add && before.isEmpty()) throw TikTokDmException(400, "Keine eigene Reaktion vorhanden")
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
        applyOwnReaction(message.id, message.convId, emoji, add)
        if (add) "Reaktion gesetzt" else "Reaktion entfernt"
    }

    private fun applyOwnReaction(messageId: String, convId: String, emoji: String, add: Boolean) {
        val current = _feed.value
        val messages = current.messages.map { m ->
            if (m.id != messageId) {
                m
            } else if (add) {
                m.copy(
                    reactions = (m.reactions + emoji).distinct(),
                    myReaction = emoji
                )
            } else {
                m.copy(
                    reactions = m.reactions.filterNot { it == emoji || it == m.myReaction },
                    myReaction = ""
                )
            }
        }
        val conversations = current.conversations.map { c ->
            if (c.convId != convId) c
            else c.copy(
                reactions = messages
                    .filter { it.convId == c.convId }
                    .flatMap { it.reactions }
                    .distinct()
            )
        }
        _feed.value = current.copy(messages = messages, conversations = conversations)
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
            cacheDeletion(messageId)
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
            cacheDeletion(messageId)
            "Nachricht gelöscht"
        }

    suspend fun markRead(context: Context, convId: String) = withContext(Dispatchers.IO) {
        val session = runCatching { TikTokCookieRepository.session(context) }.getOrNull()
            ?: return@withContext
        val current = _feed.value
        var meta = current.convMeta[convId]
        if (meta == null || meta.convShortId == 0L) {
            meta = runCatching { fetch(session).convMeta[convId] }.getOrNull()
                ?: return@withContext
            _feed.value = _feed.value.copy(convMeta = _feed.value.convMeta + (convId to meta))
        }
        val letzteMs = _feed.value.messages
            .filter { it.convId == convId }
            .maxOfOrNull { it.timestamp }
        val readStampUs = if (letzteMs != null && letzteMs > 0L) {
            letzteMs * 1000L
        } else {
            System.currentTimeMillis() * 1000L
        }
        val body = TikTokProto.buildMarkReadRequest(
            buildParams(session),
            session.deviceId,
            meta,
            readStampUs
        )
        val response = runCatching {
            ensureOk(post(CONV_MARK_READ_URL, session.cookie, body), "Als gelesen markieren")
        }
        if (response.isSuccess) {
            val updatedMeta = _feed.value.convMeta[convId]?.copy(
                unread = 0,
                readIndex = meta.maxIndex
            ) ?: meta.copy(unread = 0, readIndex = meta.maxIndex)
            _feed.value = _feed.value.copy(
                conversations = _feed.value.conversations.map {
                    if (it.convId == convId) it.copy(unread = 0) else it
                },
                convMeta = _feed.value.convMeta + (convId to updatedMeta)
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

    suspend fun renewMediaUrl(context: Context, convId: String) {
        val now = System.currentTimeMillis()
        if (now - lastUrlRenewal < URL_ERNEUERUNG_SPERRE_MS) return
        lastUrlRenewal = now
        runCatching { openConversation(context, convId) }
            .onFailure { Log.w(TAG, "Medien-URL-Erneuerung fehlgeschlagen: ${it.message}") }
    }

    suspend fun openConversation(
        context: Context,
        convId: String,
        mergeOnly: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (!mergeOnly) _loadingConversation.value = convId
        try {
            val session = TikTokCookieRepository.session(context)
            val meta = convMeta(context, convId) ?: return@withContext
            val page = fetchConversationPage(session, meta, System.currentTimeMillis() * 1000L)
            hasMoreConv[convId] = page.hasMore
            merge(convId, page, replace = !mergeOnly)
            if (!mergeOnly) clearCacheWindow(convId, page.messages)
        } finally {
            if (!mergeOnly) _loadingConversation.value = null
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
        fetchProfiles(session.cookie, listOf(peerId))[peerId]?.name
    }

    suspend fun resolveProfile(context: Context, uid: String): TikTokProfile? =
        withContext(Dispatchers.IO) {
            if (uid.isBlank()) return@withContext null
            val session = runCatching { TikTokCookieRepository.session(context) }.getOrNull()
                ?: return@withContext null
            fetchProfiles(session.cookie, listOf(uid))[uid]
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
            val clean = text.trim()
            if (clean.isEmpty()) throw TikTokDmException(400, "Leere Nachricht")
            val session = TikTokCookieRepository.session(context)
            var meta = _feed.value.convMeta[convId]
            if (meta == null || meta.ticket.isEmpty() || meta.convShortId == 0L) {
                meta = fetch(session).convMeta[convId]
            }
            if (meta == null || meta.ticket.isEmpty() || meta.convShortId == 0L) {
                throw TikTokDmException(409, "Chat-Metadaten (Ticket/ShortId) nicht found")
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
        message: TikTokMessage
    ): String {
        if (message.id.isEmpty() || meta.convId.isEmpty()) return ""
        val fields = appParams(session) + mapOf(
            "conversation_type" to meta.convType.toString(),
            "conversation_id" to meta.convId,
            "conversation_short_id" to meta.convShortId.toString(),
            "inbox_type" to UNMASK_INBOX_TYP.toString(),
            "selected_server_message_id" to message.id,
            "scene" to UNMASK_SZENE.toString()
        )
        val query = fields.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "$key=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
        val unix = System.currentTimeMillis() / 1000L
        val header = runCatching {
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
        val response = runCatching {
            postMitKopf("$UNMASK_URL?$query", session.cookie, APP_USER_AGENT, header)
        }.getOrNull() ?: return ""
        val url = TikTokProto.parseResponseUrl(response)
        Log.i(
            TAG,
            "Unmask msg=${message.id} status=${TikTokProto.statusCode(response)} " +
                "url=${if (url.isEmpty()) "-" else "ja"}"
        )
        return url
    }

    private fun appParams(session: TikTokSession): Map<String, String> {
        val time = System.currentTimeMillis()
        val identifier = session.deviceId.ifEmpty { "0" }
        val hex = identifier.toLongOrNull()?.let { String.format("%016x", it) } ?: identifier
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
            "cdid" to "cdid-$identifier",
            "channel" to APP_CHANNEL,
            "current_region" to "DE",
            "device_brand" to "Google",
            "device_id" to identifier,
            "device_platform" to "android",
            "device_type" to APP_DEVICE_TYPE,
            "dpi" to "420",
            "host_abi" to "arm64-v8a",
            "iid" to identifier,
            "install_id" to identifier,
            "is_pad" to "0",
            "language" to "de",
            "last_install_time" to (time / 1000L - 86_400L * 30L).toString(),
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
            "ts" to (time / 1000L).toString(),
            "uoo" to "0",
            "update_version_code" to APP_VERSION_CODE,
            "version_code" to APP_VERSION_CODE,
            "version_name" to APP_VERSION_NAME,
            "_rticket" to time.toString()
        )
    }

    private fun postMitKopf(
        url: String,
        cookie: String,
        userAgent: String,
        header: Map<String, String>
    ): ByteArray {
        val source = sourceOf(url)
        TikTokDump.write(context, source, "anfrage", url.toByteArray())
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
            for ((name, value) in header) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(ByteArray(0)) }
            val code = connection.responseCode
            val response = if (code in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            TikTokDump.write(context, source, "antwort", response, code)
            return response
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
        val missingNames = names.values
            .filter { it.peerId.isNotEmpty() && it.name.isEmpty() }
        if (missingNames.isNotEmpty()) {
            val cookie = runCatching { TikTokCookieRepository.session(ctx).cookie }.getOrNull()
            if (!cookie.isNullOrEmpty()) {
                for (conversation in missingNames) {
                    val profil = runCatching { fetchProfiles(cookie, listOf(conversation.peerId)) }
                        .getOrNull()
                        ?.get(conversation.peerId)
                        ?: continue
                    val name = profil.name.takeIf { it.isNotEmpty() } ?: continue
                    runCatching {
                        TikTokPushStore.reportName(ctx, conversation.convId, conversation.peerId, name)
                    }
                }
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
        val page = TikTokProto.parseConversationPage(post(CONV_URL, session.cookie, body), selfId)
        return page.copy(messages = page.messages.map { message ->
            if (message.mediaKind != "voice" || message.mediaStream.isNotEmpty()) return@map message
            val url = mediaUrl(session, meta, message)
            if (url.isEmpty()) message else message.copy(mediaUrl = url, mediaStream = url)
        })
    }

    private fun resolveNames(cookie: String, feed: TikTokFeed): TikTokFeed {
        val peerIds = feed.conversations.map { it.peerId }.filter { it.isNotEmpty() }.distinct()
        if (peerIds.isEmpty()) return feed
        val profiles = fetchProfiles(cookie, peerIds)
        if (profiles.isEmpty()) return feed
        return feed.copy(conversations = feed.conversations.map { conversation ->
            val profile = profiles[conversation.peerId] ?: return@map conversation
            conversation.copy(
                name = profile.name.ifEmpty { conversation.name },
                avatarUrl = profile.avatarUrl.ifEmpty { conversation.avatarUrl },
                handle = profile.handle.ifEmpty { conversation.handle }
            )
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

    private fun fetchProfiles(cookie: String, uids: List<String>): Map<String, TikTokProfile> {
        val result = mutableMapOf<String, TikTokProfile>()
        for (chunk in uids.chunked(20)) {
            val ids = chunk.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "$PROFILE_URL?aid=1988&user_ids=${URLEncoder.encode(ids, "UTF-8")}"
            val body = get(url, cookie) ?: continue
            val users = runCatching { JSONObject(body).optJSONArray("users") }.getOrNull() ?: continue
            for (index in 0 until users.length()) {
                val profile = users.optJSONObject(index)?.optJSONObject("im_user_profile") ?: continue
                val uid = profile.optString("user_id_str").ifEmpty { profile.optString("user_id") }
                if (uid.isEmpty()) continue
                val name = profile.optString("nick_name").ifEmpty { profile.optString("unique_id") }
                if (name.isEmpty()) continue
                result[uid] = TikTokProfile(
                    uid = uid,
                    name = name,
                    handle = profile.optString("unique_id"),
                    signature = profile.optString("signature"),
                    avatarUrl = TikTokAvatarStore.avatarUrl(profile),
                    following = profile.optInt("follow_status", 0) == 1
                )
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
        val source = sourceOf(url)
        TikTokDump.write(context, source, "anfrage", body)
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
                TikTokDump.write(context, source, "antwort", detail.toByteArray(), code)
                throw TikTokDmException(code, detail.ifEmpty { "HTTP $code" })
            }
            val response = connection.inputStream.use { it.readBytes() }
            TikTokDump.write(context, source, "antwort", response, TikTokProto.statusCode(response))
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytes(url: String, cookie: String): ByteArray? {
        val source = sourceOf(url)
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
            TikTokDump.write(context, source, "antwort", bytes)
            bytes
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, cookie: String): String? {
        val source = sourceOf(url)
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
            TikTokDump.write(context, source, "antwort", text.toByteArray())
            text
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sourceOf(url: String): String = when {
        url.contains("get_by_user_init") -> "init"
        url.contains("get_by_conversation") -> "conversation"
        url.endsWith("/send") -> "send"
        url.contains("mark_read") -> "markread"
        url.contains("recall") -> "recall"
        url.contains("delete") -> "delete"
        url.contains("set_property") -> "property"
        url.contains("/profile/") -> "profil"
        else -> url.substringAfterLast('/').take(24).ifEmpty { "unbekannt" }
    }
}
