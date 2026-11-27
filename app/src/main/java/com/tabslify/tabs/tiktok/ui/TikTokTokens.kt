package com.tabslify.tabs.tiktok.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TikTokTokens {

    val ScreenPadding = 16.dp
    val RowPaddingH = 16.dp
    val RowPaddingV = 10.dp
    val SectionGap = 12.dp
    val ItemGap = 2.dp

    val AvatarInbox = 52.dp
    val AvatarChat = 34.dp
    val AvatarProfile = 96.dp
    val AvatarRingWidth = 2.dp

    val BubbleRadius = 18.dp
    val BubbleRadiusTail = 6.dp
    val BubbleMaxWidthFraction = 0.78f
    val BubblePaddingH = 12.dp
    val BubblePaddingV = 8.dp

    val PillRadius = 999.dp
    val PillPaddingH = 12.dp
    val PillPaddingV = 6.dp

    val ComposerRadius = 22.dp
    val ComposerFieldMinHeight = 42.dp

    val OwnBubbleColor = Color(0xFF5A4AD8)
    val PeerBubbleColor = Color(0xFF1D1D25)
    val OwnBubbleText = Color(0xFFF4F4F7)
    val PeerBubbleText = Color(0xFFEDEDF2)
    val TimestampAlpha = 0.55f
    val ReplyQuoteAlpha = 0.14f

    val AccentRing = Color(0xFF7C6CFF)
    val UnreadDot = Color(0xFF7C6CFF)
    val StreakFlame = Color(0xFFFF8A3D)

    val PreviewFontSize = 13.5.sp
    val PreviewLineHeight = 17.sp
    val BubbleFontSize = 15.sp
    val BubbleLineHeight = 20.sp

    val DatePillShape = RoundedCornerShape(PillRadius)
    val BubbleShapePeer = RoundedCornerShape(
        topStart = BubbleRadiusTail,
        topEnd = BubbleRadius,
        bottomEnd = BubbleRadius,
        bottomStart = BubbleRadius
    )
    val BubbleShapeOwn = RoundedCornerShape(
        topStart = BubbleRadius,
        topEnd = BubbleRadiusTail,
        bottomEnd = BubbleRadius,
        bottomStart = BubbleRadius
    )
}
