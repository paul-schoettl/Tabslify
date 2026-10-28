package com.tabslify.core.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R

private const val UI_PREFS = "tabslify_ui"
private const val BACK_HINT_SEEN = "seen_back_hint"

fun hasSeenBackHint(context: Context): Boolean =
    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BACK_HINT_SEEN, false)

fun markBackHintSeen(context: Context) {
    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BACK_HINT_SEEN, true)
        .apply()
}

@Composable
fun BackHintOverlay(onDismiss: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "backhint")
    val swipe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "swipe"
    )
    val edgeAlpha by infinite.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edge"
    )

    val fingerAlpha = when {
        swipe < 0.12f -> swipe / 0.12f
        swipe > 0.62f -> (1f - swipe) / 0.38f
        else -> 1f
    }.coerceIn(0f, 1f)

    val edgeBrush = Brush.verticalGradient(
        listOf(Color(0xFFFF8A4C), Color(0xFFFF5A1F), Color(0xFFB34700))
    )
    val buttonBrush = Brush.horizontalGradient(
        listOf(Color(0xFFFF8A4C), Color(0xFFFF5A1F))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xE0050505), Color(0xF2050505)))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(5.dp)
                .alpha(edgeAlpha)
                .background(
                    edgeBrush,
                    shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(
                        (14 + swipe * 160).dp.roundToPx(),
                        (-40).dp.roundToPx()
                    )
                }
                .size(52.dp)
                .alpha(fingerAlpha)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xF2FFFFFF), Color(0x59FFFFFF), Color(0x1FFFFFFF))
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16110C)),
            shape = RoundedCornerShape(26.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(Color(0x29FF7A1A))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("💡", fontSize = 12.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.tipp),
                        color = Color(0xFFFFC7A3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.zuruck_zur_ubersicht),
                    color = Color(0xFFF7F1EC),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.du_hast_einen_tag_geoffnet),
                    color = Color(0xA8FFFFFF),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(buttonBrush)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.verstanden),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
