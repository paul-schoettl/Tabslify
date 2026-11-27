package com.tabslify.tabs.tiktok

import com.tabslify.core.logging.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.BgSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val TAG = "TikTokOffline"

@Composable
fun OfflineLibraryScreen(
    onBack: () -> Unit,
    onAbspielen: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videos by TikTokOfflineStore.videos.collectAsState()
    val progress by TikTokOfflineStore.progress.collectAsState()
    val candidates by TikTokOfflineStore.candidates.collectAsState()
    var deleteTarget by remember { mutableStateOf<OfflineVideo?>(null) }
    var feedRunning by remember { mutableStateOf(false) }
    var feedFehler by remember { mutableStateOf<String?>(null) }
    var seen by remember { mutableStateOf(TikTokOfflineStore.seen(context)) }
    val free = remember(videos) { TikTokOfflineStore.free(context) }

    LaunchedEffect(Unit) { TikTokOfflineStore.loadStore(context) }
    LaunchedEffect(videos) { seen = TikTokOfflineStore.seen(context) }

    val finished = videos.filter { it.state == TikTokOfflineStore.STATE_DONE }
    val used = TikTokOfflineStore.totalBytes(videos)
    val limitBytes = TikTokOfflineStore.maxBytes(context)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Chats") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline-Videos", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${finished.size}/${TikTokOfflineStore.MAX_VIDEOS} Videos · " +
                        "${formatMb(used)} von ${formatMb(limitBytes)} · frei ${formatMb(free)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Feed", style = MaterialTheme.typography.labelLarge)
            Button(
                onClick = {
                    feedRunning = true
                    feedFehler = null
                    scope.launch {
                        runCatching { TikTokFeedApi.feed(context) }
                            .onSuccess { matches ->
                                TikTokOfflineStore.setCandidates(matches)
                                if (matches.isEmpty()) feedFehler = "Keine Videos erhalten"
                            }
                            .onFailure {
                                feedFehler = it.message ?: "Feed nicht erreichbar"
                                Log.w(TAG, "Feed fehlgeschlagen: ${it.message}")
                            }
                        feedRunning = false
                    }
                },
                enabled = !feedRunning
            ) { Text(if (feedRunning) "lädt…" else "Feed laden") }
        }
        if (candidates.isNotEmpty()) {
            val offen = candidates.count { k -> videos.none { it.videoId == k.videoId } }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$offen von ${candidates.size} noch nicht geladen",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        scope.launch {
                            for (candidate in candidates) {
                                if (videos.any { it.videoId == candidate.videoId }) continue
                                runCatching {
                                    TikTokOfflineDownloader.enqueue(
                                        context,
                                        OfflineAntrag(
                                            videoId = candidate.videoId,
                                            title = candidate.title,
                                            author = candidate.author,
                                            coverUrl = candidate.coverUrl,
                                            durationMs = candidate.durationMillis,
                                            urls = candidate.urls
                                        )
                                    )
                                }.onFailure {
                                    Log.w(TAG, "Alle laden: ${candidate.videoId} ${it.message}")
                                }
                            }
                        }
                    },
                    enabled = offen > 0
                ) { Text("Alle laden ($offen)") }
            }
        }
        feedFehler?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        AutoLimitRow(context)

        Spacer(Modifier.height(4.dp))

        if (videos.isEmpty() && candidates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Noch keine Videos.\n„Feed laden“ holt Vorschläge, im Chat\nkannst du Videos direkt sichern.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(candidates, key = { "k" + it.videoId }) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        loaded = videos.any { it.videoId == candidate.videoId },
                        onLaden = {
                            scope.launch {
                                runCatching {
                                    TikTokOfflineDownloader.enqueue(
                                        context,
                                        OfflineAntrag(
                                            videoId = candidate.videoId,
                                            title = candidate.title,
                                            author = candidate.author,
                                            coverUrl = candidate.coverUrl,
                                            durationMs = candidate.durationMillis,
                                            urls = candidate.urls
                                        )
                                    )
                                }.onFailure { Log.w(TAG, "Laden fehlgeschlagen: ${it.message}") }
                            }
                        }
                    )
                }
                items(videos, key = { it.videoId }) { video ->
                    OfflineRow(
                        video = video,
                        percent = progress[video.videoId] ?: -1,
                        onAbspielen = {
                            TikTokOfflineStore.markSeen(context, video.videoId)
                            seen = TikTokOfflineStore.seen(context)
                            onAbspielen(video.videoId)
                        },
                        onDelete = { deleteTarget = video },
                        onRetry = {
                            scope.launch {
                                runCatching { TikTokOfflineDownloader.enqueue(context, antragAus(video)) }
                                    .onFailure { Log.w(TAG, "Wiederholung fehlgeschlagen: ${it.message}") }
                            }
                        },
                        onCancel = { TikTokOfflineDownloader.cancel(video.videoId) }
                    )
                }
                if (seen.isNotEmpty()) {
                    item(key = "gesehen-kopf") {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Gesehen (${seen.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(seen.toList(), key = { "g$it" }) { videoId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = videoId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val detail = TikTokFeedApi.detail(context, videoId)
                                            val matches = detail.firstOrNull() ?: return@launch
                                            TikTokOfflineDownloader.enqueue(
                                                context,
                                                OfflineAntrag(
                                                    videoId = matches.videoId,
                                                    title = matches.title,
                                                    author = matches.author,
                                                    coverUrl = matches.coverUrl,
                                                    durationMs = matches.durationMillis,
                                                    urls = matches.urls
                                                )
                                            )
                                        }.onFailure {
                                            Log.w(TAG, "Erneut laden fehlgeschlagen: ${it.message}")
                                        }
                                    }
                                }
                            ) { Text("↻") }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialogTabslify(
            title = "Video löschen?",
            text = "${target.title}\nDie Datei wird vom Gerät entfernt.",
            confirmText = "Löschen",
            onConfirm = {
                deleteTarget = null
                scope.launch { TikTokOfflineStore.deleteItem(context, target.videoId) }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun AutoLimitRow(context: android.content.Context) {
    var auto by remember { mutableStateOf(TikTokOfflineStore.isAutoActive(context)) }
    var limit by remember { mutableIntStateOf(TikTokOfflineStore.autoLimit(context)) }
    var limitBytes by remember { mutableLongStateOf(TikTokOfflineStore.maxBytes(context)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto im WLAN", style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = {
                    auto = !auto
                    TikTokOfflineStore.setAutoActive(context, auto)
                    if (auto) TikTokOfflineDownloader.starteAutoKontrolle(context)
                }
            ) {
                Text(
                    text = if (auto) "An" else "Aus",
                    color = if (auto) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        StepperRow(
            text = "Warteschlange: $limit Videos",
            less = {
                limit = (limit - 1).coerceAtLeast(0)
                TikTokOfflineStore.setAutoLimit(context, limit)
            },
            more = {
                limit = (limit + 1).coerceAtMost(TikTokOfflineStore.MAX_AUTO_LIMIT)
                TikTokOfflineStore.setAutoLimit(context, limit)
            }
        )
        StepperRow(
            text = "Platzgrenze: ${formatMb(limitBytes)}",
            less = {
                limitBytes = (limitBytes - TikTokOfflineStore.SCHRITT_BYTES)
                    .coerceAtLeast(TikTokOfflineStore.SCHRITT_BYTES)
                TikTokOfflineStore.setMaxBytes(context, limitBytes)
            },
            more = {
                limitBytes += TikTokOfflineStore.SCHRITT_BYTES
                TikTokOfflineStore.setMaxBytes(context, limitBytes)
            }
        )
        TextButton(
            onClick = { TikTokOfflineDownloader.starteAutoKontrolle(context) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("Warteschlange jetzt prüfen") }
    }
}

@Composable
private fun StepperRow(text: String, less: () -> Unit, more: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        Row {
            TextButton(onClick = less) { Text("−") }
            TextButton(onClick = more) { Text("+") }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: TikTokFeedVideo,
    loaded: Boolean,
    onLaden: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(
            OfflineVideo(
                videoId = candidate.videoId,
                title = candidate.title,
                author = candidate.author,
                coverUrl = candidate.coverUrl,
                streamUrl = "",
                durationMs = candidate.durationMillis,
                sizeBytes = 0L,
                fileName = "",
                state = "",
                error = "",
                positionMs = 0L,
                addedAt = 0L,
                lastPlayedAt = 0L
            ),
            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            Text(
                text = listOf(
                    candidate.author,
                    if (candidate.durationMillis > 0L) durationText(candidate.durationMillis) else "",
                    "${candidate.urls.size} Qualitäten"
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(6.dp))
        if (loaded) {
            Text("✓", style = MaterialTheme.typography.titleMedium)
        } else {
            TextButton(onClick = onLaden) { Text("⤓") }
        }
    }
}

@Composable
private fun OfflineRow(
    video: OfflineVideo,
    percent: Int,
    onAbspielen: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(
                if (video.state == TikTokOfflineStore.STATE_DONE) {
                    Modifier.clickable { onAbspielen() }
                } else Modifier
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(video, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            Text(
                text = video.author.ifEmpty { video.videoId },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = stateText(video, percent),
                style = MaterialTheme.typography.labelSmall,
                color = if (video.state == TikTokOfflineStore.STATE_ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (video.state == TikTokOfflineStore.STATE_DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        when (video.state) {
            TikTokOfflineStore.STATE_DONE -> TextButton(onClick = onAbspielen) { Text("▶") }
            TikTokOfflineStore.STATE_DOWNLOADING -> TextButton(onClick = onCancel) { Text("⏹") }
            TikTokOfflineStore.STATE_ERROR -> TextButton(onClick = onRetry) { Text("↻") }
            else -> TextButton(onClick = onDelete) { Text("✕") }
        }
    }
}

@Composable
private fun Cover(video: OfflineVideo, modifier: Modifier) {
    if (video.coverUrl.isEmpty()) {
        Box(modifier = modifier.background(BgSurface))
        return
    }
    val context = LocalContext.current
    val builder = ImageRequest.Builder(context).data(video.coverUrl)
    if (video.coverUrl.contains("tiktok", ignoreCase = true)) {
        builder.addHeader("Cookie", TikTokCookieRepository.cachedCookie(context))
        builder.addHeader("Referer", "https://www.tiktok.com/")
    }
    AsyncImage(
        model = builder.build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

@Composable
fun OfflinePlayerOverlay(
    startId: String,
    onBack: () -> Unit
) {
    val videos by TikTokOfflineStore.videos.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(startId) {
        TikTokOfflineStore.markSeen(context, startId)
    }
    val list = remember(videos) {
        videos.filter { it.state == TikTokOfflineStore.STATE_DONE }
    }
    val startIndex = remember(list, startId) {
        list.indexOfFirst { it.videoId == startId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(startIndex)
    var current by remember { mutableIntStateOf(startIndex) }

    LaunchedEffect(listState, list) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { current = it }
    }

    BackHandler(enabled = true, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(list, key = { _, video -> video.videoId }) { index, video ->
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    if (index == current) {
                        OfflinePlayer(video)
                    } else {
                        Cover(
                            video,
                            Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = video.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2
                        )
                        Text(
                            text = video.author.ifEmpty { video.videoId },
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        if (list.size > 1) {
            Text(
                text = "${current + 1}/${list.size}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun OfflinePlayer(video: OfflineVideo) {
    val context = LocalContext.current
    val file = remember(video.videoId, video.sizeBytes) {
        File(TikTokOfflineStore.folder(context), video.fileName)
    }
    val exo = remember(video.videoId) {
        if (!file.exists()) {
            Log.w(TAG, "Offline-Datei fehlt ${video.videoId} ${file.absolutePath}")
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(file.absolutePath))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
                seekTo(video.positionMs.coerceAtLeast(0L))
                playWhenReady = true
            }
        }
    }
    LaunchedEffect(video.videoId) {
        while (true) {
            TikTokOfflineStore.savePosition(context, video.videoId, exo?.currentPosition ?: 0L)
            delay(2000)
        }
    }
    val player = exo
    if (player == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Datei fehlt", color = Color.White)
        }
        return
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun antragAus(video: OfflineVideo) = OfflineAntrag(
    videoId = video.videoId,
    title = video.title,
    author = video.author,
    coverUrl = video.coverUrl,
    durationMs = video.durationMs,
    urls = listOf(video.streamUrl)
)

private fun stateText(video: OfflineVideo, percent: Int): String {
    val sizeBytes = if (video.sizeBytes > 0L) " · ${formatMb(video.sizeBytes)}" else ""
    val duration = if (video.durationMs > 0L) " · ${durationText(video.durationMs)}" else ""
    return when (video.state) {
        TikTokOfflineStore.STATE_DONE -> "$sizeBytes$duration"
        TikTokOfflineStore.STATE_DOWNLOADING ->
            if (percent >= 0) "lädt $percent %$sizeBytes" else "lädt$sizeBytes"
        TikTokOfflineStore.STATE_PENDING -> "wartet auf WLAN"
        TikTokOfflineStore.STATE_ERROR -> "fehlgeschlagen: ${video.error}"
        else -> "wartet"
    }
}

fun durationText(ms: Long): String {
    val sekunden = (ms / 1000L).coerceAtLeast(0L)
    return "${sekunden / 60L}:${String.format(Locale.GERMANY, "%02d", sekunden % 60L)}"
}

fun formatMb(bytes: Long): String = if (bytes >= 1024L * 1024L * 1024L) {
    String.format(Locale.GERMANY, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
} else if (bytes >= 1024L * 1024L) {
    String.format(Locale.GERMANY, "%.0f MB", bytes / (1024.0 * 1024.0))
} else {
    String.format(Locale.GERMANY, "%.0f kB", bytes / 1024.0)
}