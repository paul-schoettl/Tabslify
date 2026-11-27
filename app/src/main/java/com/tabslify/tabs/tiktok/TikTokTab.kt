package com.tabslify.tabs.tiktok

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabslify.core.logging.Log
import com.tabslify.core.ui.AccentSoft
import com.tabslify.core.ui.BgDeep
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.SharedViewModel
import kotlinx.coroutines.launch

private const val TAG = "TikTokTab"

sealed class TikTokRoute {
    data object Inbox : TikTokRoute()
    data class Chat(val convId: String) : TikTokRoute()
    data class Profile(val convId: String) : TikTokRoute()
    data object SelfProfile : TikTokRoute()
    data object Feed : TikTokRoute()
    data object Library : TikTokRoute()
    data class Player(val videoId: String) : TikTokRoute()
}

@Composable
fun TikTokTabContent(svm: SharedViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed by TikTokDmClient.feed.collectAsState()
    val loadingConv by TikTokDmClient.loadingConversation.collectAsState()
    val frontierConnected by TikTokFrontier.connected.collectAsState()
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<TikTokRoute>(TikTokRoute.Inbox) }
    var mediaViewerIndex by remember { mutableStateOf(-1) }
    var mediaViewerList by remember { mutableStateOf<List<TikTokMessage>>(emptyList()) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    val pendingConv by svm.pendingTikTokOpen.collectAsState()
    var appliedConv by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(pendingConv, appliedConv, feed.conversations) {
        val target = pendingConv ?: return@LaunchedEffect
        if (appliedConv == target) return@LaunchedEffect
        if (route is TikTokRoute.Chat) return@LaunchedEffect
        val match = feed.conversations.firstOrNull { it.convId == target } ?: return@LaunchedEffect
        appliedConv = target
        route = TikTokRoute.Chat(match.convId)
        TikTokDmClient.setOpenConversation(match.convId)
        scope.launch {
            runCatching { TikTokDmClient.openConversation(context, match.convId) }
                .onFailure { error = it.message }
            runCatching { TikTokDmClient.markRead(context, match.convId) }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                TikTokDmClient.setForeground(true)
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                TikTokDmClient.setForeground(false)
            }
        })
    }

    fun reload() {
        isLoading = true
        error = null
        scope.launch {
            runCatching { TikTokDmClient.load(context) }
                .onSuccess { TikTokFrontier.start(context) }
                .onFailure { error = it.message ?: "Unbekannter Fehler" }
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        TikTokDmClient.startPolling(context)
        TikTokFrontier.start(context)
        scope.launch { TikTokOfflineStore.loadStore(context) }
        TikTokOfflineDownloader.starteAutoKontrolle(context)
        TikTokDmClient.startStreakBacklog(context)
        onDispose {
            TikTokDmClient.setOpenConversation("")
            TikTokDmClient.stopPolling()
        }
    }

    fun openChat(conversation: TikTokConversation) {
        route = TikTokRoute.Chat(conversation.convId)
        TikTokDmClient.setOpenConversation(conversation.convId)
        scope.launch {
            runCatching { TikTokDmClient.openConversation(context, conversation.convId) }
            runCatching { TikTokDmClient.markRead(context, conversation.convId) }
        }
    }

    fun backToInbox() {
        TikTokDmClient.setOpenConversation("")
        route = TikTokRoute.Inbox
    }

    BackHandler(enabled = route !is TikTokRoute.Inbox) {
        when (route) {
            is TikTokRoute.Chat -> backToInbox()
            is TikTokRoute.Profile -> backToInbox()
            TikTokRoute.SelfProfile -> backToInbox()
            TikTokRoute.Feed -> route = TikTokRoute.Inbox
            TikTokRoute.Library -> route = TikTokRoute.Inbox
            is TikTokRoute.Player -> route = TikTokRoute.Library
            TikTokRoute.Inbox -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TikTokBackground {
            val current = route
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val forward = targetState !is TikTokRoute.Inbox
                    val enter = slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { if (forward) it / 6 else -it / 6 }
                    ) + fadeIn(tween(180))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(220),
                        targetOffsetX = { if (forward) -it / 10 else it / 10 }
                    ) + fadeOut(tween(160))
                    enter togetherWith exit
                },
                label = "tiktok-route"
            ) { target ->
                when (target) {
                    TikTokRoute.Inbox -> TikTokInboxScreen(
                        feed = feed,
                        isLoading = isLoading,
                        error = error,
                        selfUid = feed.selfId,
                        onRefresh = { reload() },
                        onOpenFeed = { route = TikTokRoute.Feed },
                        onOpenLibrary = { route = TikTokRoute.Library },
                        onOpenDiagnostics = { diagnosticsOpen = true },
                        onOpenSelfProfile = { route = TikTokRoute.SelfProfile },
                        onOpenProfile = { conversation -> route = TikTokRoute.Profile(conversation.convId) },
                        onOpen = { conversation -> openChat(conversation) }
                    )

                    is TikTokRoute.Chat -> {
                        val conversation = feed.conversations.firstOrNull { it.convId == target.convId }
                        if (conversation == null) {
                            MissingConversation(onBack = { backToInbox() })
                        } else {
                            val convId = conversation.convId
                            TikTokChatScreen(
                                conversation = conversation,
                                messages = feed.messages.filter { it.convId == convId },
                                hasMore = convId in feed.hasMore,
                                isLoading = loadingConv == convId,
                                onLoadMore = {
                                    scope.launch {
                                        runCatching { TikTokDmClient.loadMore(context, convId) }
                                    }
                                },
                                onBack = { backToInbox() },
                                onSent = {
                                    scope.launch {
                                        runCatching { TikTokDmClient.openConversation(context, convId) }
                                    }
                                },
                                onOpenProfile = { route = TikTokRoute.Profile(convId) },
                                onOpenMedia = { tapped ->
                                    val list = feed.messages
                                        .filter { it.convId == convId }
                                        .filter { it.mediaUrl.isNotEmpty() && it.mediaKind != "voice" }
                                        .sortedBy { it.timestamp }
                                    val index = list.indexOfFirst { it.id == tapped.id }
                                    if (index >= 0) {
                                        mediaViewerList = list
                                        mediaViewerIndex = index
                                    }
                                }
                            )
                        }
                    }

                    is TikTokRoute.Profile -> {
                        val conversation = feed.conversations.firstOrNull { it.convId == target.convId }
                        TikTokProfileScreen(
                            conversation = conversation,
                            selfUid = feed.selfId,
                            selfName = "",
                            isSelf = false,
                            onBack = { backToInbox() },
                            onOpenChat = { conversation?.let { openChat(it) } },
                            onOpenMedia = { message -> mediaViewerList = listOf(message); mediaViewerIndex = 0 }
                        )
                    }

                    TikTokRoute.SelfProfile -> TikTokProfileScreen(
                        conversation = null,
                        selfUid = feed.selfId,
                        selfName = "",
                        isSelf = true,
                        onBack = { backToInbox() },
                        onOpenChat = {},
                        onOpenMedia = { message -> mediaViewerList = listOf(message); mediaViewerIndex = 0 }
                    )

                    TikTokRoute.Feed -> TikTokFeedScreen(onBack = { route = TikTokRoute.Inbox })

                    TikTokRoute.Library -> OfflineLibraryScreen(
                        onBack = { route = TikTokRoute.Inbox },
                        onAbspielen = { id -> route = TikTokRoute.Player(id) }
                    )

                    is TikTokRoute.Player -> OfflinePlayerOverlay(
                        startId = target.videoId,
                        onBack = { route = TikTokRoute.Library }
                    )
                }
            }
        }

        if (mediaViewerIndex >= 0 && mediaViewerList.isNotEmpty()) {
            MediaViewer(
                messages = mediaViewerList,
                startIndex = mediaViewerIndex,
                onDismiss = { mediaViewerIndex = -1 },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            )
        }

        if (diagnosticsOpen) {
            DiagnosticsSheet(
                isConnected = frontierConnected,
                lastPush = TikTokFrontier.lastPush.collectAsState().value,
                onClose = { diagnosticsOpen = false }
            )
        }
    }
}

@Composable
private fun MissingConversation(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        com.tabslify.tabs.tiktok.ui.TikTokTopBar(
            title = "Chat",
            onBack = onBack
        )
        androidx.compose.material3.Text(
            text = "Chat nicht mehr vorhanden",
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.Center)
                .padding(24.dp),
            color = com.tabslify.core.ui.TextSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TikTokBackground(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BgDeep,
                        BgSurface,
                        AccentSoft,
                        BgSurface,
                        BgDeep
                    )
                )
            ),
        content = content
    )
}
