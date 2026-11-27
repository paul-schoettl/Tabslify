package com.tabslify.tabs.tiktok

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.toast
import kotlinx.coroutines.launch

private const val TAG_FEED = "TikTokFeed"
private const val PREFETCH_REST = 3

@Composable
fun TikTokFeedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<TikTokFeedVideo>>(emptyList()) }
    var cursor by remember { mutableStateOf(0L) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(true) }
    var commentsFor by remember { mutableStateOf<String?>(null) }
    val localLikes = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun loadMore(neu: Boolean) {
        if (isLoading) return
        isLoading = true
        if (neu) error = null
        runCatching {
            TikTokFeedApi.loadFeedPage(context, if (neu) 0L else cursor)
        }.onSuccess { page ->
            if (page.error != null) {
                error = page.error
            } else {
                val bekannt = videos.mapTo(HashSet()) { it.videoId }
                val fresh = page.videos.filter { !bekannt.contains(it.videoId) }
                videos = if (neu) page.videos else videos + fresh
                cursor = page.nextCursor
                hasMore = page.hasMore && fresh.isNotEmpty()
            }
        }.onFailure {
            error = it.message ?: "Feed nicht ladbar"
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        if (videos.isEmpty()) loadMore(true)
    }

    BackHandler(enabled = true, onBack = onBack)

    val pagerState = rememberPagerState(pageCount = { videos.size })
    val index by remember { derivedStateOf { pagerState.currentPage } }

    LaunchedEffect(index, videos.size, hasMore, isLoading) {
        if (!hasMore || isLoading || videos.isEmpty()) return@LaunchedEffect
        if (index >= videos.size - PREFETCH_REST) loadMore(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Text(
                            text = error ?: "Kein Feed",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { scope.launch { loadMore(true) } }) {
                            Text("Erneut laden", color = Color.White)
                        }
                        if (error != null) {
                            TextButton(onClick = {
                                TikTokDiagnostics.copy(
                                    context = context,
                                    block = "TikTok Feed Fehler\n$error"
                                )
                            }) { Text("Fehler kopieren", color = Color.White) }
                        }
                    }
                }
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val video = videos[page]
                FeedPage(
                    video = video,
                    active = page == index,
                    isMuted = isMuted,
                    isLiked = localLikes[video.videoId] == true,
                    onToggleMute = { isMuted = !isMuted },
                    onToggleLike = {
                        localLikes[video.videoId] = localLikes[video.videoId] != true
                        toast(
                            context,
                            if (localLikes[video.videoId] == true) {
                                "Lokal gemerkt — TikTok-Like wird nicht gesendet"
                            } else {
                                "Lokale Markierung entfernt"
                            }
                        )
                    },
                    onKommentare = { commentsFor = video.videoId },
                    onShare = { shareVideo(context, video) },
                    onOffline = {
                        scope.launch {
                            runCatching {
                                TikTokOfflineDownloader.enqueue(
                                    context,
                                    OfflineAntrag(
                                        videoId = video.videoId,
                                        title = video.title,
                                        author = video.author,
                                        coverUrl = video.coverUrl,
                                        durationMs = video.durationMillis,
                                        urls = video.urls
                                    )
                                )
                            }.onSuccess {
                                toast(context, "Video kommt in die Offline-Bibliothek")
                            }.onFailure {
                                toast(context, it.message ?: "Offline fehlgeschlagen")
                            }
                        }
                    }
                )
            }

            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }

            Text(
                text = "${index + 1}/${videos.size}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
            )
        }

        commentsFor?.let { awemeId ->
            TikTokCommentsSheet(
                awemeId = awemeId,
                onClose = { commentsFor = null }
            )
        }
    }
}

@Composable
private fun FeedPage(
    video: TikTokFeedVideo,
    active: Boolean,
    isMuted: Boolean,
    isLiked: Boolean,
    onToggleMute: () -> Unit,
    onToggleLike: () -> Unit,
    onKommentare: () -> Unit,
    onShare: () -> Unit,
    onOffline: () -> Unit
) {
    var pausiert by remember(video.videoId) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        if (active && video.urls.isNotEmpty()) {
            FeedVideoPlayer(
                urls = video.urls,
                videoId = video.videoId,
                isMuted = isMuted,
                pausiert = pausiert,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(video.videoId) {
                        detectTapGestures(
                            onTap = { pausiert = !pausiert }
                        )
                    }
            )
        } else {
            AsyncImage(
                model = video.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (pausiert && active) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(RoundedCornerShape(37.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Abspielen",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.35f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 78.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "@" + video.author.ifEmpty { "unbekannt" },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = video.title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (video.musicTitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "♪ ${video.musicTitle}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RailButton(
                label = countLabel(video.diggCount),
                active = isLiked,
                onClick = onToggleLike
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Gefällt mir",
                    tint = if (isLiked) Color(0xFFFF3B5C) else Color.White
                )
            }
            RailButton(
                label = countLabel(video.commentCount),
                active = false,
                onClick = onKommentare
            ) {
                Text("💬", style = MaterialTheme.typography.titleMedium)
            }
            RailButton(label = "Offline", active = false, onClick = onOffline) {
                Text("⬇", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            RailButton(label = "Teilen", active = false, onClick = onShare) {
                Text("↗", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            RailButton(
                label = if (isMuted) "Ton an" else "Ton aus",
                active = false,
                onClick = onToggleMute
            ) {
                Text(
                    text = if (isMuted) "🔇" else "🔊",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = if (active) 0.5f else 0.32f),
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable { onClick() }
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun FeedVideoPlayer(
    urls: List<String>,
    videoId: String,
    isMuted: Boolean,
    pausiert: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cookie = remember(videoId) { TikTokCookieRepository.cachedCookie(context) }
    var varianten by remember(videoId) { mutableStateOf(urls) }
    var index by remember(videoId) { mutableIntStateOf(0) }
    var renewed by remember(videoId) { mutableStateOf(false) }
    var failed by remember(videoId) { mutableStateOf(false) }
    val current = varianten.getOrNull(index).orEmpty()
    val source = remember(cookie) {
        DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(
                mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to TikTokOfflineDownloader.APP_USER_AGENT,
                    "Referer" to TikTokOfflineDownloader.REFERER
                )
            )
        }
    }
    val exo = remember(cookie) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (isMuted) 0f else 1f
                playWhenReady = true
            }
    }
    DisposableEffect(exo) {
        val horcher = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                Log.w(
                    TAG_FEED,
                    "Video $videoId Variante ${index + 1}/${varianten.size} " +
                        "failed: ${playbackError.errorCodeName} ${playbackError.message}"
                )
                when {
                    index + 1 < varianten.size -> index++
                    !renewed -> renewed = true

                    else -> {
                        Log.w(TAG_FEED, "Video $videoId: all variants exhausted")
                        failed = true
                    }
                }
            }
        }
        exo.addListener(horcher)
        onDispose {
            exo.removeListener(horcher)
            exo.release()
        }
    }
    LaunchedEffect(exo, current) {
        if (current.isEmpty()) return@LaunchedEffect
        exo.setMediaItem(MediaItem.fromUri(current))
        exo.prepare()
        exo.playWhenReady = !pausiert
    }
    LaunchedEffect(renewed) {
        if (!renewed) return@LaunchedEffect
        val fresh = runCatching { TikTokFeedApi.detail(context, videoId) }
            .getOrNull()
            ?.firstOrNull()
            ?.urls
            .orEmpty()
        val newItems = (fresh + varianten).distinct()
        if (newItems.size > varianten.size) {
            Log.i(TAG_FEED, "Video $videoId: ${fresh.size} freshItems Varianten nachgeladen")
            varianten = newItems
            index = 0
        } else {
            Log.w(TAG_FEED, "Video $videoId: detail lieferte keine newOnes Varianten")
            failed = true
        }
    }
    LaunchedEffect(exo, isMuted) {
        exo.volume = if (isMuted) 0f else 1f
    }
    LaunchedEffect(exo, pausiert) {
        exo.playWhenReady = !pausiert
    }
    if (failed) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text("Video nicht abspielbar", color = Color.White)
        }
        return
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { view -> view.player = exo },
        modifier = modifier
    )
}

@Composable
private fun TikTokCommentsSheet(
    awemeId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var kommentare by remember(awemeId) { mutableStateOf<List<TikTokComment>>(emptyList()) }
    var isLoading by remember(awemeId) { mutableStateOf(true) }
    var error by remember(awemeId) { mutableStateOf<String?>(null) }
    var offen by remember(awemeId) { mutableStateOf(true) }

    LaunchedEffect(awemeId) {
        isLoading = true
        runCatching { TikTokCommentClient.comments(context, awemeId) }
            .onSuccess { list ->
                kommentare = list
                if (list.isEmpty()) error = "Keine Kommentare ladbar"
            }
            .onFailure { error = it.message }
        isLoading = false
    }

    if (!offen) return
    androidx.compose.ui.window.Dialog(onDismissRequest = {
        offen = false
        onClose()
    }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Kommentare",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                when {
                    isLoading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(strokeWidth = 2.dp) }

                    kommentare.isEmpty() -> Column {
                        Text(
                            text = error ?: "Keine Kommentare",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = {
                            TikTokDiagnostics.copy(
                                context = context,
                                block = "TikTok Kommentare fehlgeschlagen\naweme=$awemeId\n${error.orEmpty()}"
                            )
                        }) { Text("Fehler kopieren") }
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(kommentare, key = { it.id.ifEmpty { it.text } }) { kommentar ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (kommentar.avatarUrl.isNotEmpty()) {
                                        AsyncImage(
                                            model = kommentar.avatarUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = kommentar.user.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = kommentar.user.ifEmpty { "unbekannt" },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = kommentar.text,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    val zusatz = buildString {
                                        if (kommentar.likes > 0L) append("❤ ${kommentar.likes}")
                                        if (kommentar.replyCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("↩ ${kommentar.replyCount}")
                                        }
                                    }
                                    if (zusatz.isNotEmpty()) {
                                        Text(
                                            text = zusatz,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun countLabel(value: Long): String = when {
    value >= 1_000_000L -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format("%.1fK", value / 1_000.0)
    value > 0L -> value.toString()
    else -> "0"
}

private fun shareVideo(context: Context, video: TikTokFeedVideo) {
    val url = "https://www.tiktok.com/@${video.author}/video/${video.videoId}"
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${video.title}\n$url")
        }
        context.startActivity(Intent.createChooser(intent, "Video teilen"))
    }.onFailure {
        Log.w(TAG_FEED, "Teilen fehlgeschlagen: ${it.message}")
    }
}
