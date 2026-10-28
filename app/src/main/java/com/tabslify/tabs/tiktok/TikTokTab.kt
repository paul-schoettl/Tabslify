package com.tabslify.tabs.tiktok

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabslify.core.objects.toast
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.SharedViewModel
import com.tabslify.widget.HandyLampClient
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import coil.compose.AsyncImage
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFETCH_MESSAGES = 8
private const val RECALL_WINDOW_MS = 60L * 60L * 1000L
private val REACTION_EMOJI = listOf(
    "❤️", "👍", "👎", "😂", "🥰", "🤔", "😮", "😢",
    "😭", "😡", "🙏", "🎉", "🔥", "✌️", "🤗", "🕶️", "🥳", "❣️", "☺️", "🤪"
)
private const val TAG = "TikTokTab"

@Composable
fun TikTokTabContent(svm: SharedViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed by TikTokDmClient.feed.collectAsState()
    val loadingConv by TikTokDmClient.loadingConversation.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedConv by remember { mutableStateOf<String?>(null) }
    val pendingConv by svm.pendingTikTokOpen.collectAsState()
    var appliedConv by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingConv, appliedConv, feed.conversations) {
        val target = pendingConv ?: return@LaunchedEffect
        if (appliedConv == target) return@LaunchedEffect
        if (selectedConv != null) return@LaunchedEffect
        val match = feed.conversations.firstOrNull { it.convId == target } ?: return@LaunchedEffect
        appliedConv = target
        selectedConv = match.convId
        scope.launch {
            runCatching { TikTokDmClient.openConversation(context, match.convId) }
                .onFailure { error = it.message }
        }
    }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { TikTokDmClient.load(context) }
                .onSuccess { TikTokFrontier.start(context) }
                .onFailure { error = it.message ?: "Unbekannter Fehler" }
            loading = false
        }
    }

    var viewerMessage by remember { mutableStateOf<TikTokMessage?>(null) }
    var videosBereich by remember { mutableStateOf(false) }
    var playerId by remember { mutableStateOf<String?>(null) }
    var diagnose by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        TikTokDmClient.startPolling(context)
        TikTokFrontier.start(context)
        scope.launch { TikTokOfflineStore.lade(context) }
        TikTokOfflineDownloader.starteAutoKontrolle(context)
        onDispose {
            TikTokDmClient.stopPolling()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TikTokBackground {
            val selected = feed.conversations.firstOrNull { it.convId == selectedConv }
            if (videosBereich) {
                OfflineLibraryScreen(
                    onZurueck = { videosBereich = false },
                    onAbspielen = { id -> playerId = id }
                )
            } else if (selected == null) {
                ConversationList(
                    feed = feed,
                    loading = loading,
                    error = error,
                    onRefresh = { reload() },
                    onOpenVideos = { videosBereich = true },
                    onDiagnose = { diagnose = true },
                    onOpen = { conversation ->
                        selectedConv = conversation.convId
                        scope.launch {
                            runCatching {
                                TikTokDmClient.openConversation(
                                    context,
                                    conversation.convId
                                )
                            }
                            runCatching { TikTokDmClient.markRead(context, conversation.convId) }
                        }
                    }
                )
            } else {
                val convId = selected.convId
                ChatView(
                    conversation = selected,
                    messages = feed.messages.filter { it.convId == convId },
                    hasMore = convId in feed.hasMore,
                    loading = loadingConv == convId,
                    onLoadMore = {
                        scope.launch {
                            runCatching {
                                TikTokDmClient.loadMore(
                                    context,
                                    convId
                                )
                            }
                        }
                    },
                    onBack = { selectedConv = null },
                    onSent = {
                        scope.launch {
                            runCatching {
                                TikTokDmClient.openConversation(
                                    context,
                                    convId
                                )
                            }
                        }
                    },
                    onOpenMedia = { viewerMessage = it }
                )
            }
        }
        val viewer = viewerMessage
        if (viewer != null) {
            MediaViewer(
                message = viewer,
                onDismiss = { viewerMessage = null },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            )
        }
        val player = playerId
        if (player != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            ) {
                OfflinePlayerOverlay(
                    startId = player,
                    onZurueck = { playerId = null }
                )
            }
        }
        if (diagnose) {
            DumpSheet(onSchliessen = { diagnose = false })
        }
    }
}

@Composable
private fun ConversationList(
    feed: TikTokFeed,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenVideos: () -> Unit,
    onDiagnose: () -> Unit,
    onOpen: (TikTokConversation) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TikTok Chats", style = MaterialTheme.typography.titleLarge)
                val totalUnread = feed.conversations.sumOf { it.unread }
                if (totalUnread > 0) {
                    Text(
                        text = "$totalUnread ungelesen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Button(onClick = onRefresh, enabled = !loading) { Text("Aktualisieren") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onOpenVideos) { Text("▶ Videos") }
            TextButton(onClick = onDiagnose) { Text("Diagnose") }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading && feed.conversations.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && feed.conversations.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Fehler: $error") }

            feed.conversations.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Keine Chats") }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(feed.conversations) { conversation ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(conversation) }
                            .padding(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = conversation.name.ifEmpty { conversation.peerId },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            StreakBadge(conversation)
                            if (conversation.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = conversation.unread.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        Text(
                            text = conversation.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DumpSheet(onSchliessen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aktiv by remember { mutableStateOf(TikTokDump.istAktiv(context)) }
    var anzahl by remember { mutableIntStateOf(0) }
    var groesse by remember { mutableLongStateOf(0L) }
    var bundel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        anzahl = TikTokDump.anzahl(context)
        groesse = TikTokDump.groesse(context)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onSchliessen,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Diagnose", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Rohdaten aufzeichnen", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = aktiv,
                    onCheckedChange = { wert ->
                        aktiv = wert
                        TikTokDump.setzeAktiv(context, wert)
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$anzahl Aufzeichnungen, ${groesse / 1024L} KB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = TikTokDump.ordner(context).absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (bundel.isNotEmpty()) {
                Text(
                    text = "Paket: $bundel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Jede Anfrage und Antwort wird als Datei abgelegt, " +
                        "dazu eine Textdatei mit allen Feldern der Antwort.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val weg = TikTokDump.leeren(context)
                        anzahl = 0
                        groesse = 0L
                        bundel = ""
                        toast(context, "$weg Aufzeichnungen gelöscht")
                    }
                }) { Text("Leeren") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val zip = TikTokDump.zip(context)
                        if (zip == null) {
                            toast(context, "Nichts zu bündeln")
                        } else {
                            bundel = zip.absolutePath
                        }
                    }
                }) { Text("Bündeln") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        anzahl = TikTokDump.anzahl(context)
                        groesse = TikTokDump.groesse(context)
                    }
                }) { Text("Zählen") }
                TextButton(onClick = onSchliessen) { Text("Fertig") }
            }
        }
    }
}

@Composable
private fun ChatView(
    conversation: TikTokConversation,
    messages: List<TikTokMessage>,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onSent: () -> Unit,
    onOpenMedia: (TikTokMessage) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(conversation.convId) { mutableStateOf("") }
    var sendError by remember(conversation.convId) { mutableStateOf<String?>(null) }
    var sending by remember(conversation.convId) { mutableStateOf(false) }

    fun doSend() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sendError = null
        sending = true
        scope.launch {
            runCatching {
                TikTokDmClient.send(context, conversation.convId, conversation.peerId, text)
            }.onSuccess {
                draft = ""
                sendError = null
                onSent()
            }.onFailure {
                sendError = it.message ?: it.toString()
            }
            sending = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Chats") }
            Text(
                text = conversation.name.ifEmpty { conversation.peerId },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            StreakBadge(conversation)
        }

        Spacer(Modifier.height(8.dp))

        val listState = rememberLazyListState()
        val ordered = remember(messages) { messages.sortedBy { it.timestamp }.reversed() }
        val firstUnreadId = remember(conversation.convId, ordered.size, conversation.unread) {
            if (conversation.unread <= 0) "" else ordered.getOrNull(conversation.unread - 1)?.id.orEmpty()
        }
        val totalCount = ordered.size
        var lastTriggerTotal by remember(conversation.convId) { mutableIntStateOf(-1) }
        var actionTarget by remember { mutableStateOf<TikTokMessage?>(null) }
        var actionError by remember { mutableStateOf<String?>(null) }
        var offlineFrage by remember { mutableStateOf<OfflineAntrag?>(null) }
        var offlineGroesse by remember { mutableLongStateOf(0L) }
        val actionScope = rememberCoroutineScope()
        val actionContext = LocalContext.current
        fun runOwnAction(label: String, block: suspend () -> String) {
            actionTarget = null
            actionError = null
            actionScope.launch {
                runCatching { block() }
                    .onFailure { actionError = it.message ?: ("$label fehlgeschlagen") }
            }
        }

        LaunchedEffect(totalCount, hasMore, loading, conversation.convId) {
            if (!hasMore || loading) return@LaunchedEffect
            if (lastTriggerTotal == totalCount) return@LaunchedEffect
            val prefetchAt = totalCount - PREFETCH_MESSAGES
            if (prefetchAt <= 0) return@LaunchedEffect
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .first { it >= prefetchAt }
            lastTriggerTotal = totalCount
            onLoadMore()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(
                items = ordered,
                key = { _, it -> it.id.ifEmpty { it.timestamp.toString() + it.text } }
            ) { index, message ->
                val aeltererNachbar = ordered.getOrNull(index + 1)
                val showDate = aeltererNachbar == null ||
                        dayLabel(aeltererNachbar.timestamp) != dayLabel(message.timestamp)
                val showUnread = message.id == firstUnreadId
                if (showDate || showUnread) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (showDate) {
                            Text(
                                text = dayLabel(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (showUnread) {
                            Text(
                                text = "Neue Nachrichten",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                MessageBubble(
                    message,
                    onOpenMedia,
                    onAction = { target -> actionTarget = target }
                )
            }
            item {
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    hasMore -> TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⬆︎ Ältere Nachrichten") }
                }
            }
        }

        if (sendError != null) {
            Text(
                text = "Senden fehlgeschlagen: $sendError",
                color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        if (sending) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = " Sende…",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Nachricht…") },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { doSend() },
                    enabled = draft.trim().isNotEmpty() && !sending,
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("↑") }
            }
            val atBottom by remember(ordered.size) {
                derivedStateOf { listState.firstVisibleItemIndex <= 1 }
            }
            if (!atBottom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { scope.launch { listState.animateScrollToItem(0) } }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("▼", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        actionTarget?.let { message ->
            MessageActionSheet(
                message = message,
                error = actionError,
                onDismiss = { actionTarget = null },
                onReaction = { emoji ->
                    actionScope.launch {
                        val setzen = !message.reactions.contains(emoji)
                        runCatching {
                            TikTokDmClient.setReaction(actionContext, message, emoji, setzen)
                        }.onSuccess {
                            Log.i(
                                TAG,
                                "Reaktion ${if (setzen) "gesetzt" else "entfernt"}: ${message.id} $emoji"
                            )
                            actionTarget = null
                        }.onFailure {
                            Log.w(TAG, "Reaktion fehlgeschlagen: ${it.message}")
                            actionError = it.message ?: "Reaktion fehlgeschlagen"
                        }
                    }
                },
                onRecall = {
                    runOwnAction("Zurückrufen") {
                        TikTokDmClient.recall(
                            actionContext,
                            conversation.convId,
                            message.id.toLongOrNull() ?: 0L
                        )
                    }
                },
                onDelete = {
                    runOwnAction("Löschen") {
                        TikTokDmClient.deleteMessage(
                            actionContext,
                            conversation.convId,
                            message.id.toLongOrNull() ?: 0L
                        )
                    }
                },
                onOffline = {
                    val antrag = antragAusNachricht(message)
                    if (antrag != null) {
                        actionTarget = null
                        if (!HandyLampClient.isCellularOnly(actionContext)) {
                            actionScope.launch {
                                runCatching {
                                    TikTokOfflineDownloader.enqueue(
                                        actionContext,
                                        antrag
                                    )
                                }
                                    .onSuccess {
                                        toast(
                                            actionContext,
                                            "Video kommt in die Offline-Bibliothek"
                                        )
                                    }
                                    .onFailure {
                                        Log.w(
                                            TAG,
                                            "Offline-Speichern fehlgeschlagen: ${it.message}"
                                        )
                                    }
                            }
                        } else {
                            offlineFrage = antrag
                            offlineGroesse = 0L
                            actionScope.launch {
                                offlineGroesse = runCatching {
                                    TikTokOfflineDownloader.groesse(
                                        actionContext,
                                        antrag.urls.first()
                                    )
                                }.getOrDefault(0L)
                            }
                        }
                    } else {
                        actionError = "Kein Video in dieser Nachricht"
                    }
                },
                onOfflineSpaeter = {
                    val antrag = antragAusNachricht(message)
                    if (antrag != null) {
                        actionTarget = null
                        actionScope.launch {
                            runCatching { TikTokOfflineStore.registriere(actionContext, antrag) }
                                .onSuccess {
                                    toast(
                                        actionContext,
                                        "Wird im WLAN automatisch geladen"
                                    )
                                }
                                .onFailure { Log.w(TAG, "Einreihen fehlgeschlagen: ${it.message}") }
                        }
                    } else {
                        actionError = "Kein Video in dieser Nachricht"
                    }
                }
            )
        }

        offlineFrage?.let { antrag ->
            AlertDialogTabslify(
                title = "Mobile Daten nutzen?",
                text = if (offlineGroesse > 0L) {
                    "${antrag.title}\nGröße: ${mb(offlineGroesse)}\n\nLäuft über deinen Hotspot."
                } else {
                    "${antrag.title}\nGröße unbekannt.\n\nLäuft über deinen Hotspot."
                },
                confirmText = "Herunterladen",
                onConfirm = {
                    offlineFrage = null
                    actionScope.launch {
                        runCatching { TikTokOfflineDownloader.enqueue(actionContext, antrag) }
                            .onFailure {
                                Log.w(
                                    TAG,
                                    "Offline-Speichern fehlgeschlagen: ${it.message}"
                                )
                            }
                    }
                },
                onDismiss = { offlineFrage = null }
            )
        }
    }
}

private fun antragAusNachricht(message: TikTokMessage): OfflineAntrag? {
    if (message.mediaKind != "video" || message.mediaStream.isEmpty()) return null
    val id = "chat_" +
            message.mediaStream.hashCode().toUInt().toString(16) +
            "_" + message.mediaStream.length
    return OfflineAntrag(
        videoId = id,
        title = message.text.take(60).ifEmpty { "Chat-Video" },
        author = if (message.isOwn) "Ich" else message.senderId,
        coverUrl = message.mediaUrl,
        durationMs = message.mediaDuration,
        urls = listOf(message.mediaStream)
    )
}

@Composable
private fun TikTokVideoPlayer(streamUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cookie = remember(streamUrl) { TikTokCookieRepository.cachedCookie(context) }
    val exo = remember(streamUrl, cookie) {
        val datenQuelle = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Cookie" to cookie))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(datenQuelle))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = true
            }
        },
        modifier = modifier
    )
}

@Composable
private fun TikTokVoicePlayer(streamUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cookie = remember(streamUrl) { TikTokCookieRepository.cachedCookie(context) }
    val exo = remember(streamUrl, cookie) {
        val datenQuelle = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to TikTokOfflineDownloader.APP_USER_AGENT,
                    "Referer" to TikTokOfflineDownloader.REFERER
                )
            )
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(datenQuelle))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = true
            }
        },
        modifier = modifier
    )
}

@Composable
private fun MediaViewer(
    message: TikTokMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = true, onBack = onDismiss)
    Box(
        modifier = modifier
            .background(Color(0xF2000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        if (message.mediaKind == "video" && message.mediaStream.isNotEmpty()) {
            TikTokVideoPlayer(
                streamUrl = message.mediaStream,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )
        } else {
            TikTokMedia(
                message = message,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun TikTokMedia(
    message: TikTokMessage,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookieVersion by remember(message.mediaUrl) { mutableIntStateOf(0) }
    var retried by remember(message.mediaUrl) { mutableStateOf(false) }
    var urlErneuert by remember(message.mediaUrl) { mutableStateOf(false) }
    var localFile by remember(message.mediaUrl) { mutableStateOf<File?>(null) }
    val cookie = remember(message.mediaUrl, cookieVersion) {
        TikTokCookieRepository.cachedCookie(context)
    }
    LaunchedEffect(message.id, message.mediaUrl, message.mediaKey, cookie.length) {
        Log.d(
            TAG,
            "bild ${message.mediaKind} host=${hostOf(message.mediaUrl)} " +
                    "key=${message.mediaKey.length} cookie=${cookie.length}"
        )
        if (message.mediaKey.isNotEmpty() && localFile == null) {
            val file =
                TikTokDmClient.encryptedMediaFile(context, message.mediaUrl, message.mediaKey)
            Log.d(TAG, "entschlüsselt ${message.id} ${file != null}")
            if (file == null && !urlErneuert) {
                urlErneuert = true
                Log.d(
                    TAG,
                    "Medium ${message.id} nicht ladbar, hole frische URL fuer ${message.convId}"
                )
                TikTokDmClient.erneuereMedienUrl(context, message.convId)
                return@LaunchedEffect
            }
            localFile = file
        }
    }
    val builder = ImageRequest.Builder(context)
        .data(localFile ?: message.mediaUrl)
        .listener(object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                Log.d(TAG, "bild geladen id=${message.id} quelle=${result.dataSource}")
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.d(TAG, "bild FEHLER id=${message.id} ${result.throwable}")
                if (!retried && message.mediaKey.isEmpty() &&
                    message.mediaUrl.contains("tiktok", ignoreCase = true)
                ) {
                    retried = true
                    scope.launch {
                        runCatching { TikTokCookieRepository.refresh(context) }
                        cookieVersion++
                    }
                }
            }
        })
    if (localFile == null && message.mediaUrl.contains("tiktok", ignoreCase = true)) {
        builder.addHeader("Cookie", cookie)
        builder.addHeader("Referer", "https://www.tiktok.com/")
    }
    AsyncImage(
        model = builder.build(),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
    )
}

@Composable
private fun MessageBubble(
    message: TikTokMessage,
    onOpenMedia: (TikTokMessage) -> Unit,
    onAction: ((TikTokMessage) -> Unit)? = null
) {
    val alignment = if (message.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val background =
        if (message.isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isOwn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .then(
                        if (onAction != null) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onAction(message) }
                            )
                        } else Modifier
                    )
                    .padding(10.dp)
            ) {
                if (message.mediaKind == "voice") {
                    if (message.mediaStream.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "🎤 Sprachnachricht · ${message.mediaDuration / 1000L} s",
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TikTokVoicePlayer(message.mediaStream)
                        }
                    } else {
                        Text(
                            text = if (message.mediaDuration > 0L) {
                                "🎤 Sprachnachricht · ${message.mediaDuration / 1000L} s · Ton nicht abrufbar"
                            } else {
                                "🎤 Sprachnachricht · Ton nicht abrufbar"
                            },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (message.mediaUrl.isNotEmpty()) {
                    val sticker = message.mediaKind == "sticker"
                    val video = message.mediaKind == "video"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (sticker) Modifier.heightIn(max = 180.dp)
                                else Modifier.height(200.dp)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenMedia(message) }
                    ) {
                        TikTokMedia(
                            message = message,
                            contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (sticker) Modifier.heightIn(max = 180.dp)
                                    else Modifier.height(200.dp)
                                )
                                .clip(RoundedCornerShape(8.dp))
                        )
                        if (video) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(Color(0x99000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video abspielen",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (message.timestamp > 0L) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = textColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ReactionChips(message.reactions)
            }
        }
    }
}

@Composable
private fun StreakBadge(conversation: TikTokConversation) {
    val streak = conversation.streak
    if (streak.value == null || (!streak.active && !streak.restorable)) return
    val label = buildString {
        append(if (streak.active) "🔥" else "⚪")
        streak.value.let { value ->
            append(' ')
            append(value)
        }
        if (streak.restorable) append(" ↺")
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (streak.active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier.padding(start = 8.dp)
    )
}

private fun dayLabel(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val value = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(timestamp))
    val today = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
    val yesterday = SimpleDateFormat(
        "dd.MM.yyyy",
        Locale.GERMANY
    ).format(Date(System.currentTimeMillis() - 86_400_000L))
    return when (value) {
        today -> "Heute"
        yesterday -> "Gestern"
        else -> value
    }
}

private fun formatTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timestamp))

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

@Composable
private fun TikTokBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF05060F),
                        Color(0xFF0B1430),
                        Color(0xFF16305C),
                        Color(0xFF0B1430),
                        Color(0xFF05060F)
                    )
                )
            ),
        content = content
    )
}

@Composable
private fun ReactionChips(reactions: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (emoji in reactions) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: TikTokMessage,
    error: String?,
    onDismiss: () -> Unit,
    onReaction: (String) -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    onOffline: () -> Unit,
    onOfflineSpaeter: () -> Unit
) {
    val isOwn = message.isOwn
    val age = System.currentTimeMillis() - message.timestamp
    val recallable = isOwn && message.timestamp > 0L && age <= RECALL_WINDOW_MS
    val video = message.mediaKind == "video" && message.mediaStream.isNotEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (error != null) {
                Text(
                    text = error,
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = if (isOwn) "Eigene Nachricht" else "Nachricht",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ageText(message),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            REACTION_EMOJI.chunked(7).forEach { reihe ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (emoji in reihe) {
                        val aktiv = message.reactions.contains(emoji)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (aktiv) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onReaction(emoji) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = emoji,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "Tippen entfernt die eigene Reaktion wieder.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (video) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    TextButton(onClick = onOfflineSpaeter) { Text("Später im WLAN") }
                    TextButton(onClick = onOffline) { Text("Offline speichern") }
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (recallable) {
                    TextButton(onClick = onRecall) { Text("Zurückrufen") }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                TextButton(onClick = onDelete) {
                    Text("Endgültig löschen", color = Color(0xFFB71C1C))
                }
            }
        }
    }
}

private fun ageText(message: TikTokMessage): String {
    val age = System.currentTimeMillis() - message.timestamp
    if (message.timestamp <= 0L || age < 0L) return "Sendezeit unbekannt"
    val minutes = age / 60_000L
    val hours = age / 3_600_000L
    val days = age / 86_400_000L
    return when {
        minutes < 1 -> "Gerade eben gesendet."
        minutes < 60 -> "Vor $minutes Minuten gesendet."
        hours < 24 -> "Vor $hours Stunden gesendet."
        else -> "Vor $days Tagen gesendet."
    }
}
