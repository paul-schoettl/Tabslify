package com.tabslify.core.ui

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabslify.BuildConfig
import com.tabslify.R
import com.tabslify.core.functions.ExactAlarms
import com.tabslify.core.functions.canNotify
import com.tabslify.core.logging.AppLog
import com.tabslify.core.objects.BackupEntry
import com.tabslify.core.objects.BackupOutcome
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.Config.client
import com.tabslify.core.objects.Config.realDevice
import com.tabslify.quiethoursnotificationhelper.checkPermissionsForPrvt
import com.tabslify.core.objects.PasswordStorage
import com.tabslify.core.objects.PrefsBackup
import com.tabslify.core.objects.prvt
import com.tabslify.core.objects.toast
import com.tabslify.privatetabslifyapp.isOnline
import com.tabslify.tabs.ainotify.AiNotifyHost
import com.tabslify.tabs.aitab.AITabViewModel
import com.tabslify.tabs.mediaplayer.MediaAnalyticsManager
import com.tabslify.tabs.virustotal.VirusTotalScanBar
import com.tabslify.tabs.virustotal.pendingVirusTotalReport
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.platform.LocalLocale

private val prvtOnlyMenuItems = setOf(
    MenuItem.GMAIL,
    MenuItem.PRIVATE_CLOUD,
    MenuItem.REMOTEDESKTOP,
    MenuItem.PC_MANAGER,
    MenuItem.FOCUSGUARD,
    MenuItem.HEISE_NEWS,
    MenuItem.DATECALCULATOR,
    MenuItem.TIKTOK,
    MenuItem.CALENDAR,
    MenuItem.PUSHUPS,
    MenuItem.SUGGESTION,
)

fun menuItemVisible(item: MenuItem, context: Context): Boolean =
    item != MenuItem.APKM_INSTALLER &&
            (item != MenuItem.EXPLORE || isFeatureEnabled(context, FeatureToggle.EXPLORE)) &&
            (prvt() || item !in prvtOnlyMenuItems)

fun saveRecentTab(context: Context, menuItem: MenuItem) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val recentTabsString = prefs.getString(KEY_RECENT_TABS, "") ?: ""
    val recentTabs = recentTabsString.split(",")
        .filter { name -> name.isNotEmpty() && MenuItem.entries.any { it.name == name } }
        .toMutableList()

    recentTabs.remove(menuItem.name)
    recentTabs.add(0, menuItem.name)
    val trimmedTabs = recentTabs.take(MAX_RECENT_TABS)

    prefs.edit {
        putString(KEY_RECENT_TABS, trimmedTabs.joinToString(","))
    }
}

fun loadRecentTabs(context: Context): List<MenuItem> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val recentTabsString = prefs.getString(KEY_RECENT_TABS, "") ?: ""
    return recentTabsString.split(",")
        .filter { it.isNotEmpty() }
        .mapNotNull { name ->
            try {
                MenuItem.valueOf(name)
            } catch (_: Exception) {
                null
            }
        }
}

fun getDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
    val model = Build.MODEL
    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        model.replaceFirstChar { it.uppercaseChar() }
    } else {
        "$manufacturer $model"
    }
}

@Composable
fun LandingPageOrApp(
    storage: Storage,
    startTarget: String?,
    svm: SharedViewModel = viewModel(),
    aiVm: AITabViewModel = viewModel()
) {
    val context = LocalContext.current
    var hasLoadedApp by rememberSaveable { mutableStateOf(startTarget != null) }
    var selectedMenuItem by rememberSaveable { mutableStateOf<MenuItem?>(null) }
    var isLandingShown by remember { mutableStateOf(!hasLoadedApp) }
    var showSettings by remember { mutableStateOf(false) }
    var isHelpOpen by remember { mutableStateOf(false) }
    var masterPw by remember { mutableStateOf(PasswordStorage.loadPassword(context)) }
    val landingListState = rememberSaveable(saver = LazyGridState.Saver) {
        LazyGridState()
    }
    var reloadKey by remember { mutableIntStateOf(0) }
    realDevice = !getDeviceName().trim().contains("sdk_gphone", ignoreCase = true)
    var landingReloadTrigger by remember { mutableIntStateOf(0) }

    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        ensureServiceToggleDefaults(context)
        ensureFeatureToggleDefaults(context)
    }
    var showFirstStartPermissionInfo by remember {
        mutableStateOf(!appPrefs.getBoolean("has_seen_permission_info", false))
    }
    var onboardingExiting by remember { mutableStateOf(false) }

    if (realDevice && prvt()) {
        if (masterPw == null || Config.masterPassword.isEmpty()) {
            MasterPasswordSetupScreen(modifier = Modifier.zIndex(10f)) { pw ->
                PasswordStorage.savePassword(context, pw)
                Config.masterPassword = pw
            }
            return
        }
        Config.masterPassword = masterPw!!

        val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        var hasCoords by remember { mutableStateOf(prefs.getBoolean("has_coordinates", false)) }
        if (!hasCoords) {
            CoordinatesSetupScreen(modifier = Modifier.zIndex(10f)) { lat, lon ->
                prefs.edit {
                    putLong("lat_key_d", java.lang.Double.doubleToRawLongBits(lat))
                    putLong("lon_key_d", java.lang.Double.doubleToRawLongBits(lon))
                    putBoolean("has_coordinates", true)
                }
                Config.LAT = lat
                Config.LON = lon
                hasCoords = true
            }
            return
        }
    }

    AnimatedVisibility(
        visible = showFirstStartPermissionInfo,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.zIndex(10f)
    ) {
        WelcomeOnboardingScreen(
            onFinished = {
                appPrefs.edit { putBoolean("has_seen_permission_info", true) }
                showFirstStartPermissionInfo = false
            },
            onExitStart = { onboardingExiting = true },
            initialPage = appPrefs.getInt("onboarding_page", 0),
            onPageChanged = { page -> appPrefs.edit { putInt("onboarding_page", page) } }
        )
    }

    if (showFirstStartPermissionInfo && !onboardingExiting) {
        return
    }

    val sessionStatus by client.auth.sessionStatus.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var online by remember { mutableStateOf(isOnline(context)) }
    var authStuck by remember { mutableStateOf(false) }
    var hadSession by remember { mutableStateOf(sessionStatus is SessionStatus.Authenticated) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                online = isOnline(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionStatus) {
        if (sessionStatus is SessionStatus.Authenticated) hadSession = true
    }

    LaunchedEffect(sessionStatus, online) {
        if (sessionStatus is SessionStatus.Initializing || sessionStatus is SessionStatus.RefreshFailure) {
            if (!online) {
                authStuck = true
            } else {
                delay(10_000.milliseconds)
                authStuck = true
            }
        } else {
            authStuck = false
        }
    }

    if (prvt() && realDevice) {
        when (sessionStatus) {
            is SessionStatus.NotAuthenticated -> {
                SupabaseLoginScreen { }
                return
            }

            is SessionStatus.Authenticated -> Unit

            SessionStatus.Initializing,
            is SessionStatus.RefreshFailure -> {
                if (!hadSession && client.auth.currentSessionOrNull() == null) {
                    if (!online || authStuck) {
                        SupabaseLoginScreen { }
                        return
                    } else {
                        SupabaseLoadingScreen()
                        return
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { }
    }

    LaunchedEffect(startTarget) {
        if (startTarget != null && selectedMenuItem == null) {
            selectedMenuItem = when (startTarget) {
                "weather" -> MenuItem.WEATHER
                "aitab" -> MenuItem.AITAB
                "ai_questions" -> MenuItem.AITAB
                "text_aktionen" -> MenuItem.AITAB
                "apkm" -> MenuItem.APKM_INSTALLER
                "gmail" -> MenuItem.GMAIL
                "virustotal" -> MenuItem.VIRUSTOTAL
                else -> null
            }
            if (selectedMenuItem != null && selectedMenuItem != MenuItem.APKM_INSTALLER) {
                saveRecentTab(context, selectedMenuItem!!)
            }
        }
    }

    val landingOffsetX = remember { Animatable(if (!hasLoadedApp) 0f else -1f) }
    val hazeState = remember { HazeState() }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var webImmersive by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun openLanding() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        showSettings = false
        isHelpOpen = false
        isLandingShown = true
        landingReloadTrigger++
        scope.launch {
            landingOffsetX.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
        }
    }

    fun closeLanding(force: Boolean = false, then: (() -> Unit)? = null) {
        showSettings = false
        isLandingShown = false
        scope.launch {
            landingOffsetX.animateTo(
                -1f,
                tween(if (force) 1 else 300, easing = FastOutSlowInEasing)
            )
            then?.invoke()
            landingOffsetX.snapTo(-1f)
        }
    }

    val pendingAiPrompt by svm.pendingAiPrompt.collectAsState()
    val appliedAiPrompt by svm.appliedAiPrompt.collectAsState()
    val pendingAiChatOpen by svm.pendingAiChatOpen.collectAsState()

    LaunchedEffect(pendingAiPrompt, appliedAiPrompt) {
        val ziel = pendingAiPrompt ?: return@LaunchedEffect
        if (appliedAiPrompt == ziel) return@LaunchedEffect
        aiVm.startNewChatWithPrompt(ziel)
        hasLoadedApp = true
        selectedMenuItem = MenuItem.AITAB
        saveRecentTab(context, MenuItem.AITAB)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        isLandingShown = false
        landingOffsetX.snapTo(-1f)
        svm.markAiPromptApplied(ziel)
    }

    LaunchedEffect(pendingAiChatOpen) {
        val chatId = pendingAiChatOpen ?: return@LaunchedEffect
        aiVm.openChat(chatId)
        hasLoadedApp = true
        selectedMenuItem = MenuItem.AITAB
        saveRecentTab(context, MenuItem.AITAB)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        isLandingShown = false
        landingOffsetX.snapTo(-1f)
        svm.setPendingAiChatOpen(null)
    }

    var pendingOverlayItem by remember { mutableStateOf<MenuItem?>(null) }
    val overlayScale = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(0f) }
    val sharedItem = pendingOverlayItem ?: selectedMenuItem
    val sharedIsLanding = isLandingShown && pendingOverlayItem == null
    val sharedHasLoadedApp = hasLoadedApp || pendingOverlayItem != null
    val appTitle = stringResource(R.string.tabslify)
    val tabTitle = sharedItem?.let { stringResource(it.titleRes) }.orEmpty()
    val sharedTitleTarget = if (sharedIsLanding || tabTitle.isEmpty()) appTitle else tabTitle
    val sharedTitle = rememberScrambledTitle(
        target = sharedTitleTarget,
        initial = tabTitle.ifEmpty { appTitle }
    )

    Box(modifier = Modifier.fillMaxSize()) {

        if (hasLoadedApp) {
            val targetMenuItem = selectedMenuItem ?: startTarget?.let { target ->
                when (target) {
                    "weather" -> MenuItem.WEATHER
                    "aitab" -> MenuItem.AITAB
                    "ai_questions" -> MenuItem.AITAB
                    "text_aktionen" -> MenuItem.AITAB
                    "apkm" -> MenuItem.APKM_INSTALLER
"gmail" -> MenuItem.GMAIL
                "tiktok" -> MenuItem.TIKTOK
                    "virustotal" -> MenuItem.VIRUSTOTAL
                    else -> null
                }
            }

            key(selectedMenuItem, reloadKey) {
                Box(Modifier.fillMaxSize()) {
                    if (targetMenuItem != null) {
                        PrivateTabslifyApp(
                            storage = storage,
                            startTarget = null,
                            initialMenuItem = targetMenuItem,
                            onMenuClick = { openLanding() },
                            helpVisible = isHelpOpen,
                            onHelpVisibleChange = { isHelpOpen = it },
                            onImmersiveChange = { webImmersive = it }
                        )
                    }
                }
            }

            VirusTotalScanBar(
                onOpenReport = { id ->
                    pendingVirusTotalReport = id
                    selectedMenuItem = MenuItem.VIRUSTOTAL
                    saveRecentTab(context, MenuItem.VIRUSTOTAL)
                    if (!hasLoadedApp) hasLoadedApp = true
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            AiNotifyHost()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = landingOffsetX.value * size.width
                }
        ) {
            LandingPage(
                onTabSelected = { menuItem -> pendingOverlayItem = menuItem },
                hazeState = hazeState,
                topBarHeight = topBarHeight,
                state = landingListState,
                reloadTrigger = landingReloadTrigger
            )
        }

        if (pendingOverlayItem != null) {
            val item = pendingOverlayItem!!
            val graphicsLayer = rememberGraphicsLayer()
            var previewBitmap by remember(item) { mutableStateOf<ImageBitmap?>(null) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = 10000f }
                    .drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                    }
                    .background(APP_COLOR)
            ) {
                PrivateTabslifyApp(
                    storage = storage,
                    startTarget = null,
                    initialMenuItem = item,
                    onMenuClick = null
                )
            }

            LaunchedEffect(item) {
                repeat(5) { withFrameNanos { } }
                val captured = graphicsLayer.toImageBitmap()
                val topPx = with(density) { topBarHeight.roundToPx() }
                previewBitmap = if (topPx > 0 && captured.height > topPx) {
                    Bitmap.createBitmap(
                        captured.asAndroidBitmap(),
                        0,
                        topPx,
                        captured.width,
                        captured.height - topPx
                    ).asImageBitmap()
                } else {
                    captured
                }

                snapshotFlow { previewBitmap }.filterNotNull().first()

                overlayScale.snapTo(0.05f)
                overlayAlpha.snapTo(1f)

                overlayScale.animateTo(
                    1f, tween(durationMillis = 320, easing = FastOutSlowInEasing)
                )

                closeLanding(true)

                selectedMenuItem = item
                saveRecentTab(context, item)

                reloadKey++

                if (!hasLoadedApp) {
                    hasLoadedApp = true
                }

                delay(80.milliseconds)
                overlayAlpha.animateTo(0f, tween(durationMillis = 200))
                previewBitmap = null
                overlayScale.snapTo(0f)
                pendingOverlayItem = null
            }

            if (previewBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topBarHeight)
                        .graphicsLayer {
                            scaleX = overlayScale.value
                            scaleY = overlayScale.value
                            alpha = overlayAlpha.value
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                ) {
                    Image(
                        bitmap = previewBitmap!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !webImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            SharedTopBar(
                titleText = sharedTitle,
                isLandingShown = sharedIsLanding,
                hasLoadedApp = sharedHasLoadedApp,
                selectedMenuItem = sharedItem,
                hazeState = hazeState,
                showLandingBackground = landingOffsetX.value > -0.99f,
                onMenuClick = { if (sharedIsLanding) closeLanding() else openLanding() },
                onHelpClick = { isHelpOpen = true },
                onSettingsClick = { showSettings = true },
                onHeightChanged = { topBarHeight = it },
                interactive = pendingOverlayItem == null
            )
        }

        SettingsFrame(
            visible = showSettings,
            onClose = { showSettings = false }
        )
    }
}

@Composable
fun MasterPasswordSetupScreen(modifier: Modifier, onPasswordSaved: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf("") }
    val isValid = input.length >= 20 && input == confirmed

    Box(
        modifier
            .fillMaxSize()
            .background(BgDeep), contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.master_passwort_einrichten),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.wird_einmalig_gesetzt_mindestens_20),
                color = TextSecondary,
                fontSize = 13.sp
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.passwort)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmed,
                onValueChange = { confirmed = it },
                label = { Text(stringResource(R.string.wiederholen)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (input.isNotEmpty() && input != confirmed)
                Text(
                    stringResource(R.string.passworter_stimmen_nicht_uberein),
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            if (input.isNotEmpty() && input.length < 20)
                Text(
                    stringResource(R.string.mindestens_20_zeichen),
                    color = ErrorRed,
                    fontSize = 12.sp
                )

            Button(
                onClick = { onPasswordSaved(input) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.speichern_starten)) }
        }
    }
}

@Composable
fun CoordinatesSetupScreen(modifier: Modifier, onCoordinatesSaved: (Double, Double) -> Unit) {
    var latInput by remember { mutableStateOf("") }
    var lonInput by remember { mutableStateOf("") }
    val latDouble = latInput.toDoubleOrNull()
    val lonDouble = lonInput.toDoubleOrNull()
    val isValid = latDouble != null && lonDouble != null

    Box(
        modifier
            .fillMaxSize()
            .background(BgDeep), contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.koordinaten_einrichten),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.bitte_gib_deine_standard_koordinaten),
                color = TextSecondary,
                fontSize = 13.sp
            )

            OutlinedTextField(
                value = latInput,
                onValueChange = { latInput = it },
                label = { Text(stringResource(R.string.breitengrad_latitude)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = lonInput,
                onValueChange = { lonInput = it },
                label = { Text(stringResource(R.string.langengrad_longitude)) },
                modifier = Modifier.fillMaxWidth()
            )

            if (latInput.isNotEmpty() && latDouble == null)
                Text(
                    stringResource(R.string.ungultiger_breitengrad),
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            if (lonInput.isNotEmpty() && lonDouble == null)
                Text(
                    stringResource(R.string.ungultiger_langengrad),
                    color = ErrorRed,
                    fontSize = 12.sp
                )

            Button(
                onClick = { onCoordinatesSaved(latDouble!!, lonDouble!!) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.speichern_starten)) }
        }
    }
}

@Composable
fun SupabaseLoadingScreen() {
    AppBackground(scrim = AppBgScrim.LIGHT) {
        CircularProgressIndicator(
            color = Color.White,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun SupabaseLoginScreen(onLoggedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var online by remember { mutableStateOf(isOnline(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                online = isOnline(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppBackground(scrim = AppBgScrim.LIGHT) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.tabslify_login),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(32.dp))
                if (!online) {
                    Text(
                        stringResource(R.string.kein_netzwerk_verfugbar),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.e_mail)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.passwort)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                    )
                )
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .padding(top = 8.dp)
                ) {
                    error?.let {
                        val formattedError = when {
                            it.contains("invalid_credentials") -> stringResource(R.string.invalid_credentials)
                            else -> it
                        }
                        Text(formattedError, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    loading = true
                    scope.launch {
                        try {
                            client.auth.signInWith(Email) {
                                this.email = email; this.password = password
                            }
                            onLoggedIn()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            loading = false
                        }
                    }
                }, enabled = !loading && online, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "..." else stringResource(R.string.anmelden))
                }
            }
        }
    }
}

@Composable
private fun rememberScrambledTitle(
    target: String,
    initial: String
): String {
    var display by remember { mutableStateOf(initial) }

    LaunchedEffect(target) {
        if (display == target) return@LaunchedEffect
        val source = display
        val durationMs = 450f
        val startNanos = withFrameNanos { it }
        var frameNanos = startNanos
        while (true) {
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            val progress = (elapsedMs / durationMs).coerceIn(0f, 1f)
            display = scrambleTitle(source, target, progress, frameNanos)
            if (progress >= 1f) break
            frameNanos = withFrameNanos { it }
        }
    }

    return display
}

private fun scrambleTitle(
    source: String,
    target: String,
    progress: Float,
    frameNanos: Long
): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val length = maxOf(source.length, target.length)
    return buildString {
        for (i in 0 until length) {
            val start = 0.03f + i * 0.035f
            val end = start + 0.40f
            when {
                progress >= end -> append(if (i < target.length) target[i] else ' ')
                progress >= start -> {
                    val index = ((frameNanos / 45_000_000L + i) % charset.length).toInt()
                    append(charset[index])
                }

                else -> append(if (i < source.length) source[i] else ' ')
            }
        }
    }
}

@Composable
private fun SharedTopBar(
    titleText: String,
    isLandingShown: Boolean,
    hasLoadedApp: Boolean,
    selectedMenuItem: MenuItem?,
    hazeState: HazeState,
    showLandingBackground: Boolean,
    onMenuClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHeightChanged: (Dp) -> Unit,
    interactive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val closeLabel = stringResource(R.string.close)
    val menuLabel = stringResource(R.string.menu_offnen)
    val helpRotation by animateFloatAsState(
        targetValue = if (isLandingShown) 90f else 0f,
        animationSpec = tween(220),
        label = "helpRotation"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                onHeightChanged(with(density) { it.size.height.toDp() })
            }
    ) {
        if (showLandingBackground) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .hazeBlur(
                        input = HazeInput.Sources(state = hazeState),
                        style = HazeBlurStyle {
                            backgroundColor(BgDeep)
                            colorEffects(
                                listOf(HazeColorEffect.tint(BgDeep.copy(alpha = 0.72f)))
                            )
                            blurRadius(60.dp)
                            noiseFactor(0f)
                            progressive(
                                HazeProgressive.verticalGradient(
                                    startIntensity = 1f,
                                    endIntensity = 0f
                                )
                            )
                        }
                    )
                    .drawWithContent {
                        drawContent()
                        val fadePx = 24.dp.toPx()
                        val bottomStop = (1f - fadePx / size.height).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                bottomStop to Color.Black,
                                1f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasLoadedApp) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = interactive,
                            onClickLabel = if (isLandingShown) closeLabel else menuLabel
                        ) { onMenuClick() }
                        .semantics {
                            contentDescription = if (isLandingShown) closeLabel else menuLabel
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MorphMenuIcon(open = isLandingShown, tint = TextSecondary)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            if (!isLandingShown && selectedMenuItem != null) {
                Icon(
                    imageVector = selectedMenuItem.iconVector,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else if (isLandingShown) {
                Icon(
                    painter = painterResource(R.drawable.ic_tabslify),
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Spacer(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!isLandingShown && selectedMenuItem == MenuItem.MEDIAPLAYERTAB) {
                Spacer(Modifier.width(96.dp))
            }
            IconButton(
                onClick = if (isLandingShown) onSettingsClick else onHelpClick,
                enabled = interactive,
                modifier = Modifier.size(48.dp)
            ) {
                AnimatedContent(
                    targetState = isLandingShown,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.7f, animationSpec = tween(220))) togetherWith
                            (fadeOut(tween(160)) + scaleOut(targetScale = 0.7f, animationSpec = tween(160)))
                    },
                    modifier = Modifier.graphicsLayer { rotationZ = helpRotation },
                    label = "helpSettings"
                ) { landing ->
                    Icon(
                        imageVector = if (landing) Icons.Default.Settings else Icons.Default.QuestionMark,
                        contentDescription = stringResource(
                            if (landing) R.string.open_settings_frame else R.string.hilfe_offnen
                        ),
                        tint = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun LandingPage(
    onTabSelected: (MenuItem) -> Unit,
    hazeState: HazeState,
    topBarHeight: Dp,
    state: LazyGridState = rememberLazyGridState(),
    reloadTrigger: Int = 0
) {
    val context = LocalContext.current
    var recentTabs by remember { mutableStateOf(loadRecentTabs(context)) }

    LaunchedEffect(reloadTrigger) {
        recentTabs = loadRecentTabs(context)
    }
    val visibleRecentTabs = remember(recentTabs, reloadTrigger) {
        recentTabs.filter { menuItemVisible(it, context) }.take(2)
    }

    val titleMap = MenuItem.entries.associateWith { stringResource(it.titleRes) }

    val allTabsSorted = remember(titleMap, reloadTrigger) {
        MenuItem.entries.filter { menuItemVisible(it, context) }
            .sortedBy { titleMap[it] }
    }
    val txtcolors = TextPrimary

    AppBackground(scrim = AppBgScrim.MEDIUM) {
        CompositionLocalProvider(LocalContentColor provides txtcolors) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = state,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp)
                        .hazeSource(state = hazeState),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item(key = "header_spacer", span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(topBarHeight))
                    }
                    if (visibleRecentTabs.isNotEmpty()) {
                        item(key = "recent_header", span = { GridItemSpan(maxLineSpan) }) {
                            LandingSectionLabel(stringResource(R.string.zuletzt_verwendet))
                        }
                        items(items = visibleRecentTabs, key = { "recent_${it.ordinal}" }) { menuItem ->
                            TabCard(
                                menuItem = menuItem,
                                onClick = { onTabSelected(menuItem) }
                            )
                        }
                        item(key = "divider", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 14.dp),
                                    thickness = 1.dp,
                                    color = Border
                                )
                                LandingSectionLabel(stringResource(R.string.alle_tabs))
                            }
                        }
                    }
                    items(items = allTabsSorted, key = { "all_${it.ordinal}" }) { menuItem ->
                        TabCard(
                            menuItem = menuItem,
                            onClick = { onTabSelected(menuItem) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelectionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { Config.currentAppLanguage(context) }
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "de" to stringResource(R.string.language_german),
        "en" to stringResource(R.string.language_english)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = {
            Text(
                text = stringResource(R.string.language_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    val selected = tag == current
                    TextButton(
                        onClick = {
                            Config.setAppLanguage(context, tag)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (selected) "✓ $label" else label,
                            color = if (selected) Accent else TextPrimary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.abbrechen), color = TextTertiary)
            }
        }
    )
}

@Composable
fun TabCard(
    menuItem: MenuItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "tabPress"
    )
    val shape = RoundedCornerShape(16.dp)
    val badgeShape = RoundedCornerShape(12.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = shape,
        border = BorderStroke(1.dp, Border),
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(badgeShape)
                    .background(AccentSoft)
                    .border(1.dp, Accent.copy(alpha = 0.24f), badgeShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = menuItem.iconVector,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = stringResource(menuItem.titleRes),
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LandingSectionLabel(text: String) {
    Text(
        text = text,
        color = TextTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

private fun isUsageStatsGranted(context: Context): Boolean {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val stats = try {
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
    } catch (_: SecurityException) {
        return false
    }
    return stats != null && stats.isNotEmpty()
}

private enum class PermissionAccess { RUNTIME, SPECIAL, HEALTH, AUTOMATIC, UNAVAILABLE }

private const val NOTIFICATION_LISTENER_PERMISSION = "NOTIFICATION_LISTENER"
private const val HEALTH_PERMISSION_PREFIX = "android.permission.health."
private const val HEALTH_READ_SLEEP = "android.permission.health.READ_SLEEP"
private const val HEALTH_READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

private val specialAccessPermissions = setOf(
    Manifest.permission.SYSTEM_ALERT_WINDOW,
    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
    Manifest.permission.ACCESS_NOTIFICATION_POLICY,
    Manifest.permission.PACKAGE_USAGE_STATS,
    Manifest.permission.SCHEDULE_EXACT_ALARM,
    Manifest.permission.REQUEST_INSTALL_PACKAGES
)

@Suppress("DEPRECATION")
private fun manifestPermissions(context: Context): List<String> =
    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions?.distinct().orEmpty()

private fun hasNotificationListenerService(context: Context): Boolean =
    context.packageManager.queryIntentServices(
        Intent("android.service.notification.NotificationListenerService"),
        PackageManager.GET_META_DATA
    ).any { it.serviceInfo.packageName == context.packageName }

private fun hasNotificationListenerAccess(context: Context): Boolean =
    Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ).orEmpty().split(':').any { it.startsWith("${context.packageName}/") }

private fun permissionDisplayName(permission: String): String =
    permission.removePrefix("android.permission.")
        .removePrefix("com.android.alarm.permission.")
        .removePrefix("health.")

private fun permissionAccess(context: Context, permission: String): PermissionAccess {
    if (permission == NOTIFICATION_LISTENER_PERMISSION) return PermissionAccess.SPECIAL
    if (permission in specialAccessPermissions) return PermissionAccess.SPECIAL
    if (permission.startsWith(HEALTH_PERMISSION_PREFIX)) return PermissionAccess.HEALTH
    val info = try {
        context.packageManager.getPermissionInfo(permission, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return PermissionAccess.UNAVAILABLE
    }
    return when {
        info.protection and PermissionInfo.PROTECTION_DANGEROUS != 0 ->
            PermissionAccess.RUNTIME

        info.protection == PermissionInfo.PROTECTION_NORMAL ->
            PermissionAccess.AUTOMATIC

        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ->
            PermissionAccess.AUTOMATIC

        info.protectionFlags and PermissionInfo.PROTECTION_FLAG_APPOP != 0 ->
            PermissionAccess.SPECIAL

        else -> PermissionAccess.UNAVAILABLE
    }
}

private fun featureAllowsPermission(context: Context, permission: String): Boolean = when {
    permission == Manifest.permission.READ_CONTACTS ->
        isFeatureEnabled(context, FeatureToggle.CONTACTS)

    permission == Manifest.permission.BLUETOOTH_CONNECT ->
        isFeatureEnabled(context, FeatureToggle.BLUETOOTH)

    permission == Manifest.permission.ACCESS_LOCAL_NETWORK ->
        isFeatureEnabled(context, FeatureToggle.LOCAL_NETWORK)

    permission == Manifest.permission.PACKAGE_USAGE_STATS ->
        isFeatureEnabled(context, FeatureToggle.USAGE_STATS)

    permission.startsWith(HEALTH_PERMISSION_PREFIX) ->
        isFeatureEnabled(context, FeatureToggle.HEALTH)

    else -> true
}

fun isPermissionGranted(context: Context, key: String): Boolean {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when (key) {
        NOTIFICATION_LISTENER_PERMISSION -> hasNotificationListenerAccess(context)

        Manifest.permission.POST_NOTIFICATIONS -> canNotify(context)

        Manifest.permission.SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(context)

        Manifest.permission.PACKAGE_USAGE_STATS -> isUsageStatsGranted(context)

        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)

        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> Environment.isExternalStorageManager()

        Manifest.permission.ACCESS_NOTIFICATION_POLICY ->
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted

        Manifest.permission.SCHEDULE_EXACT_ALARM -> ExactAlarms.canScheduleExact(context)

        Manifest.permission.REQUEST_INSTALL_PACKAGES ->
            context.packageManager.canRequestPackageInstalls()

        else -> granted(key)
    }
}

@Composable
private fun PermissionButton(
    permission: String,
    title: String,
    access: PermissionAccess,
    accessLabel: String,
    explanation: String,
    isSelected: Boolean,
    permRefreshKey: Int,
    usageStatsGranted: Boolean?,
    healthGrantedPermissions: Set<String>?,
    healthConnectAvailable: Boolean,
    buttonShape: RoundedCornerShape,
    borderBrush: Brush,
    onShowDetails: () -> Unit,
    onGrant: () -> Unit
) {
    val context = LocalContext.current
    val syncGranted = remember(permRefreshKey, permission) {
        if (permission == Manifest.permission.PACKAGE_USAGE_STATS) null
        else isPermissionGranted(context, permission)
    }
    val granted = when (access) {
        PermissionAccess.AUTOMATIC -> true
        PermissionAccess.UNAVAILABLE -> false
        PermissionAccess.HEALTH ->
            healthGrantedPermissions?.contains(permission) ?: (syncGranted ?: false)

        else -> syncGranted ?: usageStatsGranted ?: false
    }
    val canRequest = !granted && when (access) {
        PermissionAccess.RUNTIME, PermissionAccess.SPECIAL -> true
        PermissionAccess.HEALTH -> healthConnectAvailable
        PermissionAccess.AUTOMATIC, PermissionAccess.UNAVAILABLE -> false
    }
    val exactAlarmBatteryExempt = remember(permRefreshKey, permission) {
        permission == Manifest.permission.SCHEDULE_EXACT_ALARM &&
            ExactAlarms.isExemptFromBatteryOptimizations(context)
    }
    val statusText = when {
        access == PermissionAccess.AUTOMATIC ->
            "● " + stringResource(R.string.permission_label_automatic)

        access == PermissionAccess.UNAVAILABLE ->
            "○ " + stringResource(R.string.permission_label_unavailable)

        granted && exactAlarmBatteryExempt ->
            "● " + stringResource(R.string.permission_label_granted_battery_exempt)

        granted -> "● " + stringResource(R.string.permission_label_granted)
        else -> "○ " + stringResource(R.string.permission_label_not_granted)
    }
    val statusColor = if (granted) SuccessGreen else Color.White.copy(alpha = 0.55f)

    Button(
        onClick = onShowDetails,
        shape = buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) APP_COLOR.copy(
                alpha = 0.3f
            ) else Color.Transparent
        ),
        modifier = Modifier
            .padding(vertical = 5.dp)
            .fillMaxWidth()
            .clip(buttonShape)
            .background(if (isSelected) APP_COLOR.copy(alpha = 0.3f) else APP_COLOR)
            .animateContentSize()
            .border(
                BorderStroke(1.dp, borderBrush),
                buttonShape
            )
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Text(
                accessLabel,
                fontSize = 10.sp,
                color = TextTertiary,
                modifier = Modifier.fillMaxWidth()
            )
            if (explanation.isNotBlank()) {
                Text(
                    explanation,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(
                            if (granted) SuccessGreen.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.10f)
                        )
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                Spacer(Modifier.weight(1f))
                if (canRequest) {
                    TextButton(
                        onClick = onGrant,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.erteilen),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = stringResource(R.string.open_information),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun PermissionInfoScreen(onboarding: Boolean = false, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permRefreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permRefreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var pendingPrvtPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permRefreshKey++
        if (pendingPrvtPermissions) {
            pendingPrvtPermissions = false
            checkPermissionsForPrvt(context)
        }
    }
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        permRefreshKey++
    }
    var selectedPermission by remember { mutableStateOf<String?>(null) }
    var titleDialog by remember { mutableStateOf("") }
    var usagesDialog by remember { mutableStateOf<List<String>>(emptyList()) }

    val readMediaAudio1 = stringResource(R.string.mediaplayerservice_tab_songs_podcasts_abspielen)
    val readMediaAudio2 = stringResource(R.string.spotifydownloader_speichern_von_audiodateien_im)
    val postNotifications1 =
        stringResource(R.string.quiethoursnotificationservice_anzeigen_von_quiet_hours)
    val postNotifications2 = stringResource(R.string.mediaplayerservice_wiedergabenotifikationen)
    val postNotifications3 = stringResource(R.string.status_updates_fur_laufende_downloads)
    val postNotifications4 = stringResource(R.string.chargingtrackerservice_ladeinformationen)
    val postNotifications5 = stringResource(R.string.networkinfo_netzwerkinfos)
    val postNotifications6 = stringResource(R.string.batteryinfo_batterieinformationen)
    val location1 = stringResource(R.string.standortbasierte_wettervorhersage_im_weathertab)
    val location2 =
        stringResource(R.string.exploretab_explorelocationtracker_standortverfolgung_und_geofencing)
    val location3 = stringResource(R.string.shownetwerkinfo_anzeige_der_wlan_ssid)
    val locationBackground = stringResource(R.string.permission_location_background)
    val activityRecognition1 = stringResource(R.string.exploretab_activityrecognition_erkennung_des_verkehrsmittels)
    val systemAlertWindow1 =
        stringResource(R.string.quiethoursnotificationservice_test_overlay_fur_youtube)
    val foregroundService1 =
        stringResource(R.string.mediaplayerservice_medienwiedergabe_im_vordergrund)
    val foregroundService2 =
        stringResource(R.string.quiethoursnotificationservice_dauerhafte_benachrichtigungen)
    val foregroundService3 = stringResource(R.string.chargingtrackerservice_ladeuberwachung)
    val foregroundService4 = stringResource(R.string.audioforegroundservice_audioaufnahmen)
    val foregroundServiceLocation1 = stringResource(R.string.permission_foreground_service_location)
    val foregroundServiceSpecialUse1 =
        stringResource(R.string.permission_foreground_service_special_use)
    val foregroundServiceMediaPlayback1 =
        stringResource(R.string.permission_foreground_service_media_playback)
    val foregroundServiceMicrophone1 =
        stringResource(R.string.permission_foreground_service_microphone)
    val foregroundServiceMediaProjection1 =
        stringResource(R.string.permission_foreground_service_media_projection)
    val foregroundServiceDataSync1 =
        stringResource(R.string.permission_foreground_service_data_sync)
    val readMediaImagesVideo1 = stringResource(R.string.gallerytab_anzeige_von_bildern_und)
    val readMediaVisualUserSelected1 =
        stringResource(R.string.permission_read_media_visual_user_selected)
    val contacts1 = stringResource(R.string.contactstab_laden_speichern_und_loschen)
    val bootCompleted1 =
        stringResource(R.string.bootreceiver_starten_von_quiethoursnotificationservice_beim)
    val recordAudio1 =
        stringResource(R.string.audiorecordertab_audioforegroundservice_audioaufnahmen_mit_mikrofon)
    val ignoreBattery1 =
        stringResource(R.string.quiethoursnotificationservice_anfrage_zum_ignorieren_von)
    val exactAlarm1 =
        stringResource(R.string.exactalarms_planung_exakter_und_inexakter_alarme)
    val camera1 = stringResource(R.string.authenticator_scannen_von_qr_codes)
    val camera2 = stringResource(R.string.fitnesstab_zahlen_von_push_ups)
    val rideLocation = stringResource(R.string.permission_ride_tracking_location)
    val rideFgs = stringResource(R.string.permission_ride_tracking_fgs)
    val rideActivity = stringResource(R.string.permission_ride_tracking_activity)
    val internet1 = stringResource(R.string.permission_internet)
    val requestInstallPackages1 = stringResource(R.string.permission_request_install_packages)
    val changeWifiMulticast1 = stringResource(R.string.permission_change_wifi_multicast)
    val bluetoothConnect1 =
        stringResource(R.string.reportdeviceinformation_bluetooth_verbindungsstatus)
    val phoneState1 =
        stringResource(R.string.reportdeviceinformation_ismobiledataactive_mobilfunk_datenstatus)
    val readSms1 = stringResource(R.string.startsmslistener_abruf_des_sms_posteingangs)
    val setAlarm1 = stringResource(R.string.wifi_direct_set_alarm_befehl_vom_notebook)
    val wifiState1 = stringResource(R.string.reportdeviceinformation_wlan_status_hotspot)
    val networkState1 = stringResource(R.string.reportdeviceinformation_netzwerkstatus_konnektivitat)
    val localNetwork1 = stringResource(R.string.wifi_direct_lan_verbindung_zum_pc)
    val wakeLock1 = stringResource(R.string.permission_wake_lock)
    val c2dmReceive1 = stringResource(R.string.permission_c2dm_receive)
    val useBiometric1 = stringResource(R.string.permission_use_biometric)
    val useFingerprint1 = stringResource(R.string.permission_use_fingerprint)
    val dynamicReceiver1 = stringResource(R.string.permission_dynamic_receiver)
    val notificationPolicy1 = stringResource(R.string.permission_access_notification_policy)
    val manageExternalStorage1 = stringResource(R.string.permission_manage_external_storage)
    val accessSuperuser1 = stringResource(R.string.permission_access_superuser)
    val healthReadSleep1 = stringResource(R.string.permission_health_read_sleep)
    val healthReadBackground1 = stringResource(R.string.permission_health_read_background)
    val notificationListener1 =
        stringResource(R.string.permission_notification_listener_description)
    val focusguardUsageDesc = stringResource(R.string.focusguard_perm_usage_desc)
    val focusguardOverlayDesc = stringResource(R.string.focusguard_perm_overlay_desc)
    val focusguardNotifDesc = stringResource(R.string.focusguard_perm_notifications_desc)

    val permissionUsages = remember(
        readMediaAudio1,
        readMediaAudio2,
        postNotifications1,
        postNotifications2,
        postNotifications3,
        postNotifications4,
        postNotifications5,
        postNotifications6,
        location1,
        location2,
        location3,
        locationBackground,
        activityRecognition1,
        systemAlertWindow1,
        foregroundService1,
        foregroundService2,
        foregroundService3,
        foregroundService4,
        foregroundServiceLocation1,
        foregroundServiceSpecialUse1,
        foregroundServiceMediaPlayback1,
        foregroundServiceMicrophone1,
        foregroundServiceMediaProjection1,
        foregroundServiceDataSync1,
        readMediaImagesVideo1,
        readMediaVisualUserSelected1,
        contacts1,
        bootCompleted1,
        recordAudio1,
        ignoreBattery1,
        exactAlarm1,
        camera1,
        camera2,
        internet1,
        requestInstallPackages1,
        changeWifiMulticast1,
        bluetoothConnect1,
        phoneState1,
        readSms1,
        setAlarm1,
        wifiState1,
        networkState1,
        localNetwork1,
        wakeLock1,
        c2dmReceive1,
        useBiometric1,
        useFingerprint1,
        dynamicReceiver1,
        notificationPolicy1,
        manageExternalStorage1,
        accessSuperuser1,
        healthReadSleep1,
        healthReadBackground1,
        notificationListener1,
        focusguardUsageDesc,
        focusguardOverlayDesc,
        focusguardNotifDesc,
        rideLocation,
        rideFgs,
        rideActivity
    ) {
        mapOf(
            Manifest.permission.READ_MEDIA_AUDIO to listOf(
                readMediaAudio1,
                readMediaAudio2
            ),
            Manifest.permission.POST_NOTIFICATIONS to listOf(
                postNotifications1,
                postNotifications2,
                postNotifications3,
                postNotifications4,
                postNotifications5,
                postNotifications6,
                focusguardNotifDesc,
                rideFgs
            ),
            Manifest.permission.ACCESS_COARSE_LOCATION to listOf(
                location1,
                location2,
                location3,
                rideLocation
            ),
            Manifest.permission.ACCESS_FINE_LOCATION to listOf(
                location1,
                location2,
                location3,
                rideLocation
            ),
            Manifest.permission.ACCESS_BACKGROUND_LOCATION to listOf(
                location2,
                locationBackground,
                rideLocation
            ),
            Manifest.permission.ACTIVITY_RECOGNITION to listOf(
                activityRecognition1,
                rideActivity
            ),
            Manifest.permission.SYSTEM_ALERT_WINDOW to listOf(
                systemAlertWindow1,
                focusguardOverlayDesc
            ),
            Manifest.permission.PACKAGE_USAGE_STATS to listOf(
                focusguardUsageDesc
            ),
            Manifest.permission.FOREGROUND_SERVICE to listOf(
                foregroundService1,
                foregroundService2,
                foregroundService3,
                foregroundService4
            ),
            Manifest.permission.FOREGROUND_SERVICE_LOCATION to listOf(
                foregroundServiceLocation1,
                rideFgs
            ),
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE to listOf(
                foregroundServiceSpecialUse1
            ),
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK to listOf(
                foregroundServiceMediaPlayback1
            ),
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE to listOf(
                foregroundServiceMicrophone1
            ),
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION to listOf(
                foregroundServiceMediaProjection1
            ),
            Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC to listOf(
                foregroundServiceDataSync1
            ),
            Manifest.permission.READ_MEDIA_IMAGES to listOf(
                readMediaImagesVideo1
            ),
            Manifest.permission.READ_MEDIA_VIDEO to listOf(
                readMediaImagesVideo1
            ),
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to listOf(
                readMediaVisualUserSelected1
            ),
            Manifest.permission.READ_CONTACTS to listOf(
                contacts1
            ),
            Manifest.permission.RECEIVE_BOOT_COMPLETED to listOf(
                bootCompleted1
            ),
            Manifest.permission.RECORD_AUDIO to listOf(
                recordAudio1
            ),
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to listOf(
                ignoreBattery1
            ),
            Manifest.permission.SCHEDULE_EXACT_ALARM to listOf(
                exactAlarm1
            ),
            Manifest.permission.CAMERA to listOf(
                camera1,
                camera2
            ),
            Manifest.permission.INTERNET to listOf(
                internet1
            ),
            Manifest.permission.REQUEST_INSTALL_PACKAGES to listOf(
                requestInstallPackages1
            ),
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE to listOf(
                changeWifiMulticast1
            ),
            Manifest.permission.BLUETOOTH_CONNECT to listOf(
                bluetoothConnect1
            ),
            Manifest.permission.READ_PHONE_STATE to listOf(
                phoneState1
            ),
            Manifest.permission.READ_BASIC_PHONE_STATE to listOf(
                phoneState1
            ),
            Manifest.permission.READ_SMS to listOf(
                readSms1
            ),
            "com.android.alarm.permission.SET_ALARM" to listOf(
                setAlarm1
            ),
            Manifest.permission.ACCESS_WIFI_STATE to listOf(
                wifiState1
            ),
            Manifest.permission.ACCESS_NETWORK_STATE to listOf(
                networkState1
            ),
            Manifest.permission.ACCESS_LOCAL_NETWORK to listOf(
                localNetwork1
            ),
            Manifest.permission.WAKE_LOCK to listOf(
                wakeLock1
            ),
            "com.google.android.c2dm.permission.RECEIVE" to listOf(
                c2dmReceive1
            ),
            Manifest.permission.USE_BIOMETRIC to listOf(
                useBiometric1
            ),
            Manifest.permission.USE_FINGERPRINT to listOf(
                useFingerprint1
            ),
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to listOf(
                dynamicReceiver1
            ),
            Manifest.permission.ACCESS_NOTIFICATION_POLICY to listOf(
                notificationPolicy1
            ),
            Manifest.permission.MANAGE_EXTERNAL_STORAGE to listOf(
                manageExternalStorage1
            ),
            "android.permission.ACCESS_SUPERUSER" to listOf(
                accessSuperuser1
            ),
            HEALTH_READ_SLEEP to listOf(
                healthReadSleep1
            ),
            HEALTH_READ_BACKGROUND to listOf(
                healthReadBackground1
            ),
            NOTIFICATION_LISTENER_PERMISSION to listOf(
                notificationListener1
            )
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (onboarding) Modifier.systemGestureExclusion()
                else Modifier.background(BgDeep)
            )
    ) {
        val hazeState = remember { HazeState() }
        var headerHeightDp by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        val usageStatsCache = remember { mutableStateOf<Boolean?>(null) }
        val usageStatsGranted by produceState(initialValue = usageStatsCache.value, permRefreshKey) {
            value = withContext(Dispatchers.IO) { isUsageStatsGranted(context.applicationContext) }
            usageStatsCache.value = value
        }
        val permissionButtonShape = remember { RoundedCornerShape(22.dp) }
        val permissionBorderBrush = remember {
            Brush.linearGradient(colors = listOf(Accent, AccentDim))
        }
        val healthConnectAvailable = remember(context) {
            runCatching {
                HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
            }.getOrDefault(false)
        }
        val healthGrantedPermissions by produceState<Set<String>?>(
            initialValue = null,
            permRefreshKey,
            healthConnectAvailable
        ) {
            value = if (!healthConnectAvailable) {
                emptySet()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        HealthConnectClient.getOrCreate(context)
                            .permissionController
                            .getGrantedPermissions()
                    }.getOrNull()
                }
            }
        }
        val permissionEntries = remember(context) {
            val entries = manifestPermissions(context)
                .filter { featureAllowsPermission(context, it) }
                .map { permission -> permission to permissionAccess(context, permission) }
                .toMutableList()
            if (hasNotificationListenerService(context)) {
                entries += NOTIFICATION_LISTENER_PERMISSION to PermissionAccess.SPECIAL
            }
            entries.sortedBy { it.second.ordinal }
        }
        val fallbackExplanation = stringResource(R.string.permission_explanation_fallback)
        val exactAlarmBatteryExemptNote =
            stringResource(R.string.permission_exact_alarm_battery_exempt)
        val exactAlarmBatteryExempt = remember(permRefreshKey) {
            ExactAlarms.isExemptFromBatteryOptimizations(context)
        }
        val notificationListenerLabel =
            stringResource(R.string.permission_notification_listener_label)
        val accessRuntimeLabel = stringResource(R.string.permission_access_runtime)
        val accessSpecialLabel = stringResource(R.string.permission_access_special)
        val accessHealthLabel = stringResource(R.string.permission_access_health)
        val accessAutomaticLabel = stringResource(R.string.permission_access_automatic)
        val accessUnavailableLabel = stringResource(R.string.permission_access_unavailable)
        val sectionRuntimeLabel = stringResource(R.string.permission_section_runtime)
        val sectionSpecialLabel = stringResource(R.string.permission_section_special)
        val sectionHealthLabel = stringResource(R.string.permission_section_health)
        val sectionAutomaticLabel = stringResource(R.string.permission_section_automatic)
        val sectionUnavailableLabel = stringResource(R.string.permission_section_unavailable)
        fun accessLabel(access: PermissionAccess): String = when (access) {
            PermissionAccess.RUNTIME -> accessRuntimeLabel
            PermissionAccess.SPECIAL -> accessSpecialLabel
            PermissionAccess.HEALTH -> accessHealthLabel
            PermissionAccess.AUTOMATIC -> accessAutomaticLabel
            PermissionAccess.UNAVAILABLE -> accessUnavailableLabel
        }
        fun sectionLabel(access: PermissionAccess): String = when (access) {
            PermissionAccess.RUNTIME -> sectionRuntimeLabel
            PermissionAccess.SPECIAL -> sectionSpecialLabel
            PermissionAccess.HEALTH -> sectionHealthLabel
            PermissionAccess.AUTOMATIC -> sectionAutomaticLabel
            PermissionAccess.UNAVAILABLE -> sectionUnavailableLabel
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .verticalScroll(rememberScrollState())
                .then(
                    if (!onboarding) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    else Modifier
                )
                .padding(horizontal = 15.dp)
        ) {
            Spacer(Modifier.height(headerHeightDp))

            val runtimePermissions = permissionEntries
                .filter { it.second == PermissionAccess.RUNTIME }
                .map { it.first }
            Button(
                onClick = {
                    if (runtimePermissions.isNotEmpty()) {
                        pendingPrvtPermissions = true
                        Config.requestAllRuntimePermissions(runtimePermissions, permissionLauncher)
                    } else {
                        pendingPrvtPermissions = false
                        checkPermissionsForPrvt(context)
                    }
                },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = APP_COLOR),
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
            ) {
                Text(stringResource(R.string.alle_berechtigungen_anfragen), fontWeight = FontWeight.Bold)
            }

            PermissionAccess.entries.forEach { access ->
                val entriesForAccess = permissionEntries.filter { it.second == access }
                if (entriesForAccess.isNotEmpty()) {
                    LandingSectionLabel(sectionLabel(access))
                    entriesForAccess.forEach { (permission, _) ->
                        key(permission) {
                            val title = if (permission == NOTIFICATION_LISTENER_PERMISSION) {
                                notificationListenerLabel
                            } else {
                                permissionDisplayName(permission)
                            }
                            val baseExplanations = permissionUsages[permission]
                                ?: listOf(fallbackExplanation.format(title))
                            val explanations =
                                if (permission == Manifest.permission.SCHEDULE_EXACT_ALARM &&
                                    exactAlarmBatteryExempt
                                ) {
                                    listOf(exactAlarmBatteryExemptNote) + baseExplanations
                                } else {
                                    baseExplanations
                                }
                            PermissionButton(
                                permission = permission,
                                title = title,
                                access = access,
                                accessLabel = accessLabel(access),
                                explanation = explanations.firstOrNull().orEmpty(),
                                isSelected = selectedPermission == permission,
                                permRefreshKey = permRefreshKey,
                                usageStatsGranted = usageStatsGranted,
                                healthGrantedPermissions = healthGrantedPermissions,
                                healthConnectAvailable = healthConnectAvailable,
                                buttonShape = permissionButtonShape,
                                borderBrush = permissionBorderBrush,
                                onShowDetails = {
                                    selectedPermission = permission
                                    titleDialog = title
                                    usagesDialog = explanations
                                },
                                onGrant = {
                                    if (access == PermissionAccess.HEALTH) {
                                        healthPermissionLauncher.launch(setOf(permission))
                                    } else {
                                        Config.requestPermissionForKey(
                                            context,
                                            permission,
                                            permissionLauncher
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    val newHeight = with(density) { it.size.height.toDp() }
                    if (newHeight != headerHeightDp) headerHeightDp = newHeight
                }
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .hazeBlur(
                        input = HazeInput.Sources(state = hazeState),
                        style = HazeBlurStyle {
                            backgroundColor(BgDeep)
                            colorEffects(listOf(HazeColorEffect.tint(BgDeep.copy(alpha = 0.7f))))
                            blurRadius(60.dp)
                            noiseFactor(0f)
                            progressive(
                                HazeProgressive.verticalGradient(
                                    startIntensity = 1f,
                                    endIntensity = 0f
                                )
                            )
                        }
                    )
                    .drawWithContent {
                        drawContent()
                        val fadePx = 24.dp.toPx()
                        val bottomStop = (1f - fadePx / size.height).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                bottomStop to Color.Black,
                                1f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )

            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 15.dp)
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.tabslify_berechtigungen),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 5.dp),
                        fontSize = 22.sp
                    )
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.background(
                                Color.White.copy(alpha = 0.1f),
                                CircleShape
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                val introTabslifyIst = stringResource(R.string.tabslify_ist)
                val introNichtNurApp = stringResource(R.string.nicht_nur_irgendeine_app)
                val introSieIstEin = stringResource(R.string.sie_ist_ein)
                val introMixAusVielen = stringResource(R.string.mix_aus_ganz_vielen)
                val introAppsUnterAnderem = stringResource(R.string.apps_unter_anderem_fur_die)
                val introVieleBerechtigungen = stringResource(R.string.jeweils_viele_berechtigungen)
                val introBenutzererlebnis = stringResource(R.string.um_das_benutzererlebnis)
                val introUnkompliziert = stringResource(R.string.moglichst_unkompliziert)
                val introZuHalten = stringResource(R.string.zu_halten)
                Text(
                    text = buildAnnotatedString {
                        append(introTabslifyIst)
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Accent,
                                        AccentDim
                                    )
                                )
                            )
                        ) {
                            append(introNichtNurApp)
                        }
                        append(introSieIstEin)
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Accent,
                                        AccentDim
                                    )
                                )
                            )
                        ) {
                            append(introMixAusVielen)
                        }
                        append(introAppsUnterAnderem)
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Accent,
                                        AccentDim
                                    )
                                )
                            )
                        ) {
                            append(introVieleBerechtigungen)
                        }
                        append(introBenutzererlebnis)
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Accent,
                                        AccentDim
                                    )
                                )
                            )
                        ) {
                            append(introUnkompliziert)
                        }
                        append(introZuHalten)
                    },
                    color = APP_COLOR.copy(
                        red = APP_COLOR.red + .6f,
                        green = APP_COLOR.green + .6f,
                        blue = APP_COLOR.blue + .6f
                    ),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }

        AnimatedVisibility(
            visible = selectedPermission != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            DialogTabslify(
                onConfirm = { selectedPermission = null },
                onDismiss = { selectedPermission = null },
                title = titleDialog,
                text = usagesDialog.joinToString("\n\n") { "•  $it" },
                confirmText = stringResource(R.string.schliesen),
                oneButton = true,
                modifier = Modifier.fillMaxWidth(0.9f)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1f -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1f -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1f -> String.format(Locale.US, "%.2f KB", kb)
        else -> "$bytes B"
    }
}

@Composable
fun LiveTrafficPanel(modifier: Modifier = Modifier) {
    var rxRate by remember { mutableFloatStateOf(0f) }
    var txRate by remember { mutableFloatStateOf(0f) }
    var rxTotal by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }
    var txTotal by remember { mutableLongStateOf(TrafficStats.getTotalTxBytes()) }

    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000.milliseconds)
            val curRx = TrafficStats.getTotalRxBytes()
            val curTx = TrafficStats.getTotalTxBytes()
            rxRate = (curRx - lastRx).coerceAtLeast(0).toFloat()
            txRate = (curTx - lastTx).coerceAtLeast(0).toFloat()
            rxTotal = curRx
            txTotal = curTx
            lastRx = curRx
            lastTx = curTx
        }
    }

    NeonBox(
        modifier = modifier.fillMaxWidth(),
        neonColors = listOf(Accent, AccentDim),
        backgroundAlpha = 0.15f
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "📶 Live Traffic (Test)",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Starte einen Download - unten sollte Rx/s hochschießen.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("⬇️ Rx", color = SuccessGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${formatBytes(rxRate.toLong())}/s", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("total ${formatBytes(rxTotal)}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("⬆️ Tx", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${formatBytes(txRate.toLong())}/s", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("total ${formatBytes(txTotal)}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsFrame(
    visible: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var directedToSettings by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showPermissionInfo by remember { mutableStateOf(false) }
    var animatePermissionInfo by remember { mutableStateOf(false) }
    var showCoordinatesEdit by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var backupLastMs by remember { mutableLongStateOf(prefs.getLong("last_prefs_backup_ms", 0L)) }
    var backupBusy by remember { mutableStateOf(false) }
    var showRestoreList by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var restoreTarget by remember { mutableStateOf<BackupEntry?>(null) }

    val backupDoneMsg = stringResource(R.string.cloud_backup_done)
    val backupNoMasterMsg = stringResource(R.string.cloud_backup_no_master)
    val backupShrinkMsg = stringResource(R.string.cloud_backup_shrink_warn)
    val backupWrongPwMsg = stringResource(R.string.cloud_backup_wrong_pw)
    val backupFailedMsg = stringResource(R.string.cloud_backup_failed)
    val backupRestoredMsg = stringResource(R.string.cloud_backup_restored)

    fun outcomeMessage(outcome: BackupOutcome): String = when (outcome) {
        BackupOutcome.DONE -> backupDoneMsg
        BackupOutcome.NO_MASTER -> backupNoMasterMsg
        BackupOutcome.SHRINK_BLOCKED -> backupShrinkMsg
        BackupOutcome.WRONG_PASSWORD -> backupWrongPwMsg
        BackupOutcome.CORRUPT -> backupWrongPwMsg
        BackupOutcome.EMPTY -> backupFailedMsg
        BackupOutcome.FAILED -> backupFailedMsg
    }

    BackHandler {
        if (showCoordinatesEdit) {
            showCoordinatesEdit = false
        } else if (showPermissionInfo) {
            showPermissionInfo = false
        } else {
            onClose()
        }
    }

    var servicesExpanded by remember { mutableStateOf(false) }
    var serviceStates by remember { mutableStateOf(readServiceToggleStates(context)) }
    var featureStates by remember { mutableStateOf(readFeatureToggleStates(context)) }
    val masterEnabled = serviceStates[ServiceToggle.MASTER] == true

    var aiPrefGlobal by remember {
        mutableStateOf(
            prefs.getString("ai_pref_global", "gemini") ?: "gemini"
        )
    }
    var aiPrefChat by remember {
        mutableStateOf(
            prefs.getString("ai_pref_service_chat", "default") ?: "default"
        )
    }
    var aiPrefMusicSummary by remember {
        mutableStateOf(
            prefs.getString(
                "ai_pref_service_music_summary",
                "default"
            ) ?: "default"
        )
    }
    var aiPrefVision by remember {
        mutableStateOf(
            prefs.getString(
                "ai_pref_service_vision",
                "default"
            ) ?: "default"
        )
    }
    var aiPrefReplies by remember {
        mutableStateOf(
            prefs.getString(
                "ai_pref_service_replies",
                "default"
            ) ?: "default"
        )
    }
    var aiSettingsExpanded by remember { mutableStateOf(false) }

    val apiKeyDefs = remember {
        listOf(
            "NVIDIA API-Key" to "api_key_nvidia",
            "OpenRouter API-Key" to "api_key_openrouter",
            "TMDB API-Key" to "api_key_tmdb",
            "WeatherAPI-Key" to "api_key_weatherapi",
            "RapidAPI-Key (Spotify)" to "api_key_rapidapi",
            "PodcastIndex-Key" to "api_key_podcastindex",
            "PodcastIndex-Secret" to "api_key_podcastindex_secret",
            "DB Client-ID (Bahn)" to "api_key_db_client_id",
            "DB API-Key (Bahn)" to "api_key_db_api_key"
        )
    }
    val apiKeyValues = remember {
        mutableStateMapOf<String, String>().apply {
            apiKeyDefs.forEach { (_, key) -> put(key, prefs.getString(key, "") ?: "") }
        }
    }
    var apiKeysExpanded by remember { mutableStateOf(false) }

    var hasNotificationPermission by remember { mutableStateOf(true) }
    var intendedToEnableQhns by remember { mutableStateOf(false) }

    var showMediaAnalyticsDialog by remember { mutableStateOf(false) }
    var mediaAnalyticsEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        MediaAnalyticsManager.init(context.applicationContext)
        mediaAnalyticsEnabled = MediaAnalyticsManager.isAnalyticsEnabled()
    }

    fun persistServices(notificationAllowed: Boolean) {
        writeServiceToggleStates(context, serviceStates)
        applyServiceToggleStates(context, serviceStates, notificationAllowed)
    }

    fun persistFeatures() {
        writeFeatureToggleStates(context, featureStates)
        applyFeatureToggleStates(context, featureStates)
    }

    fun save() {
        prefs.edit {
            putString("ai_pref_global", aiPrefGlobal)
                .putString("ai_pref_service_chat", aiPrefChat)
                .putString("ai_pref_service_music_summary", aiPrefMusicSummary)
                .putString("ai_pref_service_vision", aiPrefVision)
                .putString("ai_pref_service_replies", aiPrefReplies)
        }

        persistServices(hasNotificationPermission)
    }

    fun checkPermissionsAndSyncServices() {
        val permitted = canNotify(context)
        hasNotificationPermission = permitted

        var states = readServiceToggleStates(context)
        if (permitted && intendedToEnableQhns) {
            states = states + (ServiceToggle.QHNS to true)
        }
        intendedToEnableQhns = false
        serviceStates = states
        featureStates = readFeatureToggleStates(context)
        mediaAnalyticsEnabled = states[ServiceToggle.MEDIA_ANALYTICS] == true

        persistServices(permitted)
        persistFeatures()
    }

    val manualPerm = {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }

            context.startActivity(intent)
            directedToSettings = true
            intendedToEnableQhns = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        var isFirstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppLog.d("LandingPage", "Lifecycle resume")
                if (isFirstResume) {
                    AppLog.d("LandingPage", "First resume")
                    isFirstResume = false
                    return@LifecycleEventObserver
                }
                checkPermissionsAndSyncServices()
                directedToSettings = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(visible) {
        if (visible) {
            checkPermissionsAndSyncServices()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.einstellungen),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                NeonBox(
                    modifier = Modifier.fillMaxWidth(),
                    neonColors = listOf(Accent, AccentDim),
                    backgroundAlpha = 0.15f
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLanguageDialog = true }
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_language),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.language_title),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }

                            Box {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = stringResource(R.string.koordinaten_andern),
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (Config.masterPassword.isBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    NeonBox(
                        modifier = Modifier.fillMaxWidth(),
                        neonColors = listOf(Color(0xFF00FFAA), Color(0xFFFFB300)),
                        backgroundAlpha = 0.15f
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.cloud_backup_title),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when {
                                    Config.masterPassword.isBlank() ->
                                        stringResource(R.string.cloud_backup_no_master)
                                    backupLastMs > 0L ->
                                        stringResource(R.string.cloud_backup_last) + " " +
                                                android.text.format.DateUtils
                                                    .getRelativeTimeSpanString(backupLastMs)
                                    else -> stringResource(R.string.cloud_backup_never)
                                },
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (backupBusy) return@Button
                                        backupBusy = true
                                        scope.launch {
                                            val outcome =
                                                PrefsBackup.backupNow(context, force = true)
                                            if (outcome == BackupOutcome.DONE) {
                                                backupLastMs =
                                                    prefs.getLong("last_prefs_backup_ms", 0L)
                                            }
                                            toast(context, outcomeMessage(outcome))
                                            backupBusy = false
                                        }
                                    },
                                    enabled = !backupBusy && Config.masterPassword.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.cloud_backup_now))
                                }
                                Button(
                                    onClick = {
                                        if (backupBusy) return@Button
                                        backupBusy = true
                                        scope.launch {
                                            backupList = PrefsBackup.listBackups()
                                            backupBusy = false
                                            showRestoreList = true
                                        }
                                    },
                                    enabled = !backupBusy && Config.masterPassword.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.cloud_backup_restore))
                                }
                            }
                        }
                    }
                }

                if (showRestoreList) {
                    AlertDialog(
                        onDismissRequest = { showRestoreList = false },
                        title = { Text(stringResource(R.string.cloud_backup_restore)) },
                        text = {
                            if (backupList.isEmpty()) {
                                Text(stringResource(R.string.cloud_backup_never))
                            } else {
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                ) {
                                    val fmt = java.text.SimpleDateFormat(
                                        "dd.MM.yyyy HH:mm",
                                        LocalLocale.current.platformLocale
                                    )
                                    backupList.forEach { entry ->
                                        Text(
                                            text = fmt.format(java.util.Date(entry.createdAt)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    restoreTarget = entry
                                                    showRestoreList = false
                                                }
                                                .padding(vertical = 12.dp)
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showRestoreList = false }) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    )
                }

                restoreTarget?.let { entry ->
                    AlertDialog(
                        onDismissRequest = { restoreTarget = null },
                        title = { Text(stringResource(R.string.cloud_backup_restore)) },
                        text = { Text(stringResource(R.string.cloud_backup_restore_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                restoreTarget = null
                                if (backupBusy) return@TextButton
                                backupBusy = true
                                scope.launch {
                                    val outcome = PrefsBackup.restore(context, entry.fileName)
                                    if (outcome == BackupOutcome.DONE) {
                                        backupLastMs =
                                            prefs.getLong("last_prefs_backup_ms", 0L)
                                    }
                                    toast(
                                        context,
                                        if (outcome == BackupOutcome.DONE)
                                            backupRestoredMsg
                                        else outcomeMessage(outcome)
                                    )
                                    backupBusy = false
                                }
                            }) {
                                Text(stringResource(R.string.cloud_backup_restore))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { restoreTarget = null }) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                NeonBox(
                    modifier = Modifier.fillMaxWidth(),
                    neonColors = listOf(Accent, AccentDim),
                    backgroundAlpha = 0.15f
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.hintergrunddienste),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.alle_hintergrundaktivitaten),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = masterEnabled,
                                onCheckedChange = { v ->
                                    serviceStates =
                                        serviceStates + (ServiceToggle.MASTER to v)
                                    save()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color.DarkGray,
                                    disabledUncheckedTrackColor = Color.DarkGray.copy(alpha = 0.4f)
                                )
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { servicesExpanded = !servicesExpanded }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.einzelne_dienste),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (servicesExpanded) "▲" else "▼",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }

                        AnimatedVisibility(visible = servicesExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                val services = ServiceToggle.entries.filter {
                                    it != ServiceToggle.MASTER &&
                                        it != ServiceToggle.MEDIA_ANALYTICS &&
                                        it.isAvailable()
                                }

                                services.forEachIndexed { index, toggle ->
                                    val checked = serviceStates[toggle] == true
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(toggle.titleRes),
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                stringResource(toggle.descriptionRes),
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 11.sp
                                            )
                                            if (toggle == ServiceToggle.QHNS &&
                                                !hasNotificationPermission
                                            ) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = stringResource(R.string.keine_benachrichtigungsberechtigung),
                                                    color = ErrorRed,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = if (toggle.gatedByMaster)
                                                checked && masterEnabled else checked,
                                            onCheckedChange = { v ->
                                                if (toggle == ServiceToggle.QHNS &&
                                                    !hasNotificationPermission
                                                ) {
                                                    manualPerm()
                                                } else {
                                                    serviceStates =
                                                        serviceStates + (toggle to v)
                                                    save()
                                                }
                                            },
                                            enabled = if (toggle.gatedByMaster)
                                                masterEnabled else true,
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                uncheckedThumbColor = Color.Gray,
                                                uncheckedTrackColor = Color.DarkGray,
                                                disabledCheckedTrackColor = Color.DarkGray,
                                                disabledCheckedThumbColor = Color.Gray
                                            )
                                        )
                                    }
                                    if (index < services.lastIndex) {
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                NeonBox(
                    modifier = Modifier.fillMaxWidth(),
                    neonColors = listOf(Color(0xFF00FF00), Color(0xFF00CC00)),
                    backgroundAlpha = 0.15f
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.media_analytics),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.tracke_horgewohnheiten_fur_statistiken),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = mediaAnalyticsEnabled,
                                onCheckedChange = { newValue ->
                                    if (newValue) {
                                        // Just enable it immediately
                                        mediaAnalyticsEnabled = true
                                        MediaAnalyticsManager.setAnalyticsEnabled(true)
                                        serviceStates = serviceStates +
                                                (ServiceToggle.MEDIA_ANALYTICS to true)
                                    } else {
                                        // Show confirmation dialog
                                        showMediaAnalyticsDialog = true
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color.DarkGray,
                                    disabledUncheckedTrackColor = Color.DarkGray.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LiveTrafficPanel()

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .drawBehind {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawRect(
                                        0f, 0f, size.width, size.height,
                                        Paint().apply {
                                            color = Color(0xFFBEBEBE).copy(alpha = 0.3f).toArgb()
                                            isAntiAlias = true
                                            maskFilter = android.graphics.BlurMaskFilter(
                                                25f,
                                                android.graphics.BlurMaskFilter.Blur.OUTER
                                            )
                                        }
                                    )
                                }
                            }
                            .clip(RoundedCornerShape(5.dp))
                            .height(5.dp)
                            .background(Color(0xFFBEBEBE))
                    )
                    Text(
                        stringResource(R.string.transparenz),
                        modifier = Modifier.weight(2f),
                        color = Color(0xFFBEBEBE),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .drawBehind {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawRect(
                                        0f, 0f, size.width, size.height,
                                        Paint().apply {
                                            color = Color(0xFFBEBEBE).copy(alpha = 0.3f).toArgb()
                                            isAntiAlias = true
                                            maskFilter = android.graphics.BlurMaskFilter(
                                                25f,
                                                android.graphics.BlurMaskFilter.Blur.OUTER
                                            )
                                        }
                                    )
                                }
                            }
                            .clip(RoundedCornerShape(5.dp))
                            .height(5.dp)
                            .background(Color(0xFFBEBEBE))
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                NeonBox(
                    modifier = Modifier.fillMaxWidth(),
                    neonColors = listOf(Color(0xFFE8B92A), Color(0xFFFF0000)),
                    backgroundAlpha = 0.15f
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPermissionInfo = true }
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.warum_fordere_ich_so_viele),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Box {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = stringResource(R.string.open_information),
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (prvt()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    NeonBox(
                        modifier = Modifier.fillMaxWidth(),
                        neonColors = listOf(Accent, AccentDim),
                        backgroundAlpha = 0.15f
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCoordinatesEdit = true }
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.zuhause_koordinaten_andern),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Box {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = stringResource(R.string.koordinaten_andern),
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (prvt()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .drawBehind {
                                    drawIntoCanvas { canvas ->
                                        canvas.nativeCanvas.drawRect(
                                            0f, 0f, size.width, size.height,
                                            Paint().apply {
                                                color =
                                                    Color(0xFFBEBEBE).copy(alpha = 0.3f).toArgb()
                                                isAntiAlias = true
                                                maskFilter = android.graphics.BlurMaskFilter(
                                                    25f,
                                                    android.graphics.BlurMaskFilter.Blur.OUTER
                                                )
                                            }
                                        )
                                    }
                                }
                                .clip(RoundedCornerShape(5.dp))
                                .height(5.dp)
                                .background(Color(0xFFBEBEBE))
                        )
                        Text(
                            stringResource(R.string.erweitert),
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFBEBEBE),
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .drawBehind {
                                    drawIntoCanvas { canvas ->
                                        canvas.nativeCanvas.drawRect(
                                            0f, 0f, size.width, size.height,
                                            Paint().apply {
                                                color =
                                                    Color(0xFFBEBEBE).copy(alpha = 0.3f).toArgb()
                                                isAntiAlias = true
                                                maskFilter = android.graphics.BlurMaskFilter(
                                                    25f,
                                                    android.graphics.BlurMaskFilter.Blur.OUTER
                                                )
                                            }
                                        )
                                    }
                                }
                                .clip(RoundedCornerShape(5.dp))
                                .height(5.dp)
                                .background(Color(0xFFBEBEBE))
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    NeonBox(
                        modifier = Modifier.fillMaxWidth(),
                        neonColors = listOf(Accent, AccentDim),
                        backgroundAlpha = 0.15f
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.bevorzugte_ai_global),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.standard_anbieter_fur_alle_dienste),
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp
                                    )
                                }

                                var showGlobalDropdown by remember { mutableStateOf(false) }
                                Box {
                                    Text(
                                        text = aiPrefGlobal.uppercase(),
                                        color = Accent,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { showGlobalDropdown = true }
                                            .border(
                                                1.dp,
                                                Color.White.copy(alpha = 0.2f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    DropdownMenu(
                                        expanded = showGlobalDropdown,
                                        onDismissRequest = { showGlobalDropdown = false },
                                        containerColor = Color.Black
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Gemini", color = Color.White) },
                                            onClick = {
                                                aiPrefGlobal = "gemini"; showGlobalDropdown =
                                                false; save()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("NVIDIA", color = Color.White) },
                                            onClick = {
                                                aiPrefGlobal = "nvidia"; showGlobalDropdown =
                                                false; save()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("OpenRouter", color = Color.White) },
                                            onClick = {
                                                aiPrefGlobal = "openrouter"; showGlobalDropdown =
                                                false; save()
                                            }
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { aiSettingsExpanded = !aiSettingsExpanded }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.einzelne_dienste_anpassen),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (aiSettingsExpanded) "▲" else "▼",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }

                            AnimatedVisibility(visible = aiSettingsExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                ) {
                                    val aiServices = listOf(
                                        Triple(
                                            stringResource(R.string.befehl_ai_chat),
                                            aiPrefChat
                                        ) { v: String -> aiPrefChat = v },
                                        Triple(
                                            stringResource(R.string.musik_zusammenfassung),
                                            aiPrefMusicSummary
                                        ) { v: String -> aiPrefMusicSummary = v },
                                        Triple(
                                            stringResource(R.string.vokabel_vision),
                                            aiPrefVision
                                        ) { v: String -> aiPrefVision = v },
                                        Triple(
                                            stringResource(R.string.nachrichten_antworten),
                                            aiPrefReplies
                                        ) { v: String -> aiPrefReplies = v }
                                    )

                                    aiServices.forEachIndexed { index, (title, currentVal, setter) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                title,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                modifier = Modifier.weight(1f)
                                            )

                                            var showServiceDropdown by remember {
                                                mutableStateOf(
                                                    false
                                                )
                                            }
                                            Box {
                                                Text(
                                                    text = when (currentVal) {
                                                        "default" -> stringResource(R.string.standard)
                                                        "gemini" -> "Gemini"
                                                        "nvidia" -> "NVIDIA"
                                                        "openrouter" -> "OpenRouter"
                                                        else -> stringResource(R.string.standard)
                                                    },
                                                    color = Color.White.copy(alpha = 0.8f),
                                                    fontSize = 12.sp,
                                                    modifier = Modifier
                                                        .clickable { showServiceDropdown = true }
                                                        .border(
                                                            1.dp,
                                                            Color.White.copy(alpha = 0.15f),
                                                            RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(
                                                            horizontal = 10.dp,
                                                            vertical = 4.dp
                                                        )
                                                )
                                                DropdownMenu(
                                                    expanded = showServiceDropdown,
                                                    onDismissRequest = {
                                                        showServiceDropdown = false
                                                    },
                                                    containerColor = Color.Black
                                                ) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                stringResource(R.string.standard_global),
                                                                color = Color.White
                                                            )
                                                        },
                                                        onClick = {
                                                            setter("default"); showServiceDropdown =
                                                            false; save()
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                "Gemini",
                                                                color = Color.White
                                                            )
                                                        },
                                                        onClick = {
                                                            setter("gemini"); showServiceDropdown =
                                                            false; save()
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                "NVIDIA",
                                                                color = Color.White
                                                            )
                                                        },
                                                        onClick = {
                                                            setter("nvidia"); showServiceDropdown =
                                                            false; save()
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                "OpenRouter",
                                                                color = Color.White
                                                            )
                                                        },
                                                        onClick = {
                                                            setter("openrouter"); showServiceDropdown =
                                                            false; save()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        if (index < aiServices.lastIndex) {
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    NeonBox(
                        modifier = Modifier.fillMaxWidth(),
                        neonColors = listOf(Accent, AccentDim),
                        backgroundAlpha = 0.15f
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { apiKeysExpanded = !apiKeysExpanded }
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.eigene_api_schlussel),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.leer_lassen_standard_schlussel_des),
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    if (apiKeysExpanded) "▲" else "▼",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }

                            AnimatedVisibility(visible = apiKeysExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                ) {
                                    apiKeyDefs.forEach { (label, key) ->
                                        OutlinedTextField(
                                            value = apiKeyValues[key] ?: "",
                                            onValueChange = { v ->
                                                apiKeyValues[key] = v
                                                prefs.edit { putString(key, v.trim()) }
                                            },
                                            label = { Text(label) },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = BuildConfig.VERSION_NAME,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp
                )
            }
        }

        LaunchedEffect(showPermissionInfo) {
            if (showPermissionInfo) {
                delay(50.milliseconds)
                animatePermissionInfo = true
            } else {
                animatePermissionInfo = false
            }
        }

        AnimatedVisibility(
            visible = showPermissionInfo && animatePermissionInfo,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            PermissionInfoScreen(onClose = { showPermissionInfo = false })
        }

        // Media Analytics Confirmation Dialog
        if (showMediaAnalyticsDialog) {
            AlertDialog(
                onDismissRequest = { showMediaAnalyticsDialog = false },
                title = {
                    Text(
                        stringResource(R.string.media_analytics_deaktivieren),
                        color = Color.White
                    )
                },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.mochtest_du_media_analytics_wirklich),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.option_1_deaktivieren_alle_daten),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            stringResource(R.string.option_2_nur_deaktivieren_daten),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                // Option 1: Disable and clear all data
                                mediaAnalyticsEnabled = false
                                MediaAnalyticsManager.setAnalyticsEnabled(false)
                                MediaAnalyticsManager.clearAllSessions()
                                serviceStates = serviceStates +
                                        (ServiceToggle.MEDIA_ANALYTICS to false)
                                showMediaAnalyticsDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.deaktivieren_daten_loschen))
                        }
                        Button(
                            onClick = {
                                // Option 2: Just disable, keep data
                                mediaAnalyticsEnabled = false
                                MediaAnalyticsManager.setAnalyticsEnabled(false)
                                serviceStates = serviceStates +
                                        (ServiceToggle.MEDIA_ANALYTICS to false)
                                showMediaAnalyticsDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.nur_deaktivieren))
                        }
                    }
                },
                dismissButton = {
                    Button(onClick = { showMediaAnalyticsDialog = false }) {
                        Text(stringResource(R.string.abbrechen))
                    }
                },
                containerColor = BgCard
            )
        }

        AnimatedVisibility(
            visible = showCoordinatesEdit && prvt(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val coordPrefs =
                remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            var latInput by remember {
                mutableStateOf(
                    if (coordPrefs.contains("lat_key_d"))
                        java.lang.Double.longBitsToDouble(coordPrefs.getLong("lat_key_d", 0L))
                            .toString()
                    else
                        coordPrefs.getFloat("lat_key", 0f).toString()
                )
            }
            var lonInput by remember {
                mutableStateOf(
                    if (coordPrefs.contains("lon_key_d"))
                        java.lang.Double.longBitsToDouble(coordPrefs.getLong("lon_key_d", 0L))
                            .toString()
                    else
                        coordPrefs.getFloat("lon_key", 0f).toString()
                )
            }
            var savedHint by remember { mutableStateOf(false) }
            val latDouble = latInput.toDoubleOrNull()
            val lonDouble = lonInput.toDoubleOrNull()
            val isValid = latDouble != null && lonDouble != null

            Box(
                Modifier
                    .fillMaxSize()
                    .background(BgDeep)
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 15.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.koordinaten_andern),
                        style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 5.dp),
                            fontSize = 22.sp
                        )
                        IconButton(
                            onClick = { showCoordinatesEdit = false },
                            modifier = Modifier.background(
                                Color.White.copy(alpha = 0.1f),
                                CircleShape
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.diese_koordinaten_gelten_als_zuhause),
                        color = APP_COLOR.copy(
                            red = APP_COLOR.red + .6f,
                            green = APP_COLOR.green + .6f,
                            blue = APP_COLOR.blue + .6f
                        ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = latInput,
                        onValueChange = { latInput = it; savedHint = false },
                        label = { Text(stringResource(R.string.breitengrad_latitude)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = lonInput,
                        onValueChange = { lonInput = it; savedHint = false },
                        label = { Text(stringResource(R.string.langengrad_longitude)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (latInput.isNotEmpty() && latDouble == null)
                        Text(
                            stringResource(R.string.ungultiger_breitengrad),
                            color = ErrorRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    if (lonInput.isNotEmpty() && lonDouble == null)
                        Text(
                            stringResource(R.string.ungultiger_langengrad),
                            color = ErrorRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            coordPrefs.edit {
                                putLong(
                                    "lat_key_d",
                                    java.lang.Double.doubleToRawLongBits(latDouble!!)
                                )
                                putLong(
                                    "lon_key_d",
                                    java.lang.Double.doubleToRawLongBits(lonDouble!!)
                                )
                                putBoolean("has_coordinates", true)
                            }
                            Config.LAT = latDouble!!
                            Config.LON = lonDouble!!
                            savedHint = true
                        },
                        enabled = isValid,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.speichern)) }

                    if (savedHint) {
                        Text(
                            stringResource(R.string.gespeichert_punkt),
                            color = SuccessGreen,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                Spacer(Modifier.height(32.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showLanguageDialog,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LanguageSelectionDialog { showLanguageDialog = false }
        }
    }
}