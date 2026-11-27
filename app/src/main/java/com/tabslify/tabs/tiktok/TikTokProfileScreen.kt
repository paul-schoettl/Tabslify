package com.tabslify.tabs.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tabslify.core.objects.toast
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.tabs.tiktok.ui.TikTokAvatar
import com.tabslify.tabs.tiktok.ui.TikTokStreakChip
import com.tabslify.tabs.tiktok.ui.TikTokTokens
import com.tabslify.tabs.tiktok.ui.TikTokTopBar
import kotlinx.coroutines.launch

@Composable
fun TikTokProfileScreen(
    conversation: TikTokConversation?,
    selfUid: String,
    selfName: String,
    isSelf: Boolean,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMedia: (TikTokMessage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peerId = conversation?.peerId.orEmpty()
    val feedState by TikTokDmClient.feed.collectAsState()
    var profile by remember(peerId) { mutableStateOf<TikTokProfile?>(null) }
    var isLoading by remember(peerId) { mutableStateOf(!isSelf && peerId.isNotEmpty()) }
    var error by remember(peerId) { mutableStateOf<String?>(null) }
    var isFollowing by remember(peerId) { mutableStateOf(false) }
    var isWorking by remember(peerId) { mutableStateOf(false) }

    LaunchedEffect(peerId) {
        if (isSelf || peerId.isEmpty()) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        runCatching { TikTokDmClient.resolveProfile(context, peerId) }
            .onSuccess {
                profile = it
                isFollowing = it?.following ?: false
                if (it == null) error = "Profil nicht abrufbar"
            }
            .onFailure { error = it.message ?: "Profil nicht abrufbar" }
        isLoading = false
    }

    val sharedMedia = remember(feedState.messages, peerId) {
        if (peerId.isEmpty()) emptyList()
        else feedState.messages
            .filter { message ->
                val conv = feedState.conversations.firstOrNull { it.convId == message.convId }
                conv?.peerId == peerId &&
                    message.mediaUrl.isNotEmpty() &&
                    message.mediaKind != "voice"
            }
            .sortedByDescending { it.timestamp }
            .distinctBy { it.id }
    }

    val displayName = profile?.name?.ifEmpty { null }
        ?: conversation?.name?.ifEmpty { null }
        ?: selfName.ifEmpty { if (isSelf) "Du" else peerId }
    val handle = profile?.handle ?: conversation?.handle.orEmpty()
    val bio = profile?.signature.orEmpty()
    val avatarUrl = profile?.avatarUrl ?: conversation?.avatarUrl.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        TikTokTopBar(
            title = if (isSelf) "Profil" else displayName,
            onBack = onBack
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = TikTokTokens.ScreenPadding,
                end = TikTokTokens.ScreenPadding,
                top = 4.dp,
                bottom = 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TikTokAvatar(
                        uid = peerId,
                        name = displayName,
                        url = avatarUrl,
                        size = TikTokTokens.AvatarProfile
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    if (handle.isNotEmpty()) {
                        Text(
                            text = "@$handle",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                    if (conversation != null) {
                        Spacer(Modifier.height(6.dp))
                        TikTokStreakChip(
                            value = conversation.streak.value,
                            active = conversation.streak.active,
                            restorable = conversation.streak.restorable
                        )
                    }
                    if (bio.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isLoading) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = ErrorRed
                        )
                        TextButton(onClick = {
                            TikTokDiagnostics.copy(
                                context = context,
                                block = "TikTok Profil fehlgeschlagen\npeer=$peerId\nerror=$it"
                            )
                        }) { Text("Diagnose kopieren") }
                    }
                    if (!isSelf) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onOpenChat,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TikTokTokens.AccentRing
                                )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Nachricht")
                            }
                            OutlinedButton(
                                enabled = !isWorking && profile != null,
                                onClick = {
                                    val target = !isFollowing
                                    isWorking = true
                                    scope.launch {
                                        runCatching {
                                            TikTokFollowClient.setFollow(context, peerId, target)
                                        }.onSuccess {
                                            isFollowing = target
                                            toast(
                                                context,
                                                if (target) "Folgt jetzt" else "Nicht mehr gefolgt"
                                            )
                                        }.onFailure {
                                            toast(context, it.message ?: "Folgen fehlgeschlagen")
                                        }
                                        isWorking = false
                                    }
                                }
                            ) {
                                Text(if (isFollowing) "Entfolgen" else "Folgen")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (sharedMedia.isEmpty()) {
                            "Noch keine geteilten Medien"
                        } else {
                            "${sharedMedia.size} geteilte Medien"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(sharedMedia, key = { it.id }) { message ->
                MediaTile(message = message, onOpen = { onOpenMedia(message) })
            }
            if (!isSelf && !isLoading && sharedMedia.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Videos und Fotos aus diesem Chat appear hier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaTile(message: TikTokMessage, onOpen: () -> Unit) {
    val context = LocalContext.current
    var localFile by remember(message.id) {
        mutableStateOf(TikTokImageStore.path(context, message.id).takeIf { it.isNotEmpty() })
    }
    LaunchedEffect(message.id) {
        if (localFile != null) return@LaunchedEffect
        val loaded = TikTokImageStore.load(
            context = context,
            messageId = message.id,
            url = message.mediaUrl,
            keyHex = message.mediaKey,
            convId = message.convId
        )
        if (loaded.isNotEmpty()) localFile = loaded
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }
    ) {
        val source: Any = localFile ?: message.mediaUrl
        if (localFile != null || message.mediaKey.isEmpty()) {
            AsyncImage(
                model = source,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (message.mediaKind == "video") {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
