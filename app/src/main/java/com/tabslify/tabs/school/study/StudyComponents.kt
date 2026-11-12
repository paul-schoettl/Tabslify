package com.tabslify.tabs.school.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.ui.AccentFill
import com.tabslify.core.ui.AccentViolet
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgDeep
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.ErrorSoft
import com.tabslify.core.ui.OnAccentFill
import com.tabslify.core.ui.OnAccentFillMuted
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary

@Composable
fun StudyHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    paddingValues: PaddingValues,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(BgDeep, Color.Transparent)))
            .padding(paddingValues)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.zuruck),
                    tint = TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions?.invoke()
    }
}

@Composable
fun StudyPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) AccentFill else BgCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) OnAccentFill else TextTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun StudySecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) TextSecondary else TextTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun StudyErrorBanner(text: String?) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorRed.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = ErrorSoft, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = ErrorSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StudyNoticeBanner(text: String?) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ℹ", color = AccentViolet, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StudyProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AccentFill,
            trackColor = BgSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.study_plan_done_short, done, total),
            color = TextTertiary,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun StudySectionTitle(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
fun studyKindLabel(kind: String): String = when (kind) {
    "vocab" -> stringResource(R.string.study_vocab)
    "text_section" -> stringResource(R.string.study_text_section)
    else -> kind
}

@Composable
fun StudySourceChip(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = TextTertiary, fontSize = 10.sp)
    }
}

@Composable
fun StudyConfidenceWarning() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ErrorRed.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("⚠", color = ErrorSoft, fontSize = 11.sp)
        Text(stringResource(R.string.study_confidence_low), color = ErrorSoft, fontSize = 10.sp)
    }
}

@Composable
fun StudyEmptyState(
    title: String,
    text: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📚", fontSize = 44.sp)
        Text(title, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(text, color = TextSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        StudyPrimaryButton(text = actionText, onClick = onAction)
    }
}

@Composable
fun StudyTypeChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AccentFill)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = OnAccentFillMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
