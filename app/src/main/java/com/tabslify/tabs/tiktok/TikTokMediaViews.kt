package com.tabslify.tabs.tiktok

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun TikTokVideoPlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    startMuted: Boolean = false
) {
    val context = LocalContext.current
    val cookie = remember(streamUrl) { TikTokCookieRepository.cachedCookie(context) }
    var isMuted by remember(streamUrl) { mutableStateOf(startMuted) }
    val exo = remember(streamUrl, cookie) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to TikTokOfflineDownloader.APP_USER_AGENT,
                    "Referer" to TikTokOfflineDownloader.REFERER
                )
            )
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = false
            }
    }
    LaunchedEffect(exo, isMuted) {
        exo.volume = if (isMuted) 0f else 1f
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view -> view.player = exo },
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 58.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable { isMuted = !isMuted }
        ) {
            Text(
                text = if (isMuted) "🔇" else "🔊",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun TikTokVoicePlayer(
    streamUrl: String,
    messageId: String = "",
    durationMs: Long = 0L,
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cookie by remember(streamUrl) { mutableStateOf(TikTokCookieRepository.cachedCookie(context)) }
    var source by remember(streamUrl, messageId) {
        mutableStateOf(
            if (messageId.isNotEmpty()) TikTokVoiceStore.path(context, messageId) else ""
        )
    }
    var isPlaying by remember(streamUrl, messageId) { mutableStateOf(false) }
    var position by remember(streamUrl, messageId) { mutableLongStateOf(0L) }
    var total by remember(streamUrl, messageId) { mutableLongStateOf(durationMs) }
    var isBuffering by remember(streamUrl, messageId) { mutableStateOf(false) }
    var hasError by remember(streamUrl, messageId) { mutableStateOf(false) }
    LaunchedEffect(streamUrl, messageId) {
        if (source.isNotEmpty()) return@LaunchedEffect
        if (!TikTokCookieRepository.hasCachedAppSession(context)) {
            val fresh = runCatching { TikTokCookieRepository.refresh(context) }.getOrNull()
            if (fresh != null) cookie = fresh.cookie
        }
        if (messageId.isNotEmpty()) {
            val local = TikTokVoiceStore.path(context, messageId)
            if (local.isNotEmpty()) {
                source = local
                return@LaunchedEffect
            }
        }
        val loaded = TikTokVoiceStore.load(context, messageId, streamUrl)
        source = loaded.ifEmpty { streamUrl }
    }
    val exo = remember(source, cookie) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to TikTokOfflineDownloader.APP_USER_AGENT,
                    "Referer" to TikTokOfflineDownloader.REFERER
                )
            )
        val isLocal = source.isNotEmpty() && !source.startsWith("http")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                if (isLocal) DefaultMediaSourceFactory(context)
                else DefaultMediaSourceFactory(dataSource)
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(source.ifEmpty { streamUrl }))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    val measured = exo.duration
                    if (measured > 0L) total = measured
                }
                if (state == Player.STATE_ENDED) {
                    exo.pause()
                    exo.seekTo(0L)
                    position = 0L
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    LaunchedEffect(exo) {
        while (true) {
            runCatching {
                position = exo.currentPosition
                if (exo.playerError != null) hasError = true
            }
            delay(200)
        }
    }
    if (hasError) {
        Text(
            text = "🎤 Sprachnachricht · Ton nicht abrufbar",
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }
    val progress = if (total > 0L) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f))
                .clickable {
                    when {
                        exo.playbackState == Player.STATE_ENDED ||
                            exo.playbackState == Player.STATE_IDLE -> {
                            exo.seekTo(0L)
                            exo.play()
                        }

                        exo.isPlaying -> exo.pause()
                        else -> exo.play()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering && !isPlaying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = tint
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(total) {
                        detectTapGestures { point ->
                            if (total > 0L && size.width > 0) {
                                exo.seekTo(
                                    (point.x / size.width * total).toLong().coerceIn(0L, total)
                                )
                                position = exo.currentPosition
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.25f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = durationText(position),
                    color = tint.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = durationText(total),
                    color = tint.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun MediaViewer(
    messages: List<TikTokMessage>,
    startIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, messages.lastIndex),
        pageCount = { messages.size }
    )
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }
    val reset = {
        scale = 1f
        offset = Offset.Zero
    }
    androidx.activity.compose.BackHandler(enabled = true) {
        if (scale > 1f) reset() else onDismiss()
    }
    val current = messages.getOrNull(pagerState.currentPage)
    Box(
        modifier = modifier.background(Color(0xF2000000)),
        contentAlignment = Alignment.Center
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val message = messages[page]
            val isCurrent = page == pagerState.currentPage
            val isVideo = message.mediaKind == "video" && message.mediaStream.isNotEmpty()
            if (isVideo) {
                if (isCurrent) {
                    TikTokVideoPlayer(
                        streamUrl = message.mediaStream,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                } else {
                    Box(Modifier.fillMaxSize())
                }
            } else {
                FullscreenImage(
                    message = message,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = if (isCurrent) scale else 1f,
                            scaleY = if (isCurrent) scale else 1f,
                            translationX = if (isCurrent) offset.x else 0f,
                            translationY = if (isCurrent) offset.y else 0f
                        )
                        .pointerInput(message.id, isCurrent) {
                            if (!isCurrent) return@pointerInput
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 6f)
                                scale = next
                                offset = if (next > 1f) {
                                    val bound = size.width.toFloat() * (next - 1f) / 2f
                                    Offset(
                                        (offset.x + pan.x).coerceIn(-bound, bound),
                                        (offset.y + pan.y).coerceIn(-bound, bound)
                                    )
                                } else {
                                    Offset.Zero
                                }
                            }
                        }
                        .pointerInput(message.id, isCurrent) {
                            if (!isCurrent) return@pointerInput
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) reset() else scale = 2.5f
                                }
                            )
                        }
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${messages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            var isSaving by remember { mutableStateOf(false) }
            if (current != null && current.mediaKind != "video" && current.mediaUrl.isNotEmpty()) {
                val image = current
                TextButton(
                    onClick = {
                        if (isSaving) return@TextButton
                        isSaving = true
                        scope.launch {
                            val path = TikTokImageStore.path(context, image.id).ifEmpty {
                                TikTokImageStore.load(
                                    context,
                                    image.id,
                                    image.mediaUrl,
                                    image.mediaKey,
                                    image.convId
                                )
                            }
                            val saved = path.isNotEmpty() &&
                                saveImageToGallery(context, File(path), "tiktok_${image.id}")
                            toast(
                                context,
                                if (saved) "Bild gespeichert" else "Speichern fehlgeschlagen"
                            )
                            isSaving = false
                        }
                    },
                    enabled = !isSaving
                ) {
                    Text(
                        text = if (isSaving) "…" else "Speichern",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }
    }
}

@Composable
fun FullscreenImage(message: TikTokMessage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var file by remember(message.id) {
        mutableStateOf(TikTokImageStore.path(context, message.id).takeIf { it.isNotEmpty() })
    }
    var hasError by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id, message.mediaUrl, message.mediaKey) {
        if (file != null) return@LaunchedEffect
        val loaded = TikTokImageStore.load(
            context = context,
            messageId = message.id,
            url = message.mediaUrl,
            keyHex = message.mediaKey,
            convId = message.convId
        )
        if (loaded.isNotEmpty()) file = loaded else hasError = true
    }
    when {
        file != null -> AsyncImage(
            model = File(file!!),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )

        hasError -> Text(
            text = "Bild nicht ladbar",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        else -> CircularProgressIndicator(color = Color.White)
    }
}

private suspend fun saveImageToGallery(context: Context, file: File, name: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.${extensionOf(file)}")
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(file))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tabslify")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target: Uri = resolver.insert(collection, values) ?: return@runCatching false
            resolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            true
        }.getOrDefault(false)
    }

private fun mimeOf(file: File): String {
    val header = ByteArray(12)
    val read = runCatching {
        file.inputStream().use { it.read(header) }
    }.getOrDefault(0)
    if (read >= 12 && String(header, 0, 4, Charsets.ISO_8859_1) == "RIFF") return "image/webp"
    if (read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return "image/jpeg"
    if (read >= 4 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte()) return "image/png"
    if (read >= 4 && String(header, 0, 3, Charsets.ISO_8859_1) == "GIF") return "image/gif"
    return "image/*"
}

private fun extensionOf(file: File): String = when (mimeOf(file)) {
    "image/webp" -> "webp"
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    else -> "img"
}

@Composable
fun TikTokMedia(
    message: TikTokMessage,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookieVersion by remember(message.mediaUrl) { mutableIntStateOf(0) }
    var retried by remember(message.mediaUrl) { mutableStateOf(false) }
    var localFile by remember(message.mediaUrl, message.id) { mutableStateOf<File?>(null) }
    val cookie = remember(message.mediaUrl, cookieVersion) {
        TikTokCookieRepository.cachedCookie(context)
    }
    LaunchedEffect(message.id, message.mediaUrl, message.mediaKey, cookie.length) {
        Log.d(
            TAG_MEDIA,
            "image ${message.mediaKind} host=${hostOf(message.mediaUrl)} " +
                "key=${message.mediaKey.length} cookie=${cookie.length}"
        )
        if (localFile != null) return@LaunchedEffect
        val existing = TikTokImageStore.path(context, message.id).takeIf { it.isNotEmpty() }
        if (existing != null) {
            localFile = File(existing)
            return@LaunchedEffect
        }
        val loaded = TikTokImageStore.load(
            context = context,
            messageId = message.id,
            url = message.mediaUrl,
            keyHex = message.mediaKey,
            convId = message.convId
        )
        if (loaded.isNotEmpty()) {
            localFile = File(loaded)
        }
    }
    val builder = ImageRequest.Builder(context)
        .data(localFile ?: message.mediaUrl)
        .listener(object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                Log.d(TAG_MEDIA, "image loaded id=${message.id} source=${result.dataSource}")
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                Log.d(TAG_MEDIA, "image ERROR id=${message.id} ${result.throwable}")
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

internal fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

private const val TAG_MEDIA = "TikTokTab"
