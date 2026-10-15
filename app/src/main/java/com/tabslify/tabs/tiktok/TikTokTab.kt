package com.tabslify.tabs.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TikTokTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed by TikTokDmClient.feed.collectAsState()
    val loadingConv by TikTokDmClient.loadingConversation.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedConv by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { TikTokDmClient.load(context) }
                .onFailure { error = it.message ?: "Unbekannter Fehler" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    var webViewSichtbar by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> WebView(ctx).also { TikTokWebSender.attach(it) } },
            modifier = if (webViewSichtbar) {
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
            } else {
                Modifier.fillMaxSize()
            },
            update = { view ->
                if (webViewSichtbar) {
                    TikTokWebSender.onVisible()
                    view.invalidate()
                }
            }
        )
        TikTokBackground {
        val selected = feed.conversations.firstOrNull { it.convId == selectedConv }
        if (selected == null) {
            ConversationList(
                feed = feed,
                loading = loading,
                error = error,
                onRefresh = { reload() },
                onOpen = { conversation ->
                    selectedConv = conversation.convId
                    scope.launch { runCatching { TikTokDmClient.openConversation(context, conversation.convId) } }
                }
            )
        } else {
            val convId = selected.convId
            ChatView(
                conversation = selected,
                messages = feed.messages.filter { it.convId == convId },
                hasMore = convId in feed.hasMore,
                loading = loadingConv == convId,
                onLoadMore = { scope.launch { runCatching { TikTokDmClient.loadMore(context, convId) } } },
                onBack = { selectedConv = null },
                onSent = { scope.launch { runCatching { TikTokDmClient.load(context) } } },
                sendBlocked = webViewSichtbar
            )
        }
        }
        Button(
            onClick = { webViewSichtbar = !webViewSichtbar },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .zIndex(3f)
        ) {
            Text(if (webViewSichtbar) "WebView aus" else "WebView an")
        }
    }
}

@Composable
private fun ConversationList(
    feed: TikTokFeed,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
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
            Text("TikTok Chats", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onRefresh, enabled = !loading) { Text("Aktualisieren") }
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
                        Text(
                            text = conversation.name.ifEmpty { conversation.peerId },
                            style = MaterialTheme.typography.titleMedium
                        )
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

@Composable
private fun ChatView(
    conversation: TikTokConversation,
    messages: List<TikTokMessage>,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onSent: () -> Unit,
    sendBlocked: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(conversation.convId) { mutableStateOf("") }
    var sendError by remember(conversation.convId) { mutableStateOf<String?>(null) }
    var sending by remember(conversation.convId) { mutableStateOf(false) }

    fun doSend() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        if (sendBlocked) {
            sendError = "WebView ist sichtbar - zum Senden ausschalten"
            return
        }
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
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(8.dp))

        val listState = rememberLazyListState()
        val ordered = remember(messages) { messages.sortedBy { it.timestamp }.reversed() }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ordered, key = { it.id.ifEmpty { it.timestamp.toString() + it.text } }) { message ->
                MessageBubble(message)
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
    }
}

@Composable
private fun MessageBubble(message: TikTokMessage) {
    val alignment = if (message.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val background = if (message.isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isOwn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .padding(10.dp)
        ) {
            if (message.mediaUrl.isNotEmpty()) {
                AsyncImage(
                    model = message.mediaUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
            if (message.timestamp > 0L) {
                Text(
                    text = formatTime(message.timestamp),
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(timestamp))

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
