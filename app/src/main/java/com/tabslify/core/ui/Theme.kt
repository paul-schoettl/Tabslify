package com.tabslify.core.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tabslify.R

val APP_COLOR = Color(0xFF08080B)
val APP_BLUE = Color(0xFF7C6CFF)
val gruen = Color(0xFF4ADE80)

val BgDeep = Color(0xFF08080B)
val BgSurface = Color(0xFF101014)
val BgCard = Color(0xFF16161B)
val BgScrimTop = Color(0xFF101014)
val BgScrimBottom = Color(0xFF050507)
val Border = Color(0xFF26262E)
val Accent = Color(0xFF7C6CFF)
val AccentDim = Color(0xFF4A3FA0)
val AccentSoft = Color(0xFF221F3A)
val AccentViolet = Accent
val AccentVioletDim = AccentDim
val TextPrimary = Color(0xFFF4F4F7)
val TextSecondary = Color(0xFFA6A6B2)
val TextTertiary = Color(0xFF6E6E7C)
val SuccessGreen = Color(0xFF4ADE80)
val ErrorRed = Color(0xFFF87171)

val AppColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDim,
    onPrimaryContainer = TextPrimary,
    secondary = AccentDim,
    onSecondary = TextPrimary,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    error = ErrorRed,
    onError = Color.Black
)

val AppFontFamily = FontFamily(
    Font(R.font.smb_regular, FontWeight.Normal),
    Font(R.font.smb, FontWeight.Bold),
)

private val baseline = Typography()

val Typography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = AppFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = AppFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = AppFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = AppFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = AppFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = AppFontFamily),
)
