package com.tabslify.tabs.notes

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.tabslify.R
import com.tabslify.core.logging.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private sealed interface LinkPreviewState {
    data object Loading : LinkPreviewState
    data class Ready(val record: LinkPreviewRecord) : LinkPreviewState
    data object Failed : LinkPreviewState
}

@Composable
fun NoteLinkPreviewSection(
    urls: List<String>,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    if (urls.isEmpty()) return
    val context = LocalContext.current
    val states = remember { mutableStateMapOf<String, LinkPreviewState>() }

    LaunchedEffect(urls) {
        val cached = loadLinkPreviews(context)
        urls.forEach { url ->
            if (states[url] == null) states[url] = cached[url].toState()
        }
        delay(600.milliseconds)
        val now = System.currentTimeMillis()
        val stale = urls.filter { url ->
            val record = cached[url]
            record == null || record.isStale(now)
        }
        if (stale.isEmpty()) return@LaunchedEffect
        stale.forEach { url ->
            if (cached[url] == null) states[url] = LinkPreviewState.Loading
        }
        val results = coroutineScope {
            stale.map { url -> async { url to fetchLinkPreview(url) } }.awaitAll()
        }
        saveLinkPreviews(context, results.map { it.second })
        results.forEach { (url, record) ->
            val previous = cached[url]
            val keepPrevious = record.failed && previous != null && !previous.failed
            states[url] = if (keepPrevious) previous.toState() else record.toState()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        urls.forEach { url ->
            LinkPreviewRow(
                url = url,
                state = states[url] ?: LinkPreviewState.Loading,
                textColor = textColor,
                onClick = { openUrlExternally(context, url) }
            )
        }
    }
}

@Composable
private fun LinkPreviewRow(
    url: String,
    state: LinkPreviewState,
    textColor: Color,
    onClick: () -> Unit
) {
    val record = (state as? LinkPreviewState.Ready)?.record
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkPreviewThumbnail(state = state, textColor = textColor)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record?.title?.takeIf { it.isNotBlank() } ?: linkHost(url),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                record?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (state) {
                        is LinkPreviewState.Loading -> stringResource(R.string.link_vorschau_laden)
                        is LinkPreviewState.Failed -> stringResource(R.string.link_vorschau_nicht_verfugbar)
                        is LinkPreviewState.Ready ->
                            record?.siteName?.takeIf { it.isNotBlank() } ?: linkHost(url)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.link_offnen),
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun LinkPreviewThumbnail(state: LinkPreviewState, textColor: Color) {
    val base = Modifier
        .size(56.dp)
        .clip(RoundedCornerShape(8.dp))
    val imageUrl = (state as? LinkPreviewState.Ready)?.record?.imageUrl
    when {
        !imageUrl.isNullOrBlank() -> AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base.background(Color.White.copy(alpha = 0.08f))
        )

        state is LinkPreviewState.Loading -> Box(base, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = textColor.copy(alpha = 0.7f)
            )
        }

        else -> Box(
            modifier = base.background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun LinkPreviewRecord?.toState(): LinkPreviewState = when {
    this == null -> LinkPreviewState.Loading
    failed -> LinkPreviewState.Failed
    else -> LinkPreviewState.Ready(this)
}

private fun openUrlExternally(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { AppLog.w("NoteLinkPreview", "Link konnte nicht geöffnet werden") }
}
