package com.tabslify.tabs.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.tabs.tiktok.ui.TikTokAvatar
import com.tabslify.tabs.tiktok.ui.TikTokPill
import com.tabslify.tabs.tiktok.ui.TikTokStreakChip
import com.tabslify.tabs.tiktok.ui.TikTokTokens
import com.tabslify.tabs.tiktok.ui.TikTokTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class InboxFilter(val label: String) {
    ALL("Alle"),
    UNREAD("Ungelesen"),
    STREAKS("Serien")
}

private sealed class InboxEntry {
    data class Header(val label: String) : InboxEntry()
    data class Row(val conversation: TikTokConversation) : InboxEntry()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TikTokInboxScreen(
    feed: TikTokFeed,
    isLoading: Boolean,
    error: String?,
    selfUid: String,
    onRefresh: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSelfProfile: () -> Unit,
    onOpenProfile: (TikTokConversation) -> Unit,
    onOpen: (TikTokConversation) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(InboxFilter.ALL) }
    var actionTarget by remember { mutableStateOf<TikTokConversation?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val filtered = remember(feed.conversations, query, filter) {
        val term = query.trim().lowercase()
        feed.conversations.filter { conversation ->
            val matchesTerm = term.isEmpty() ||
                conversation.name.lowercase().contains(term) ||
                conversation.handle.lowercase().contains(term) ||
                conversation.lastMessage.lowercase().contains(term)
            val matchesFilter = when (filter) {
                InboxFilter.ALL -> true
                InboxFilter.UNREAD -> conversation.unread > 0
                InboxFilter.STREAKS -> conversation.streak.active || conversation.streak.restorable
            }
            matchesTerm && matchesFilter
        }
    }

    val entries = remember(filtered) { buildInboxEntries(filtered) }
    val unreadChats = feed.conversations.count { it.unread > 0 }

    Column(modifier = Modifier.fillMaxSize()) {
        TikTokTopBar(
            title = "TikTok",
            subtitle = if (unreadChats > 0) "$unreadChats Chats mit newOnes Nachrichten" else null,
            leading = {
                TikTokAvatar(
                    uid = selfUid,
                    name = "",
                    url = "",
                    size = 30.dp,
                    modifier = Modifier.clickable { onOpenSelfProfile() }
                )
            },
            onRefresh = onRefresh,
            isRefreshing = isLoading,
            onOverflow = onOpenDiagnostics
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TikTokTokens.ScreenPadding)
                .padding(top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Suchen", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Suche löschen",
                            tint = TextTertiary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { query = "" }
                        )
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = TikTokTokens.DatePillShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            SquareAction(onClick = onOpenFeed) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Feed",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            SquareAction(onClick = onOpenLibrary) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = "Offline-Videos",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TikTokTokens.ScreenPadding)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InboxFilter.entries.forEach { option ->
                TikTokPill(
                    label = option.label,
                    selected = filter == option,
                    onClick = { filter = option }
                )
            }
        }

        when {
            isLoading && feed.conversations.isEmpty() -> Centered {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }

            error != null && feed.conversations.isEmpty() -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Chats nicht ladbar",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRefresh) { Text("Erneut laden") }
                    TextButton(onClick = {
                        TikTokDiagnostics.copy(
                            context = context,
                            block = "TikTok Chats laden fehlgeschlagen\nerror=$error"
                        )
                    }) { Text("Diagnose kopieren") }
                }
            }

            feed.conversations.isEmpty() -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Keine Chats",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Zum Aktualisieren oben rightSide tippen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            entries.isEmpty() -> Centered {
                Text(
                    text = "Kein Treffer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(TikTokTokens.ItemGap)
            ) {
                items(entries, key = { entry ->
                    when (entry) {
                        is InboxEntry.Header -> "h:${entry.label}"
                        is InboxEntry.Row -> entry.conversation.convId
                    }
                }) { entry ->
                    when (entry) {
                        is InboxEntry.Header -> SectionHeader(entry.label)
                        is InboxEntry.Row -> InboxRow(
                            conversation = entry.conversation,
                            onOpen = { onOpen(entry.conversation) },
                            onOpenProfile = { onOpenProfile(entry.conversation) },
                            onLongPress = { actionTarget = entry.conversation }
                        )
                    }
                }
            }
        }
    }

    val target = actionTarget
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = target.name.ifEmpty { target.peerId },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (target.handle.isNotEmpty()) {
                    Text(
                        text = "@${target.handle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(14.dp))
                SheetAction("Profil öffnen") {
                    actionTarget = null
                    onOpenProfile(target)
                }
                SheetAction("Chat öffnen") {
                    actionTarget = null
                    onOpen(target)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { actionTarget = null }) { Text("Schließen") }
            }
        }
    }
}

@Composable
private fun SquareAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = TextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TikTokTokens.RowPaddingH, end = TikTokTokens.RowPaddingH)
            .padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun InboxRow(
    conversation: TikTokConversation,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
    onLongPress: () -> Unit
) {
    val hasUnread = conversation.unread > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = TikTokTokens.RowPaddingH, vertical = TikTokTokens.RowPaddingV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TikTokAvatar(
            uid = conversation.peerId,
            name = conversation.name,
            url = conversation.avatarUrl,
            size = TikTokTokens.AvatarInbox,
            ringColor = if (hasUnread) TikTokTokens.AccentRing else null,
            modifier = Modifier.clickable { onOpenProfile() }
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.name.ifEmpty { conversation.peerId },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                TikTokStreakChip(
                    value = conversation.streak.value,
                    active = conversation.streak.active,
                    restorable = conversation.streak.restorable
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(conversation.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) TikTokTokens.AccentRing else TextTertiary
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = previewOf(conversation),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasUnread) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasUnread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(TikTokTokens.UnreadDot)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(TikTokTokens.ScreenPadding),
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun buildInboxEntries(conversations: List<TikTokConversation>): List<InboxEntry> {
    if (conversations.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val startOfToday = now - (now % 86_400_000L)
    val buckets = linkedMapOf<String, MutableList<TikTokConversation>>()
    for (conversation in conversations) {
        val ts = conversation.lastTimestamp
        val label = when {
            ts >= startOfToday -> "Heute"
            ts >= startOfToday - 86_400_000L -> "Gestern"
            ts >= startOfToday - 7L * 86_400_000L -> "Diese Woche"
            else -> "Älter"
        }
        buckets.getOrPut(label) { mutableListOf() }.add(conversation)
    }
    val out = mutableListOf<InboxEntry>()
    for (label in listOf("Heute", "Gestern", "Diese Woche", "Älter")) {
        val group = buckets[label] ?: continue
        out.add(InboxEntry.Header(label))
        group.forEach { out.add(InboxEntry.Row(it)) }
    }
    return out
}

fun previewOf(conversation: TikTokConversation): String {
    val raw = conversation.lastMessage.replace('\n', ' ').trim()
    val sharedPost = raw.startsWith("🎬 TikTok-Video") || raw.startsWith("🖼️ TikTok-Foto")
    val ownMedia = conversation.lastKind == "video" ||
        conversation.lastKind == "photo" || conversation.lastKind == "sticker"
    val peerShared = !conversation.lastIsOwn && (sharedPost || ownMedia)
    return when {
        conversation.lastIsOwn -> "Du: $raw".take(140)
        peerShared -> {
            val what = when {
                conversation.lastKind == "sticker" -> "einen Sticker"
                raw.startsWith("🖼️ TikTok-Foto") -> "ein TikTok-Foto"
                conversation.lastKind == "photo" && !raw.startsWith("🎬") -> "ein Foto"
                else -> "ein Video"
            }
            "hat $what geteilt"
        }
        raw.isEmpty() -> "Neue Nachricht"
        else -> raw.take(140)
    }
}

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "jetzt"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 172_800_000L -> "gestern"
        diff < 604_800_000L -> "${diff / 86_400_000L}d"
        else -> SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(timestamp))
    }
}
