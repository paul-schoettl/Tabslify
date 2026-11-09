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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    onZurueck: () -> Unit,
    onAbspielen: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videos by TikTokOfflineStore.videos.collectAsState()
    val fortschritt by TikTokOfflineStore.fortschritt.collectAsState()
    val kandidaten by TikTokOfflineStore.kandidaten.collectAsState()
    var loeschenZiel by remember { mutableStateOf<OfflineVideo?>(null) }
    var feedLaeuft by remember { mutableStateOf(false) }
    var feedFehler by remember { mutableStateOf<String?>(null) }
    val frei = remember(videos) { TikTokOfflineStore.frei(context) }

    LaunchedEffect(Unit) { TikTokOfflineStore.lade(context) }

    val fertig = videos.filter { it.state == TikTokOfflineStore.STATE_DONE }
    val genutzt = TikTokOfflineStore.summeBytes(videos)
    val grenze = TikTokOfflineStore.maxBytes(context)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onZurueck) { Text("‹ Chats") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline-Videos", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${fertig.size}/${TikTokOfflineStore.MAX_VIDEOS} Videos · " +
                        "${mb(genutzt)} von ${mb(grenze)} · frei ${mb(frei)}",
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
                    feedLaeuft = true
                    feedFehler = null
                    scope.launch {
                        runCatching { TikTokFeedApi.feed(context) }
                            .onSuccess { treffer ->
                                TikTokOfflineStore.setzeKandidaten(treffer)
                                if (treffer.isEmpty()) feedFehler = "Keine Videos erhalten"
                            }
                            .onFailure {
                                feedFehler = it.message ?: "Feed nicht erreichbar"
                                Log.w(TAG, "Feed fehlgeschlagen: ${it.message}")
                            }
                        feedLaeuft = false
                    }
                },
                enabled = !feedLaeuft
            ) { Text(if (feedLaeuft) "lädt…" else "Feed laden") }
        }
        feedFehler?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        AutoZeile(context)

        Spacer(Modifier.height(4.dp))

        if (videos.isEmpty() && kandidaten.isEmpty()) {
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
                items(kandidaten, key = { "k" + it.videoId }) { kandidat ->
                    KandidatZeile(
                        kandidat = kandidat,
                        geladen = videos.any { it.videoId == kandidat.videoId },
                        onLaden = {
                            scope.launch {
                                runCatching {
                                    TikTokOfflineDownloader.enqueue(
                                        context,
                                        OfflineAntrag(
                                            videoId = kandidat.videoId,
                                            title = kandidat.titel,
                                            author = kandidat.autor,
                                            coverUrl = kandidat.coverUrl,
                                            durationMs = kandidat.dauerMillis,
                                            urls = kandidat.urls
                                        )
                                    )
                                }.onFailure { Log.w(TAG, "Laden fehlgeschlagen: ${it.message}") }
                            }
                        }
                    )
                }
                items(videos, key = { it.videoId }) { video ->
                    OfflineZeile(
                        video = video,
                        prozent = fortschritt[video.videoId] ?: -1,
                        onAbspielen = { onAbspielen(video.videoId) },
                        onLoeschen = { loeschenZiel = video },
                        onWiederholen = {
                            scope.launch {
                                runCatching { TikTokOfflineDownloader.enqueue(context, antragAus(video)) }
                                    .onFailure { Log.w(TAG, "Wiederholung fehlgeschlagen: ${it.message}") }
                            }
                        },
                        onAbbrechen = { TikTokOfflineDownloader.abbrechen(video.videoId) }
                    )
                }
            }
        }
    }

    loeschenZiel?.let { ziel ->
        AlertDialogTabslify(
            title = "Video löschen?",
            text = "${ziel.title}\nDie Datei wird vom Gerät entfernt.",
            confirmText = "Löschen",
            onConfirm = {
                loeschenZiel = null
                scope.launch { TikTokOfflineStore.loesche(context, ziel.videoId) }
            },
            onDismiss = { loeschenZiel = null }
        )
    }
}

@Composable
private fun AutoZeile(context: android.content.Context) {
    var auto by remember { mutableStateOf(TikTokOfflineStore.autoAktiv(context)) }
    var limit by remember { mutableStateOf(TikTokOfflineStore.autoLimit(context)) }
    var grenze by remember { mutableStateOf(TikTokOfflineStore.maxBytes(context)) }
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
                    TikTokOfflineStore.setzeAutoAktiv(context, auto)
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
        StepperZeile(
            text = "Warteschlange: $limit Videos",
            weniger = {
                limit = (limit - 1).coerceAtLeast(0)
                TikTokOfflineStore.setzeAutoLimit(context, limit)
            },
            mehr = {
                limit = (limit + 1).coerceAtMost(TikTokOfflineStore.MAX_AUTO_LIMIT)
                TikTokOfflineStore.setzeAutoLimit(context, limit)
            }
        )
        StepperZeile(
            text = "Platzgrenze: ${mb(grenze)}",
            weniger = {
                grenze = (grenze - TikTokOfflineStore.SCHRITT_BYTES)
                    .coerceAtLeast(TikTokOfflineStore.SCHRITT_BYTES)
                TikTokOfflineStore.setzeMaxBytes(context, grenze)
            },
            mehr = {
                grenze += TikTokOfflineStore.SCHRITT_BYTES
                TikTokOfflineStore.setzeMaxBytes(context, grenze)
            }
        )
        TextButton(
            onClick = { TikTokOfflineDownloader.starteAutoKontrolle(context) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("Warteschlange jetzt prüfen") }
    }
}

@Composable
private fun StepperZeile(text: String, weniger: () -> Unit, mehr: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        Row {
            TextButton(onClick = weniger) { Text("−") }
            TextButton(onClick = mehr) { Text("+") }
        }
    }
}

@Composable
private fun KandidatZeile(
    kandidat: TikTokFeedVideo,
    geladen: Boolean,
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
                videoId = kandidat.videoId,
                title = kandidat.titel,
                author = kandidat.autor,
                coverUrl = kandidat.coverUrl,
                streamUrl = "",
                durationMs = kandidat.dauerMillis,
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
                text = kandidat.titel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            Text(
                text = listOf(
                    kandidat.autor,
                    if (kandidat.dauerMillis > 0L) dauerText(kandidat.dauerMillis) else "",
                    "${kandidat.urls.size} Qualitäten"
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(6.dp))
        if (geladen) {
            Text("✓", style = MaterialTheme.typography.titleMedium)
        } else {
            TextButton(onClick = onLaden) { Text("⤓") }
        }
    }
}

@Composable
private fun OfflineZeile(
    video: OfflineVideo,
    prozent: Int,
    onAbspielen: () -> Unit,
    onLoeschen: () -> Unit,
    onWiederholen: () -> Unit,
    onAbbrechen: () -> Unit
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
                text = zustandText(video, prozent),
                style = MaterialTheme.typography.labelSmall,
                color = if (video.state == TikTokOfflineStore.STATE_ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (video.state == TikTokOfflineStore.STATE_DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { prozent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        when (video.state) {
            TikTokOfflineStore.STATE_DONE -> TextButton(onClick = onAbspielen) { Text("▶") }
            TikTokOfflineStore.STATE_DOWNLOADING -> TextButton(onClick = onAbbrechen) { Text("⏹") }
            TikTokOfflineStore.STATE_ERROR -> TextButton(onClick = onWiederholen) { Text("↻") }
            else -> TextButton(onClick = onLoeschen) { Text("✕") }
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
    onZurueck: () -> Unit
) {
    val videos by TikTokOfflineStore.videos.collectAsState()
    val liste = remember(videos) {
        videos.filter { it.state == TikTokOfflineStore.STATE_DONE }
    }
    val startIndex = remember(liste, startId) {
        liste.indexOfFirst { it.videoId == startId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(startIndex)
    var aktuell by remember { mutableIntStateOf(startIndex) }

    LaunchedEffect(listState, liste) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { aktuell = it }
    }

    BackHandler(enabled = true, onBack = onZurueck)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(liste, key = { _, video -> video.videoId }) { index, video ->
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    if (index == aktuell) {
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
            onClick = onZurueck,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) { Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        if (liste.size > 1) {
            Text(
                text = "${aktuell + 1}/${liste.size}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
            )
        }
    }
}

@Composable
private fun OfflinePlayer(video: OfflineVideo) {
    val context = LocalContext.current
    val datei = remember(video.videoId, video.sizeBytes) {
        File(TikTokOfflineStore.ordner(context), video.fileName)
    }
    val exo = remember(video.videoId) {
        if (!datei.exists()) {
            Log.w(TAG, "Offline-Datei fehlt ${video.videoId} ${datei.absolutePath}")
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(datei.absolutePath))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
                seekTo(video.positionMs.coerceAtLeast(0L))
                playWhenReady = true
            }
        }
    }
    LaunchedEffect(video.videoId) {
        while (true) {
            TikTokOfflineStore.speicherePosition(context, video.videoId, exo?.currentPosition ?: 0L)
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

private fun zustandText(video: OfflineVideo, prozent: Int): String {
    val groesse = if (video.sizeBytes > 0L) " · ${mb(video.sizeBytes)}" else ""
    val dauer = if (video.durationMs > 0L) " · ${dauerText(video.durationMs)}" else ""
    return when (video.state) {
        TikTokOfflineStore.STATE_DONE -> "$groesse$dauer"
        TikTokOfflineStore.STATE_DOWNLOADING ->
            if (prozent >= 0) "lädt $prozent %$groesse" else "lädt$groesse"
        TikTokOfflineStore.STATE_PENDING -> "wartet auf WLAN"
        TikTokOfflineStore.STATE_ERROR -> "fehlgeschlagen: ${video.error}"
        else -> "wartet"
    }
}

fun dauerText(ms: Long): String {
    val sekunden = (ms / 1000L).coerceAtLeast(0L)
    return "${sekunden / 60L}:${String.format(Locale.GERMANY, "%02d", sekunden % 60L)}"
}

fun mb(bytes: Long): String = if (bytes >= 1024L * 1024L * 1024L) {
    String.format(Locale.GERMANY, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
} else if (bytes >= 1024L * 1024L) {
    String.format(Locale.GERMANY, "%.0f MB", bytes / (1024.0 * 1024.0))
} else {
    String.format(Locale.GERMANY, "%.0f kB", bytes / 1024.0)
}