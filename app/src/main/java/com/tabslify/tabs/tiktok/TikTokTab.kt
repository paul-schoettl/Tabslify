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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                onBack = { selectedConv = null }
            )
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
    onBack: () -> Unit
) {
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
        var lastSeenId by remember(conversation.convId) { mutableStateOf("") }
        LaunchedEffect(messages) {
            val last = messages.lastOrNull()
            if (last != null && last.id != lastSeenId) {
                lastSeenId = last.id
                listState.scrollToItem(messages.size)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
            items(messages) { message -> MessageBubble(message) }
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
