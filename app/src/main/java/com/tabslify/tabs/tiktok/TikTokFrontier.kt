package com.tabslify.tabs.tiktok

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import com.tabslify.tabs.tiktok.TikTokProto.FrontierStreak
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object TikTokFrontier {

    private const val TAG = "TikTokTab"
    private const val RECONNECT_DELAY_MS = 5000L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastPush = MutableStateFlow(0L)
    val lastPush: StateFlow<Long> = _lastPush.asStateFlow()

    private var scope: CoroutineScope? = null
    private var loop: Job? = null
    private var http: HttpClient? = null

    @Synchronized
    fun start(context: Context) {
        if (loop?.isActive == true) return
        val app = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        loop = scope?.launch {
            while (isActive) {
                try {
                    connect(app)
                } catch (e: Exception) {
                    Log.w(TAG, "Frontier getrennt: ${e.message}")
                }
                _connected.value = false
                delay(RECONNECT_DELAY_MS)
            }
        }
        Log.i(TAG, "Frontier-Client gestartet")
    }

    fun stop() {
        loop?.cancel()
        loop = null
        scope?.cancel()
        scope = null
        runCatching { http?.close() }
        http = null
        _connected.value = false
    }

    private suspend fun connect(context: Context) {
        val userId = TikTokDmClient.currentSelfId()
        if (userId.isEmpty()) {
            Log.w(TAG, "Frontier wartet auf eigene UID")
            return
        }
        val session = TikTokCookieRepository.session(context)
        val client = HttpClient(OkHttp) { install(WebSockets) }
        http = client
        try {
            client.webSocket(
                urlString = TikTokProto.frontierUrl(session.deviceId),
                request = { headers.append(HttpHeaders.Cookie, session.cookie) }
            ) {
                val now = System.currentTimeMillis()
                val auth = TikTokProto.buildFrontierAuth(userId, now)
                TikTokDump.schreibe(context, "frontier", "anfrage-auth", auth)
                send(Frame.Binary(true, auth))
                runCatching {
                    val register = TikTokProto.buildFrontierDeviceRegister(
                        TikTokDmClient.frontierParams(session),
                        now + 1
                    )
                    TikTokDump.schreibe(context, "frontier", "anfrage-geraet", register)
                    send(Frame.Binary(true, register))
                }.onFailure { Log.w(TAG, "Frontier Geraete-Anmeldung fehlgeschlagen: ${it.message}") }
                runCatching {
                    val register = TikTokProto.buildFrontierIndexRegister(
                        userId,
                        TikTokDmClient.frontierIndices(),
                        now + 2
                    )
                    TikTokDump.schreibe(context, "frontier", "anfrage-index", register)
                    send(Frame.Binary(true, register))
                }.onFailure { Log.w(TAG, "Frontier Index-Anmeldung fehlgeschlagen: ${it.message}") }
                _connected.value = true
                Log.i(TAG, "Frontier verbunden")
                for (frame in incoming) {
                    val binary = frame as? Frame.Binary ?: continue
                    val bytes = runCatching { binary.readBytes() }.getOrNull() ?: continue
                    val reactions = TikTokProto.parseFrontierReactions(bytes)
                    val pushes = TikTokProto.parseFrontierStreaks(bytes)
                    TikTokDump.schreibe(context, "frontier", "antwort", bytes)
                    if (reactions.isEmpty() && pushes.isEmpty()) {
                        Log.i(
                            TAG,
                            "Frontier-Frame ohne bekannte Nutzlast ${bytes.size}B:\n" +
                                TikTokProto.beschreibeFrame(bytes).take(2500)
                        )
                    }
                    for (reaction in reactions) {
                        Log.i(
                            TAG,
                            "Reaktions-Push: msg=${reaction.serverMessageId} " +
                                "conv=${reaction.convShortId} emoji=${reaction.emoji} add=${reaction.add}"
                        )
                        _lastPush.value = System.currentTimeMillis()
                        TikTokDmClient.applyFrontierReaction(
                            reaction.serverMessageId,
                            reaction.convShortId,
                            reaction.emoji,
                            reaction.add
                        )
                    }
                    if (pushes.isEmpty()) continue
                    _lastPush.value = System.currentTimeMillis()
                    for (push in pushes) {
                        Log.i(
                            TAG,
                            "Streak-Push: conv=${push.convId} cmd=${push.cmdType} " +
                                "counter=${push.counter} start=${push.startTime} scene=${push.scene}"
                        )
                        applyPush(push)
                    }
                }
            }
        } finally {
            if (http === client) http = null
            client.close()
        }
    }

    private suspend fun applyPush(push: FrontierStreak) {
        if (push.convId.isEmpty()) return
        val now = System.currentTimeMillis()
        val restorable = push.scene.contains("end_with_restore")
        val ended = push.scene.contains("end_no_restore") || push.cmdType == 2
        val state = when {
            restorable -> TikTokStreak(restorable = true, changedAt = now)
            ended -> TikTokStreak(changedAt = now)
            push.counter > 0 -> TikTokStreak(active = true, value = push.counter, changedAt = now)
            push.scene.contains("streak_new") || push.cmdType == 1 ->
                TikTokStreak(active = true, changedAt = now)
            else -> return
        }
        TikTokDmClient.saveStreak(push.convId, state)
    }
}
