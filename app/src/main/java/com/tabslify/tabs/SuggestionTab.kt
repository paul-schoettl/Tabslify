package com.tabslify.tabs

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.tabslify.R
import com.tabslify.core.objects.Config
import com.tabslify.core.ui.Accent
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class DailySuggestion(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val duration: Int?,
    val reason: String,
    val validUntil: String,
)

private fun parseSuggestion(body: String): DailySuggestion? {
    val root = JSONObject(body)
    val objectValue = root.optJSONObject("suggestion") ?: return null
    val videoId = objectValue.optString("video_id").takeIf { it.isNotBlank() } ?: return null
    val duration = if (objectValue.isNull("duration")) null else objectValue.optInt("duration")
    return DailySuggestion(
        videoId = videoId,
        title = objectValue.optString("title"),
        channel = objectValue.optString("channel"),
        thumbnailUrl = objectValue.optString("thumbnail_url"),
        duration = duration?.takeIf { it > 0 },
        reason = objectValue.optString("reason"),
        validUntil = objectValue.optString("valid_until"),
    )
}

private fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

private fun formatValidUntil(value: String): String {
    if (value.isBlank()) return ""
    return try {
        OffsetDateTime.parse(value)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    } catch (_: Exception) {
        ""
    }
}

@Composable
fun SuggestionTabContent() {
    val context = LocalContext.current
    var suggestion by remember { mutableStateOf<DailySuggestion?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        isLoading = true
        error = null
        try {
            val response = Config.client.functions.invoke(
                function = "get_suggestion",
                body = JSONObject(),
            )
            suggestion = parseSuggestion(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            isLoading = false
        }
    }

    val openVideo = {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "https://youtu.be/${suggestion?.videoId}".toUri())
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "YouTube konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refreshKey++ },
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.suggestion_title),
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.suggestion_subtitle),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.suggestion_refresh),
                            tint = TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                when {
                    isLoading && suggestion == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Accent)
                        }
                    }

                    error != null && suggestion == null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.suggestion_error),
                                color = ErrorRed,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = error.orEmpty(),
                                color = TextTertiary,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(14.dp))
                            TextButton(onClick = { refreshKey++ }) {
                                Text(stringResource(R.string.suggestion_retry), color = Accent)
                            }
                        }
                    }

                    suggestion == null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 50.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.suggestion_empty),
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.suggestion_empty_hint),
                                color = TextSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    else -> {
                        val video = suggestion!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = BgCard.copy(alpha = 0.94f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                if (video.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = video.thumbnailUrl,
                                        contentDescription = video.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(15.dp)),
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                                Text(
                                    text = video.title,
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = video.channel,
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val duration = formatDuration(video.duration)
                                    if (duration.isNotBlank()) {
                                        Text(
                                            text = duration,
                                            color = TextTertiary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                if (video.validUntil.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.suggestion_valid_until,
                                            formatValidUntil(video.validUntil),
                                        ),
                                        color = TextTertiary,
                                        fontSize = 12.sp,
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Accent.copy(alpha = 0.12f))
                                        .padding(12.dp),
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Lightbulb,
                                                contentDescription = null,
                                                tint = Accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.suggestion_reason),
                                                color = Accent,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = video.reason,
                                            color = TextPrimary.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = openVideo,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Accent,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.suggestion_open_youtube),
                                        fontWeight = FontWeight.Bold,
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
