package com.tabslify.tabs.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.tabslify.core.ui.Border
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.tabs.tiktok.ui.TikTokAvatar
import com.tabslify.tabs.tiktok.ui.TikTokStreakChip
import com.tabslify.tabs.tiktok.ui.TikTokTokens
import com.tabslify.tabs.tiktok.ui.TikTokTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val PREFETCH_MESSAGES = 8
private const val RECALL_WINDOW_MS = 60L * 60L * 1000L
private const val DRAFT_PREFS = "tiktok_drafts"
private const val GROUP_WINDOW_MS = 5L * 60L * 1000L

private val REACTION_EMOJI = listOf(
    "❤️", "👍", "👎", "😂", "🥰", "🤔", "😮", "😢",
    "😭", "😡", "🙏", "🎉", "🔥", "✌️", "🤗", "🕶️", "🥳", "❣️", "☺️", "🤪"
)

@Composable
fun TikTokChatScreen(
    conversation: TikTokConversation,
    messages: List<TikTokMessage>,
    hasMore: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onSent: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMedia: (TikTokMessage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(DRAFT_PREFS, android.content.Context.MODE_PRIVATE) }
    var draft by remember(conversation.convId) {
        mutableStateOf(prefs.getString(conversation.convId, "").orEmpty())
    }
    var sendError by remember(conversation.convId) { mutableStateOf<String?>(null) }
    var isSending by remember(conversation.convId) { mutableStateOf(false) }
    var pendingText by remember(conversation.convId) { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<TikTokMessage?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var offlineRequest by remember { mutableStateOf<OfflineAntrag?>(null) }
    var offlineSize by remember { mutableLongStateOf(0L) }
    var replyTarget by remember(conversation.convId) { mutableStateOf<TikTokMessage?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(draft) {
        prefs.edit { putString(conversation.convId, draft) }
    }

    fun persistDraft(value: String) {
        prefs.edit { putString(conversation.convId, value) }
    }

    fun doSend() {
        val text = draft.trim()
        if (text.isEmpty() || isSending) return
        sendError = null
        isSending = true
        pendingText = text
        draft = ""
        persistDraft("")
        replyTarget = null
        scope.launch {
            runCatching {
                TikTokDmClient.send(context, conversation.convId, conversation.peerId, text)
            }.onSuccess {
                sendError = null
                pendingText = null
                onSent()
                scope.launch { listState.animateScrollToItem(0) }
            }.onFailure {
                pendingText = null
                sendError = it.message ?: it.toString()
                draft = text
                persistDraft(text)
            }
            isSending = false
        }
    }

    val ordered = remember(messages) { messages.sortedBy { it.timestamp }.reversed() }
    val firstUnreadId = remember(conversation.convId, ordered.size, conversation.unread) {
        if (conversation.unread <= 0) "" else ordered.getOrNull(conversation.unread - 1)?.id.orEmpty()
    }
    val totalCount = ordered.size
    var lastTriggerTotal by remember(conversation.convId) { mutableIntStateOf(-1) }
    var seenCount by remember(conversation.convId) { mutableIntStateOf(ordered.size) }

    LaunchedEffect(totalCount) {
        val atBottom = listState.firstVisibleItemIndex <= 1
        if (atBottom) seenCount = totalCount
    }

    LaunchedEffect(totalCount, hasMore, isLoading, conversation.convId) {
        if (!hasMore || isLoading) return@LaunchedEffect
        if (lastTriggerTotal == totalCount) return@LaunchedEffect
        val prefetchAt = totalCount - PREFETCH_MESSAGES
        if (prefetchAt <= 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { it >= prefetchAt }
        lastTriggerTotal = totalCount
        onLoadMore()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        TikTokTopBar(
            title = conversation.name.ifEmpty { conversation.peerId },
            subtitle = conversation.handle.takeIf { it.isNotEmpty() }?.let { "@$it" },
            onBack = onBack,
            leading = {
                TikTokAvatar(
                    uid = conversation.peerId,
                    name = conversation.name,
                    url = conversation.avatarUrl,
                    size = 30.dp,
                    modifier = Modifier.clickable { onOpenProfile() }
                )
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TikTokStreakChip(
                        value = conversation.streak.value,
                        active = conversation.streak.active,
                        restorable = conversation.streak.restorable
                    )
                    IconButton(onClick = onOpenProfile) {
                        Text(
                            text = "ⓘ",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = TikTokTokens.ScreenPadding,
                    end = TikTokTokens.ScreenPadding,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(
                    items = ordered,
                    key = { _, it -> it.id.ifEmpty { it.timestamp.toString() + it.text } }
                ) { index, message ->
                    val olderNeighbour = ordered.getOrNull(index + 1)
                    val newerNeighbour = ordered.getOrNull(index - 1)
                    val sameSenderAsOlder = olderNeighbour != null &&
                        olderNeighbour.isOwn == message.isOwn &&
                        message.timestamp - olderNeighbour.timestamp < GROUP_WINDOW_MS
                    val sameSenderAsNewer = newerNeighbour != null &&
                        newerNeighbour.isOwn == message.isOwn &&
                        newerNeighbour.timestamp - message.timestamp < GROUP_WINDOW_MS
                    val showDate = olderNeighbour == null ||
                        dayLabel(olderNeighbour.timestamp) != dayLabel(message.timestamp)
                    val showUnread = message.id == firstUnreadId
                    if (showDate || showUnread) {
                        ChatDivider(
                            dateLabel = if (showDate) dayLabel(message.timestamp) else null,
                            showUnread = showUnread
                        )
                    }
                    MessageBubble(
                        message = message,
                        isFirstOfGroup = !sameSenderAsOlder,
                        isLastOfGroup = !sameSenderAsNewer,
                        onOpenMedia = onOpenMedia,
                        onAction = { target -> actionTarget = target }
                    )
                }
                if (pendingText != null) {
                    item {
                        PendingBubble(text = pendingText.orEmpty(), isSending = isSending)
                    }
                }
                item {
                    when {
                        isLoading -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }

                        hasMore -> TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Ältere Nachrichten") }
                    }
                }
            }

            val hidden = totalCount - seenCount
            val atBottom by remember {
                derivedStateOf { listState.firstVisibleItemIndex <= 1 }
            }
            if (!atBottom) {
                Surface(
                    shape = TikTokTokens.DatePillShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(0)
                                seenCount = totalCount
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = TikTokTokens.AccentRing,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (hidden > 0) "$hidden newItems" else "Nach below",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        replyTarget?.let { target ->
            ReplyPreview(
                message = target,
                onCancel = { replyTarget = null }
            )
        }

        if (sendError != null) {
            Text(
                text = "Senden fehlgeschlagen: $sendError",
                color = ErrorRed,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = TikTokTokens.ScreenPadding, vertical = 4.dp)
            )
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            isSending = isSending,
            onSend = { doSend() }
        )

        actionTarget?.let { message ->
            MessageActionSheet(
                message = message,
                error = actionError,
                onDismiss = { actionTarget = null },
                onReaction = { emoji ->
                    scope.launch {
                        val set = message.myReaction != emoji
                        runCatching {
                            TikTokDmClient.setReaction(context, message, emoji, set)
                        }.onSuccess {
                            actionTarget = null
                        }.onFailure {
                            actionError = it.message ?: "Reaktion fehlgeschlagen"
                        }
                    }
                },
                onReply = {
                    replyTarget = message
                    actionTarget = null
                },
                onRecall = {
                    actionTarget = null
                    actionError = null
                    scope.launch {
                        runCatching {
                            TikTokDmClient.recall(
                                context,
                                conversation.convId,
                                message.id.toLongOrNull() ?: 0L
                            )
                        }.onFailure { actionError = it.message ?: "Zurückrufen fehlgeschlagen" }
                    }
                },
                onDelete = {
                    actionTarget = null
                    actionError = null
                    scope.launch {
                        runCatching {
                            TikTokDmClient.deleteMessage(
                                context,
                                conversation.convId,
                                message.id.toLongOrNull() ?: 0L
                            )
                        }.onFailure { actionError = it.message ?: "Löschen fehlgeschlagen" }
                    }
                },
                onOffline = {
                    val request = buildOfflineRequest(message)
                    if (request != null) {
                        actionTarget = null
                        if (!com.tabslify.widget.HandyLampClient.isCellularOnly(context)) {
                            scope.launch {
                                runCatching {
                                    TikTokOfflineDownloader.enqueue(context, request)
                                }.onSuccess {
                                    com.tabslify.core.objects.toast(
                                        context,
                                        "Video kommt in die Offline-Bibliothek"
                                    )
                                }
                            }
                        } else {
                            offlineRequest = request
                            offlineSize = 0L
                            scope.launch {
                                offlineSize = runCatching {
                                    TikTokOfflineDownloader.sizeBytes(context, request.urls.first())
                                }.getOrDefault(0L)
                            }
                        }
                    } else {
                        actionError = "Kein Video in dieser Nachricht"
                    }
                },
                onOfflineLater = {
                    val request = buildOfflineRequest(message)
                    if (request != null) {
                        actionTarget = null
                        scope.launch {
                            runCatching { TikTokOfflineStore.register(context, request) }
                                .onSuccess {
                                    com.tabslify.core.objects.toast(
                                        context,
                                        "Wird im WLAN automatic geladen"
                                    )
                                }
                        }
                    } else {
                        actionError = "Kein Video in dieser Nachricht"
                    }
                }
            )
        }

        offlineRequest?.let { request ->
            com.tabslify.core.ui.AlertDialogTabslify(
                title = "Mobile Daten nutzen?",
                text = if (offlineSize > 0L) {
                    "${request.title}\nGröße: ${formatMb(offlineSize)}\n\nLäuft über deinen Hotspot."
                } else {
                    "${request.title}\nGröße unbekannt.\n\nLäuft über deinen Hotspot."
                },
                confirmText = "Herunterladen",
                onConfirm = {
                    offlineRequest = null
                    scope.launch {
                        runCatching { TikTokOfflineDownloader.enqueue(context, request) }
                    }
                },
                onDismiss = { offlineRequest = null }
            )
        }
    }
}

@Composable
private fun ChatDivider(dateLabel: String?, showUnread: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (dateLabel != null) {
            Surface(
                shape = TikTokTokens.DatePillShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        if (showUnread) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Neue Nachrichten",
                style = MaterialTheme.typography.labelSmall,
                color = TikTokTokens.AccentRing
            )
        }
    }
}

@Composable
private fun PendingBubble(text: String, isSending: Boolean) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = TextTertiary
                )
                Spacer(Modifier.width(6.dp))
            }
            Surface(
                shape = TikTokTokens.BubbleShapeOwn,
                color = TikTokTokens.OwnBubbleColor.copy(alpha = 0.55f)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TikTokTokens.OwnBubbleText.copy(alpha = 0.8f),
                    modifier = Modifier.padding(
                        horizontal = TikTokTokens.BubblePaddingH,
                        vertical = TikTokTokens.BubblePaddingV
                    )
                )
            }
        }
    }
}

@Composable
private fun ReplyPreview(message: TikTokMessage, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TikTokTokens.ScreenPadding)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(TikTokTokens.AccentRing)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Antwort an ${if (message.isOwn) "dich" else "Kontakt"}",
                style = MaterialTheme.typography.labelSmall,
                color = TikTokTokens.AccentRing
            )
            Text(
                text = message.text.ifEmpty { "Medien" },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Antwort abbrechen",
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit
) {
    val enabled = draft.trim().isNotEmpty() && !isSending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TikTokTokens.ScreenPadding)
            .padding(top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Nachricht…", style = MaterialTheme.typography.bodyMedium) },
            maxLines = 5,
            shape = RoundedCornerShape(TikTokTokens.ComposerRadius),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) TikTokTokens.AccentRing
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = enabled) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = TextPrimary
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Senden",
                    tint = if (enabled) Color.White else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: TikTokMessage,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
    onOpenMedia: (TikTokMessage) -> Unit,
    onAction: ((TikTokMessage) -> Unit)?
) {
    val context = LocalContext.current
    val alignment = if (message.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val background = if (message.isOwn) TikTokTokens.OwnBubbleColor else TikTokTokens.PeerBubbleColor
    val textColor = if (message.isOwn) TikTokTokens.OwnBubbleText else TikTokTokens.PeerBubbleText
    val shape = if (message.isOwn && isFirstOfGroup) {
        TikTokTokens.BubbleShapeOwn
    } else if (!message.isOwn && isFirstOfGroup) {
        TikTokTokens.BubbleShapePeer
    } else {
        RoundedCornerShape(TikTokTokens.BubbleRadius)
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .background(background)
                    .then(
                        if (onAction != null) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onAction(message) }
                            )
                        } else Modifier
                    )
                    .padding(
                        horizontal = TikTokTokens.BubblePaddingH,
                        vertical = TikTokTokens.BubblePaddingV
                    )
            ) {
                if (message.replyToId.isNotEmpty() || message.replyToText.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(textColor.copy(alpha = TikTokTokens.ReplyQuoteAlpha))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Antwort",
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        val quote = message.replyToText.ifEmpty { message.replyToQuote }
                        if (quote.isNotEmpty()) {
                            Text(
                                text = quote,
                                color = textColor.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (message.mediaKind == "voice") {
                    if (message.mediaStream.isNotEmpty()) {
                        TikTokVoicePlayer(
                            streamUrl = message.mediaStream,
                            messageId = message.id,
                            durationMs = message.mediaDuration,
                            tint = textColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "🎤 Sprachnachricht · Ton nicht abrufbar",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (message.mediaUrl.isNotEmpty()) {
                    val sticker = message.mediaKind == "sticker"
                    val video = message.mediaKind == "video"
                    val mediaSize = when {
                        sticker -> Modifier.heightIn(max = 180.dp)
                        video -> Modifier.height(200.dp)
                        else -> Modifier.aspectRatio(0.75f)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(mediaSize)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenMedia(message) }
                    ) {
                        TikTokMedia(
                            message = message,
                            contentScale = if (sticker) androidx.compose.ui.layout.ContentScale.Fit
                            else androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(mediaSize)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        if (video) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x99000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Video abspielen",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val hasMedia = when (message.mediaKind) {
                    "voice" -> message.mediaStream.isNotEmpty()
                    "video" -> message.mediaStream.isNotEmpty() || message.mediaUrl.isNotEmpty()
                    else -> message.mediaUrl.isNotEmpty()
                }
                val isPlaceholderOnly = hasMedia && (
                    message.text == "🖼️ Bild" || message.text == "🎬 Video" ||
                        message.text.startsWith("🎤 Sprache")
                    )
                if (message.text.isNotEmpty() && !isPlaceholderOnly) {
                    Text(
                        text = message.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (message.unknownType.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = message.unknownType,
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    TikTokDiagnostics.copy(
                                        context = context,
                                        block = TikTokDiagnostics.describe(message)
                                    )
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("Fehler kopieren", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (message.timestamp > 0L && isLastOfGroup) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = textColor.copy(alpha = TikTokTokens.TimestampAlpha),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (message.reactions.isNotEmpty() && isLastOfGroup) {
                Spacer(Modifier.height(3.dp))
                ReactionChips(
                    listOfNotNull(
                        message.myReaction.takeIf { it.isNotEmpty() },
                        message.peerReaction.takeIf { it.isNotEmpty() }
                    ).ifEmpty { message.reactions }
                )
            }
        }
    }
}

@Composable
private fun ReactionChips(reactions: List<String>) {
    val shown = reactions.distinct().take(1)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (emoji in shown) {
            Surface(
                shape = TikTokTokens.DatePillShape,
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

private fun buildOfflineRequest(message: TikTokMessage): OfflineAntrag? {
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

internal fun dayLabel(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val value = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
        .format(java.util.Date(timestamp))
    val today = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
        .format(java.util.Date())
    val yesterday = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
        .format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
    return when (value) {
        today -> "Heute"
        yesterday -> "Gestern"
        else -> value
    }
}

internal fun formatTime(timestamp: Long): String =
    if (timestamp <= 0L) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY)
        .format(java.util.Date(timestamp))
