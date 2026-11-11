package com.tabslify.core.ui

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabslify.R
import com.tabslify.core.functions.canNotify
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.prvt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

private val TextPrimaryOnboarding = Color(0xFFF7F5FB)
private val TextSecondaryOnboarding = Color(0xB3FFFFFF)
private val ChipBg = Color(0x0DFFFFFF)

private val AccentColors = listOf(Color(0xFF484848), Color(0xFF252525))
private val AccentBrush = Brush.linearGradient(AccentColors)

private val ConfettiColors = listOf(Accent, AccentFill, AccentDim, OnAccentFill)

private data class ConfettiParticle(
    val angleRad: Float,
    val speed: Float,
    val gravity: Float,
    val radius: Float,
    val color: Color
)

private fun createConfettiParticles(density: Float): List<ConfettiParticle> =
    List(Random.nextInt(12, 19)) {
        ConfettiParticle(
            angleRad = -(Math.PI * (0.17 + Random.nextDouble() * 0.66)).toFloat(),
            speed = density * (70f + Random.nextFloat() * 80f),
            gravity = density * (180f + Random.nextFloat() * 80f),
            radius = density * (1.8f + Random.nextFloat() * 1.7f),
            color = ConfettiColors[Random.nextInt(ConfettiColors.size)]
        )
    }

private fun animationsEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
} catch (_: Exception) {
    true
}

private enum class PageKind { LOGO, LANGUAGE, GRID, BULLETS, BULLETS_ALT, SERVICES, FEATURES_TRACKING, FEATURES_CONNECTION, NOTIFICATION_PERMISSION, PERMISSIONS }

private data class OnboardPage(
    val kind: PageKind,
    val titleRes: Int,
    val subtitleRes: Int = 0,
    val icon: ImageVector? = null,
    val iconBrush: Brush = AccentBrush,
    val bulletsRes: List<Int> = emptyList()
)

private val pages = listOf(
    OnboardPage(
        kind = PageKind.LOGO,
        titleRes = R.string.tabslify,
        subtitleRes = R.string.eine_app_die_20_tools
    ),
    OnboardPage(
        kind = PageKind.LANGUAGE,
        titleRes = R.string.language_title,
        subtitleRes = R.string.language_onboarding_subtitle,
        icon = Icons.Filled.Public,
        iconBrush = Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFFB45CFC)))
    ),
    OnboardPage(
        kind = PageKind.GRID,
        titleRes = R.string.alles_an_einem_ort,
        subtitleRes = R.string.ki_chat_passworter_browser_notizen
    ),
    OnboardPage(
        kind = PageKind.BULLETS,
        titleRes = R.string.ki_die_wirklich_hilft,
        icon = Icons.Filled.AutoAwesome,
        iconBrush = Brush.linearGradient(listOf(Color(0xFFB45CFC), Color(0xFF6B4CFC))),
        bulletsRes = listOf(
            R.string.chat_mit_text_bildern,
            R.string.vokabeln_automatisch_aus_fotos,
            R.string.optionale_musik_analyse
        )
    ),
    OnboardPage(
        kind = PageKind.BULLETS_ALT,
        titleRes = R.string.privatsphare_zuerst,
        icon = Icons.Filled.Shield,
        iconBrush = Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFF5C6BFC))),
        bulletsRes = listOf(
            R.string.kein_tracking_keine_werbung,
            R.string.tracking_funktionen_nur_optional
        )
    ),
    OnboardPage(
        kind = PageKind.SERVICES,
        titleRes = R.string.onboarding_services_title,
        subtitleRes = R.string.onboarding_services_subtitle,
        icon = Icons.Filled.Settings,
        iconBrush = Brush.linearGradient(listOf(Accent, Color(0xFF6B4CFC)))
    ),
    OnboardPage(
        kind = PageKind.FEATURES_TRACKING,
        titleRes = R.string.onboarding_features_tracking_title,
        subtitleRes = R.string.onboarding_features_tracking_subtitle,
        icon = Icons.Filled.Explore,
        iconBrush = Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFFB45CFC)))
    ),
    OnboardPage(
        kind = PageKind.FEATURES_CONNECTION,
        titleRes = R.string.onboarding_features_connection_title,
        subtitleRes = R.string.onboarding_features_connection_subtitle,
        icon = Icons.Filled.Public,
        iconBrush = Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFF5C6BFC)))
    ),
    OnboardPage(
        kind = PageKind.NOTIFICATION_PERMISSION,
        titleRes = R.string.notification_permission_title,
        subtitleRes = R.string.notification_permission_subtitle,
        icon = Icons.Filled.Notifications,
        iconBrush = Brush.linearGradient(listOf(Accent, Color(0xFF5C8CFF)))
    ),
    OnboardPage(
        kind = PageKind.PERMISSIONS,
        titleRes = R.string.tabslify_berechtigungen
    )
)

private data class GridTile(val icon: ImageVector, val brush: Brush)

private val gridTiles = listOf(
    GridTile(Icons.AutoMirrored.Filled.Chat, Brush.linearGradient(listOf(Accent, Color(0xFF5C8CFF)))),
    GridTile(Icons.Filled.Lock, Brush.linearGradient(listOf(Color(0xFF9A8CFF), Accent))),
    GridTile(Icons.Filled.Public, Brush.linearGradient(listOf(Color(0xFFD95CFF), Color(0xFFB45CFC)))),
    GridTile(Icons.Filled.School, Brush.linearGradient(listOf(Color(0xFF7A5CFF), Color(0xFF6B4CFC)))),
    GridTile(Icons.Filled.CalendarMonth, Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFF5C6BFC)))),
    GridTile(Icons.Filled.AutoAwesome, Brush.linearGradient(listOf(Color(0xFFFF5CAF), Color(0xFFD95CFF))))
)

@Composable
fun WelcomeOnboardingScreen(
    onFinished: () -> Unit = {},
    onExitStart: () -> Unit = {},
    initialPage: Int = 0,
    currentPage: Int? = null,
    onPageChanged: ((Int) -> Unit)? = null
) {
    val visiblePages = remember {
        pages.filter { page ->
            if (prvt() && (page.kind == PageKind.FEATURES_TRACKING ||
                        page.kind == PageKind.FEATURES_CONNECTION)) {
                return@filter false
            }
            page.kind != PageKind.FEATURES_CONNECTION ||
                FeatureToggle.entries.any {
                    it.group == FeatureGroup.CONNECTION && it.isAvailable()
                }
        }
    }
    val safeInitialPage = remember(initialPage) { initialPage.coerceIn(0, visiblePages.lastIndex) }
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { visiblePages.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == visiblePages.lastIndex
    val skipTargetPage = remember {
        visiblePages.indexOfFirst { it.kind == PageKind.NOTIFICATION_PERMISSION }.let { if (it >= 0) it else visiblePages.lastIndex }
    }

    var confettiToken by remember { mutableIntStateOf(0) }
    var wasLast by remember { mutableStateOf(isLast) }
    LaunchedEffect(isLast) {
        if (isLast && !wasLast) confettiToken += 1
        wasLast = isLast
    }

    val exitProgress = remember { Animatable(0f) }
    val navigatePage: (Boolean) -> Unit = { forward ->
        val target = if (forward) pagerState.currentPage + 1 else pagerState.currentPage - 1
        if (target in visiblePages.indices && target != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }
    val currentNavigatePage by rememberUpdatedState(navigatePage)
    var finishing by remember { mutableStateOf(false) }
    LaunchedEffect(finishing) {
        if (finishing) {
            onExitStart()
            repeat(3) { withFrameNanos { } }
            exitProgress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            onFinished()
        }
    }

    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage != null && currentPage in visiblePages.indices && pagerState.currentPage != currentPage) {
            pagerState.scrollToPage(currentPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
    }

    AppBackground(
        modifier = Modifier
            .systemGestureExclusion()
            .graphicsLayer {
                val p = exitProgress.value
                alpha = 1f - p
                val s = 1f + 0.08f * p
                scaleX = s
                scaleY = s
                translationY = -48.dp.toPx() * p
            },
        scrim = AppBgScrim.STRONG
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        currentNavigatePage(offset.x >= size.width / 2f)
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    PageContent(
                        page = visiblePages[page],
                        isActive = pagerState.currentPage == page,
                        onNavigate = navigatePage,
                        pageOffset = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction }
                    )
                }

                DotsIndicator(
                    count = visiblePages.size,
                    current = pagerState.currentPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )

                GradientButton(
                    label = if (isLast) stringResource(R.string.los_geht_s) else stringResource(R.string.weiter),
                    confettiToken = confettiToken,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp)
                ) {
                    if (isLast) {
                        if (!finishing) finishing = true
                    } else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = stringResource(R.string.uberspringen),
                        color = Color(0x8CFFFFFF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch { pagerState.animateScrollToPage(skipTargetPage) }
                            }
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

private fun Modifier.pageTransition(pageOffset: () -> Float): Modifier = graphicsLayer {
    val offset = pageOffset().coerceIn(-1f, 1f)
    val distance = abs(offset)
    translationX = offset * size.width * 0.32f
    translationY = distance * 30.dp.toPx()
    rotationZ = offset * 6f
    val pageScale = 1f - 0.20f * distance
    scaleX = pageScale
    scaleY = pageScale
    alpha = (1f - 0.70f * distance).coerceIn(0f, 1f)
}

@Composable
private fun PageContent(
    page: OnboardPage,
    isActive: Boolean,
    onNavigate: (Boolean) -> Unit,
    pageOffset: () -> Float
) {
    if (page.kind == PageKind.PERMISSIONS) {
        Box(modifier = Modifier.fillMaxSize().pageTransition(pageOffset)) {
            PermissionInfoScreen(onboarding = true)
        }
        return
    }

    if (page.kind == PageKind.SERVICES) {
        Box(modifier = Modifier.fillMaxSize().pageTransition(pageOffset)) {
            ServicesPage()
        }
        return
    }

    if (page.kind == PageKind.FEATURES_TRACKING) {
        Box(modifier = Modifier.fillMaxSize().pageTransition(pageOffset)) {
            FeaturesPage(
                group = FeatureGroup.TRACKING,
                titleRes = R.string.onboarding_features_tracking_title,
                subtitleRes = R.string.onboarding_features_tracking_subtitle,
                icon = Icons.Filled.Explore
            )
        }
        return
    }

    if (page.kind == PageKind.FEATURES_CONNECTION) {
        Box(modifier = Modifier.fillMaxSize().pageTransition(pageOffset)) {
            FeaturesPage(
                group = FeatureGroup.CONNECTION,
                titleRes = R.string.onboarding_features_connection_title,
                subtitleRes = R.string.onboarding_features_connection_subtitle,
                icon = Icons.Filled.Public
            )
        }
        return
    }

    val currentOnNavigate by rememberUpdatedState(onNavigate)

    val iconScale = remember(page) { Animatable(0.6f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            iconScale.animateTo(
                1f,
                spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)
            )
        } else {
            iconScale.animateTo(0.6f, tween(180))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pageTransition(pageOffset)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnNavigate(offset.x >= size.width / 2f)
                }
            }
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (page.kind) {
            PageKind.LOGO -> {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(iconScale.value)
                        .clip(RoundedCornerShape(32.dp))
                        .background(AccentBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.app_icon_foreground_rounded_corners),
                        contentDescription = stringResource(R.string.tabslify),
                        modifier = Modifier.size(96.dp)
                    )
                }
            }

            PageKind.GRID -> {
                IconGrid(scale = iconScale.value)
            }

            else -> {
                val icon = page.icon
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(iconScale.value)
                            .clip(CircleShape)
                            .background(page.iconBrush),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(34.dp))

        Text(
            text = stringResource(page.titleRes),
            color = TextPrimaryOnboarding,
            fontSize = if (page.kind == PageKind.LOGO) 34.sp else 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        if (page.subtitleRes != 0) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(page.subtitleRes),
                color = TextSecondaryOnboarding,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center
            )
        }

        if (page.bulletsRes.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            page.bulletsRes.forEach { lineRes ->
                BulletRow(text = stringResource(lineRes))
                Spacer(Modifier.height(14.dp))
            }
        }

        if (page.kind == PageKind.LANGUAGE) {
            Spacer(Modifier.height(22.dp))
            LanguageOptions()
        }

        if (page.kind == PageKind.NOTIFICATION_PERMISSION) {
            NotificationPermissionEffect(isActive = isActive)
        }
    }
}

@Composable
private fun ServicesPage() {
    val context = LocalContext.current
    var states by remember { mutableStateOf(readServiceToggleStates(context)) }
    val masterEnabled = states[ServiceToggle.MASTER] == true

    LaunchedEffect(Unit) {
        ensureServiceToggleDefaults(context)
        states = readServiceToggleStates(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))

        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.onboarding_services_title),
            color = TextPrimaryOnboarding,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_services_subtitle),
            color = TextSecondaryOnboarding,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        ServiceToggle.entries.filter { it.isAvailable() }.forEach { toggle ->
            val checked = states[toggle] == true
            val gated = toggle.gatedByMaster && !toggle.isMaster
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ChipBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(toggle.titleRes),
                        color = TextPrimaryOnboarding,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(toggle.descriptionRes),
                        color = TextSecondaryOnboarding,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = if (gated) checked && masterEnabled else checked,
                    onCheckedChange = { value ->
                        states = states + (toggle to value)
                        writeServiceToggleStates(context, states)
                        applyServiceToggleStates(context, states, canNotify(context))
                    },
                    enabled = !gated || masterEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray,
                        disabledUncheckedTrackColor = Color.DarkGray.copy(alpha = 0.4f)
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.onboarding_services_note),
            color = Color(0x8CFFFFFF),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun FeaturesPage(
    group: FeatureGroup,
    titleRes: Int,
    subtitleRes: Int,
    icon: ImageVector
) {
    val context = LocalContext.current
    var states by remember { mutableStateOf(readFeatureToggleStates(context)) }

    LaunchedEffect(Unit) {
        ensureFeatureToggleDefaults(context)
        states = readFeatureToggleStates(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))

        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = stringResource(titleRes),
            color = TextPrimaryOnboarding,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(subtitleRes),
            color = TextSecondaryOnboarding,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        FeatureToggle.entries
            .filter { it.group == group && it.isAvailable() }
            .forEach { feature ->
                val checked = states[feature] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ChipBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(feature.titleRes),
                            color = TextPrimaryOnboarding,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(feature.descriptionRes),
                            color = TextSecondaryOnboarding,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = checked,
                        onCheckedChange = { value ->
                            states = states + (feature to value)
                            writeFeatureToggleStates(context, states)
                            applyFeatureToggleStates(context, states)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.DarkGray,
                            disabledUncheckedTrackColor = Color.DarkGray.copy(alpha = 0.4f)
                        )
                    )
                }
            }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun NotificationPermissionEffect(isActive: Boolean) {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        applyServiceToggleStates(
            context,
            readServiceToggleStates(context),
            canNotify(context)
        )
    }

    LaunchedEffect(isActive) {
        if (isActive && !requested) {
            requested = true
            Config.requestAllDeclaredRuntimePermissions(context, launcher)
        }
    }
}

@Composable
private fun LanguageOptions() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(Config.currentAppLanguage(context)) }
    val options = listOf(
        "de" to stringResource(R.string.language_german),
        "en" to stringResource(R.string.language_english)
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        options.forEach { (tag, label) ->
            LanguageOptionRow(
                label = label,
                isSelected = selected == tag,
                onClick = {
                    selected = tag
                    Config.setAppLanguage(context, tag)
                }
            )
        }
    }
}

@Composable
private fun LanguageOptionRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ChipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextPrimaryOnboarding, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (isSelected) Modifier.background(AccentBrush)
                    else Modifier.border(BorderStroke(1.dp, Color(0x59FFFFFF)), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun IconGrid(scale: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        gridTiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { tile ->
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(22.dp))
                            .background(tile.brush),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tile.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BulletRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ChipBg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(AccentBrush)
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, color = Color(0xD1FFFFFF), fontSize = 15.sp)
    }
}

/* ------------------------------------------------------------------ */
/*  Dots + button                                                     */
/* ------------------------------------------------------------------ */

@Composable
private fun DotsIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val active = i == current
            val width by animateDpAsState(if (active) 26.dp else 8.dp, tween(250), label = "dotW")
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) AccentBrush
                        else Brush.linearGradient(listOf(Color(0x38FFFFFF), Color(0x38FFFFFF)))
                    )
            )
        }
    }
}

@Composable
private fun GradientButton(
    label: String,
    modifier: Modifier = Modifier,
    confettiToken: Int = 0,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val progress = remember { Animatable(0f) }
    var particles by remember { mutableStateOf<List<ConfettiParticle>>(emptyList()) }

    LaunchedEffect(confettiToken) {
        if (confettiToken <= 0) return@LaunchedEffect
        particles = emptyList()
        progress.snapTo(0f)
        if (!animationsEnabled(context)) return@LaunchedEffect
        particles = createConfettiParticles(density)
        progress.animateTo(1f, tween(550, easing = LinearEasing))
        particles = emptyList()
    }

    Box(modifier = modifier.height(56.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(28.dp))
                .background(AccentBrush)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        if (particles.isNotEmpty() && progress.value > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(160.dp)
                    .align(Alignment.Center)
            ) {
                val t = progress.value * 0.55f
                val origin = Offset(size.width / 2f, size.height / 2f)
                val particleScale = 1f - 0.55f * progress.value
                val fade = (1f - progress.value).coerceIn(0f, 1f)
                particles.forEach { particle ->
                    val travel = particle.speed * t
                    val x = origin.x + cos(particle.angleRad) * travel
                    val y = origin.y + sin(particle.angleRad) * travel + 0.5f * particle.gravity * t * t
                    drawCircle(
                        color = particle.color.copy(alpha = fade),
                        radius = particle.radius * particleScale,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}