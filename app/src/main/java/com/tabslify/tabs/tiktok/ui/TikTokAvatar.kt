package com.tabslify.tabs.tiktok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tabslify.tabs.tiktok.TikTokAvatarStore
import java.io.File

@Composable
fun TikTokAvatar(
    uid: String,
    name: String,
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color? = null,
    fallbackSeed: Int = 0
) {
    val context = LocalContext.current
    var path by remember(uid, url) { mutableStateOf(TikTokAvatarStore.path(context, uid)) }
    LaunchedEffect(uid, url) {
        if (path.isNotEmpty() || url.isEmpty()) return@LaunchedEffect
        val loaded = TikTokAvatarStore.load(context, uid, url)
        if (loaded.isNotEmpty()) path = loaded
    }
    val base = Modifier
        .size(size)
        .clip(CircleShape)
    val ringed = if (ringColor != null) {
        base.border(TikTokTokens.AvatarRingWidth, ringColor, CircleShape)
    } else {
        base
    }
    Box(
        modifier = modifier
            .then(ringed)
            .background(placeholderColor(uid, fallbackSeed)),
        contentAlignment = Alignment.Center
    ) {
        if (path.isNotEmpty()) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initialOf(name, uid),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = initialSize(size)
            )
        }
    }
}

private fun initialOf(name: String, uid: String): String {
    val source = name.trim().ifEmpty { uid }
    val letter = source.firstOrNull { it.isLetterOrDigit() }
    return letter?.uppercase() ?: "?"
}

private fun initialSize(size: Dp): TextUnit = when {
    size.value >= 80f -> 34.sp
    size.value >= 44f -> 18.sp
    else -> 14.sp
}

private fun placeholderColor(uid: String, seed: Int): Color {
    val palette = listOf(
        Color(0xFF4A3FA0),
        Color(0xFF2E4E6B),
        Color(0xFF5B3550),
        Color(0xFF365B47),
        Color(0xFF6B4A2E),
        Color(0xFF3D4A6B)
    )
    val key = uid.ifEmpty { seed.toString() }
    val index = (key.hashCode().and(0x7FFFFFFF) + seed) % palette.size
    return palette[index]
}

@Composable
fun TikTokStreakChip(value: Int?, active: Boolean, restorable: Boolean) {
    if (!active && !restorable && value == null) return
    val label = when {
        value != null && value > 0 -> "$value"
        restorable -> "!"
        else -> "🔥"
    }
    Box(
        modifier = Modifier
            .clip(TikTokTokens.DatePillShape)
            .background(TikTokTokens.StreakFlame.copy(alpha = if (active) 0.18f else 0.10f))
            .border(
                width = 1.dp,
                color = TikTokTokens.StreakFlame.copy(alpha = if (active) 0.45f else 0.25f),
                shape = TikTokTokens.DatePillShape
            )
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = "🔥 $label",
            style = MaterialTheme.typography.labelSmall,
            color = TikTokTokens.StreakFlame
        )
    }
}
