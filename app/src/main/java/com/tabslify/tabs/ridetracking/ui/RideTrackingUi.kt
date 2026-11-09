package com.tabslify.tabs.ridetracking.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.tabslify.R
import com.tabslify.core.ui.Accent
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.NeonBox
import com.tabslify.core.ui.SuccessGreen
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.tabs.fitnesstab.ui.BikeMiniStat
import com.tabslify.tabs.fitnesstab.ui.BikeSectionTitle
import com.tabslify.tabs.fitnesstab.ui.bikeModeIcon
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_BALANCED
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_HIGH
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_SAVER
import com.tabslify.tabs.ridetracking.data.RIDE_MODE_BIKE
import com.tabslify.tabs.ridetracking.data.RIDE_MODE_EBIKE
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_FINISHED
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_PAUSED
import com.tabslify.tabs.ridetracking.data.Ride
import com.tabslify.tabs.ridetracking.data.RidePoint
import com.tabslify.tabs.ridetracking.data.RideRepository
import com.tabslify.tabs.ridetracking.data.RideSplit
import com.tabslify.tabs.ridetracking.service.RideRecorder
import com.tabslify.tabs.ridetracking.service.RideStartResult
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_AUTO_PAUSE
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_PAUSE
import com.tabslify.tabs.ridetracking.data.RideEvent
import com.tabslify.tabs.ridetracking.data.RideMetrics
import com.tabslify.tabs.ridetracking.data.RidePhoto
import com.tabslify.tabs.ridetracking.data.RidePhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.UUID

private val RideAccent = SuccessGreen
private val RideAccentSecondary = Color(0xFF4CC9FF)
private val RideEbikeAccent = Accent

@Composable
fun RideTrackingCard(
    onOpenStats: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by RideRecorder.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf(RIDE_MODE_BIKE) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (RideRecorder.hasLocationPermission(context)) {
            val result = RideRecorder.start(context, mode)
            if (result != RideStartResult.STARTED) handleStartError(context, result) { errorText = it }
        } else {
            showPermissionDialog = true
        }
    }

    fun requestStart() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty() && RideRecorder.hasLocationPermission(context)) {
            val result = RideRecorder.start(context, mode)
            if (result != RideStartResult.STARTED) handleStartError(context, result) { errorText = it }
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    if (showStopDialog) {
        AlertDialogTabslify(
            onDismiss = { showStopDialog = false },
            title = stringResource(R.string.ride_tracking_stop_title),
            text = stringResource(R.string.ride_tracking_stop_text),
            confirmText = stringResource(R.string.ride_tracking_stop),
            onConfirm = {
                showStopDialog = false
                RideRecorder.stop(context)
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialogTabslify(
            onDismiss = { showPermissionDialog = false },
            title = stringResource(R.string.ride_tracking_permission_title),
            text = stringResource(R.string.ride_tracking_permission_text),
            confirmText = stringResource(R.string.ride_tracking_permission_settings),
            onConfirm = {
                showPermissionDialog = false
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        )
    }

    errorText?.let { message ->
        AlertDialogTabslify(
            onDismiss = { errorText = null },
            title = stringResource(R.string.ride_tracking_start_failed),
            text = message,
            confirmText = stringResource(R.string.ride_tracking_close),
            oneButton = true,
            onConfirm = { errorText = null }
        )
    }

    val accent = when {
        mode == RIDE_MODE_EBIKE -> RideEbikeAccent
        else -> RideAccent
    }
    val finished = state.status == RIDE_STATUS_FINISHED && state.finishedRideId != null

    NeonBox(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = RoundedCornerShape(20.dp),
        neonColors = listOf(accent, RideAccentSecondary),
        backgroundAlpha = 0.16f,
        borderWidth = 2.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🚴", fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            finished -> stringResource(R.string.ride_tracking_finished)
                            state.status == RIDE_STATUS_PAUSED -> stringResource(R.string.ride_tracking_paused)
                            state.active -> stringResource(R.string.ride_tracking_recording)
                            else -> stringResource(R.string.ride_tracking_start)
                        },
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            state.active || finished -> modeLabel(state.mode.ifBlank { mode })
                            else -> stringResource(R.string.ride_tracking_start_hint)
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                if (state.active) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.2f))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = batteryProfileLabel(state.batteryProfile),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (state.active) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatDistance(state.distanceMeters),
                        color = TextPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatDuration(state.movingMs),
                            color = RideAccentSecondary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.ride_tracking_moving),
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BikeMiniStat(
                        label = stringResource(R.string.ride_tracking_avg_speed),
                        value = formatSpeed(state.avgSpeedMps),
                        accent = RideAccent,
                        modifier = Modifier.weight(1f)
                    )
                    BikeMiniStat(
                        label = stringResource(R.string.ride_tracking_max_speed),
                        value = formatSpeed(state.maxSpeedMps),
                        accent = RideEbikeAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BikeMiniStat(
                        label = stringResource(R.string.ride_tracking_current_speed),
                        value = formatSpeed(state.currentSpeedMps),
                        accent = RideAccentSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    BikeMiniStat(
                        label = stringResource(R.string.ride_tracking_elevation),
                        value = stringResource(
                            R.string.ride_tracking_value_meters,
                            state.elevationGainM
                        ),
                        accent = RideAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                when {
                    state.gpsWeak -> Text(
                        text = stringResource(R.string.ride_tracking_gps_weak),
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    state.autoPaused -> Text(
                        text = stringResource(R.string.ride_tracking_auto_pause),
                        color = RideAccentSecondary,
                        fontSize = 12.sp
                    )

                    state.accuracy != null -> Text(
                        text = stringResource(
                            R.string.ride_tracking_accuracy_value,
                            state.accuracy ?: 0f
                        ),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RideActionButton(
                        text = stringResource(
                            if (state.status == RIDE_STATUS_PAUSED) {
                                R.string.ride_tracking_resume
                            } else {
                                R.string.ride_tracking_pause
                            }
                        ),
                        colors = listOf(RideAccent, RideAccentSecondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.status == RIDE_STATUS_PAUSED) {
                            RideRecorder.resume(context)
                        } else {
                            RideRecorder.pause(context)
                        }
                    }
                    RideActionButton(
                        text = stringResource(R.string.ride_tracking_stop),
                        colors = listOf(Color(0xFFFF6B6B), Color(0xFFFF8A65)),
                        modifier = Modifier.weight(1f)
                    ) { showStopDialog = true }
                }
            } else if (finished) {
                val id = state.finishedRideId
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatDistance(state.distanceMeters),
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDuration(state.movingMs),
                        color = RideAccentSecondary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RideActionButton(
                        text = stringResource(R.string.ride_tracking_view_stats),
                        colors = listOf(RideAccent, RideAccentSecondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (id != null) onOpenStats(id)
                    }
                    RideActionButton(
                        text = stringResource(R.string.ride_tracking_done),
                        colors = listOf(Accent, RideAccentSecondary),
                        modifier = Modifier.weight(1f)
                    ) { RideRecorder.clearFinished() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeChip(
                        label = stringResource(R.string.ride_tracking_mode_bike),
                        icon = "🚲",
                        selected = mode == RIDE_MODE_BIKE,
                        colors = listOf(RideAccent, RideAccentSecondary),
                        modifier = Modifier.weight(1f)
                    ) { mode = RIDE_MODE_BIKE }
                    ModeChip(
                        label = stringResource(R.string.ride_tracking_mode_ebike),
                        icon = "🚴⚡",
                        selected = mode == RIDE_MODE_EBIKE,
                        colors = listOf(RideEbikeAccent, RideAccentSecondary),
                        modifier = Modifier.weight(1f)
                    ) { mode = RIDE_MODE_EBIKE }
                }
                RideActionButton(
                    text = stringResource(R.string.ride_tracking_start),
                    colors = listOf(accent, RideAccentSecondary),
                    modifier = Modifier.fillMaxWidth()
                ) { requestStart() }
                Text(
                    text = stringResource(R.string.ride_tracking_battery_hint),
                    color = TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun RideRecentSection(
    onOpenStats: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember { RideRepository(context) }
    val recentFlow = remember(repo) { repo.recentFinishedFlow(3) }
    val rides by recentFlow.collectAsState(initial = emptyList())
    if (rides.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BikeSectionTitle(stringResource(R.string.ride_tracking_recent))
        rides.forEach { ride ->
            RideRecentCard(ride = ride, onClick = { onOpenStats(ride.id) })
        }
    }
}

@Composable
private fun RideRecentCard(ride: Ride, onClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("EEE, dd. MMM · HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = bikeModeIcon(ride.mode), fontSize = 24.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = formatDistance(ride.distanceMeters),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dateFmt.format(Date(ride.startTime)),
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDuration(ride.movingTimeMs),
                color = RideAccentSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatSpeed(ride.avgSpeedMps),
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun RideStatsScreen(
    rideId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { RideRepository(context) }
    val rideFlow = remember(repo, rideId) { repo.rideFlow(rideId) }
    val pointsFlow = remember(repo, rideId) { repo.pointsFlowForRide(rideId) }
    val splitsFlow = remember(repo, rideId) { repo.splitsFlowForRide(rideId) }
    val photosFlow = remember(repo, rideId) { repo.photosFlowForRide(rideId) }
    val eventsFlow = remember(repo, rideId) { repo.eventsFlowForRide(rideId) }
    val ride by rideFlow.collectAsState(initial = null)
    val points by pointsFlow.collectAsState(initial = emptyList())
    val splits by splitsFlow.collectAsState(initial = emptyList())
    val photos by photosFlow.collectAsState(initial = emptyList())
    val events by eventsFlow.collectAsState(initial = emptyList())

    var viewingPhoto by remember { mutableStateOf<RidePhoto?>(null) }
    var photoToDelete by remember { mutableStateOf<RidePhoto?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun savePhoto(file: File, fallbackTimestamp: Long) {
        scope.launch {
            val saved = persistRidePhoto(context, repo, rideId, file, points, fallbackTimestamp)
            if (!saved) {
                file.delete()
                errorMessage = context.getString(R.string.ride_tracking_photo_failed)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null && file.length() > 0L) {
            savePhoto(file, System.currentTimeMillis())
        } else {
            file?.delete()
        }
    }

    fun startCamera() {
        runCatching {
            val file = RidePhotoStore.newPhotoFile(context, rideId)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            pendingCameraFile = file
            cameraLauncher.launch(uri)
        }.onFailure {
            errorMessage = context.getString(R.string.ride_tracking_photo_failed)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            errorMessage = context.getString(R.string.ride_tracking_camera_denied)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val target = RidePhotoStore.newPhotoFile(context, rideId)
                val copied = RidePhotoStore.importUri(context, uri, target)
                if (copied) {
                    savePhoto(target, System.currentTimeMillis())
                } else {
                    target.delete()
                    errorMessage = context.getString(R.string.ride_tracking_photo_failed)
                }
            }
        }
    }

    fun requestCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun deletePhoto(photo: RidePhoto) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { RidePhotoStore.resolve(context, photo).delete() }
                runCatching { repo.deletePhoto(photo) }
            }
            if (viewingPhoto?.id == photo.id) viewingPhoto = null
        }
    }

    val dateFmt = remember { SimpleDateFormat("EEEE, dd. MMMM yyyy · HH:mm", Locale.getDefault()) }
    val rideValue = ride

    viewingPhoto?.let { photo ->
        RidePhotoViewer(
            photo = photo,
            file = RidePhotoStore.resolve(context, photo),
            onClose = { viewingPhoto = null },
            onDelete = { photoToDelete = photo }
        )
    }

    photoToDelete?.let { photo ->
        AlertDialogTabslify(
            onDismiss = { photoToDelete = null },
            title = stringResource(R.string.ride_tracking_photo_delete_title),
            text = stringResource(R.string.ride_tracking_photo_delete_text),
            confirmText = stringResource(R.string.loschen),
            onConfirm = {
                photoToDelete = null
                deletePhoto(photo)
            }
        )
    }

    errorMessage?.let { message ->
        AlertDialogTabslify(
            onDismiss = { errorMessage = null },
            title = stringResource(R.string.ride_tracking_photo_failed),
            text = message,
            confirmText = stringResource(R.string.ride_tracking_close),
            oneButton = true,
            onConfirm = { errorMessage = null }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.zuruck),
                    tint = TextPrimary
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = rideValue?.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.ride_tracking_stats_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                rideValue?.let {
                    Text(
                        text = dateFmt.format(Date(it.startTime)),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            Text(text = bikeModeIcon(rideValue?.mode ?: RIDE_MODE_BIKE), fontSize = 26.sp)
        }

        if (rideValue == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.ride_tracking_loading),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                RideTrackMap(
                    points = points,
                    photos = photos,
                    onPhotoClick = { viewingPhoto = it }
                )
            }
            item { RideStatsGrid(ride = rideValue) }
            item { RideElevationProfile(points = points, events = events) }
            item {
                BikeSectionTitle(stringResource(R.string.ride_tracking_splits))
                if (splits.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ride_tracking_splits_empty),
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
            items(splits, key = { it.id }) { split ->
                RideSplitRow(split = split)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BikeSectionTitle(stringResource(R.string.ride_tracking_photo_section))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { requestCamera() }) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = RideAccentSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ride_tracking_photo_add_camera),
                            color = RideAccentSecondary,
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = RideAccentSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ride_tracking_photo_add_gallery),
                            color = RideAccentSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                if (photos.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ride_tracking_photo_empty),
                        color = TextTertiary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            items(photos, key = { it.id }) { photo ->
                RidePhotoTimelineRow(
                    photo = photo,
                    file = RidePhotoStore.resolve(context, photo),
                    onClick = { viewingPhoto = photo }
                )
            }
        }
    }
}

private suspend fun persistRidePhoto(
    context: android.content.Context,
    repo: RideRepository,
    rideId: String,
    file: File,
    points: List<RidePoint>,
    fallbackTimestamp: Long
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        if (file.length() <= 0L) return@runCatching false
        val timestamp = RidePhotoStore.captureTimestamp(file, fallbackTimestamp)
        val ridePoints = points.ifEmpty {
            runCatching { repo.pointsForRide(rideId) }.getOrDefault(emptyList())
        }
        val nearest = RidePhotoStore.nearestPoint(ridePoints, timestamp)
        val dimensions = RidePhotoStore.dimensions(file)
        val photo = RidePhoto(
            id = UUID.randomUUID().toString(),
            rideId = rideId,
            timestamp = timestamp,
            lat = nearest?.second?.lat,
            lon = nearest?.second?.lon,
            path = file.name,
            mime = "image/jpeg",
            width = dimensions.first,
            height = dimensions.second,
            sizeBytes = file.length(),
            routePointIndex = nearest?.first ?: -1,
            trackOffsetM = nearest?.second?.distanceFromStartM,
            createdAt = System.currentTimeMillis()
        )
        repo.addPhoto(photo)
        true
    }.getOrDefault(false)
}

@Composable
private fun RideTrackMap(
    points: List<RidePoint>,
    photos: List<RidePhoto>,
    onPhotoClick: (RidePhoto) -> Unit
) {
    if (points.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BgCard),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.ride_tracking_no_points),
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(20.dp)
            )
        }
        return
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            mapView?.tileProvider?.clearTileCache()
            mapView?.onDetach()
            mapView = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                Configuration.getInstance().userAgentValue = context.packageName
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = false
                    setScrollableAreaLimitDouble(null)
                    controller.setZoom(14.0)
                    controller.setCenter(GeoPoint(points.first().lat, points.first().lon))
                }.also { mapView = it }
            },
            update = { view ->
                view.overlays.removeAll(view.overlays.filterIsInstance<Polyline>().toSet())
                view.overlays.removeAll(view.overlays.filterIsInstance<Marker>().toSet())
                val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
                view.overlays.add(
                    Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = RideAccent.toArgb()
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.isAntiAlias = true
                    }
                )
                photos.forEach { photo ->
                    val lat = photo.lat
                    val lon = photo.lon
                    if (lat != null && lon != null) {
                        view.overlays.add(
                            Marker(view).apply {
                                position = GeoPoint(lat, lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = photo.caption ?: RidePhotoStore.photoDateLabel(photo.timestamp)
                                setOnMarkerClickListener { _, _ ->
                                    onPhotoClick(photo)
                                    true
                                }
                            }
                        )
                    }
                }
                val box = BoundingBox.fromGeoPoints(geoPoints)
                view.post { view.zoomToBoundingBox(box, false, 60) }
                view.invalidate()
            }
        )
    }
}

@Composable
private fun RideStatsGrid(ride: Ride) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_distance),
                value = formatDistance(ride.distanceMeters),
                accent = RideAccent,
                modifier = Modifier.weight(1f)
            )
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_moving),
                value = formatDuration(ride.movingTimeMs),
                accent = RideAccentSecondary,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_avg_speed),
                value = formatSpeed(ride.avgSpeedMps),
                accent = RideAccentSecondary,
                modifier = Modifier.weight(1f)
            )
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_max_speed),
                value = formatSpeed(ride.maxSpeedMps),
                accent = RideEbikeAccent,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_elevation),
                value = stringResource(
                    R.string.ride_tracking_value_meters,
                    ride.elevationGainM
                ),
                accent = RideAccent,
                modifier = Modifier.weight(1f)
            )
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_paused_time),
                value = formatDuration(ride.pausedTimeMs),
                accent = TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BikeMiniStat(
                label = stringResource(R.string.ride_tracking_points),
                value = ride.pointCount.toString(),
                accent = RideAccentSecondary,
                modifier = Modifier.weight(1f)
            )
            ride.calories?.let {
                BikeMiniStat(
                    label = stringResource(R.string.ride_tracking_calories),
                    value = stringResource(R.string.ride_tracking_value_kcal, it),
                    accent = RideEbikeAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RideElevationProfile(points: List<RidePoint>, events: List<RideEvent>) {
    val pauses = remember(events) {
        events.filter { it.type == RIDE_EVENT_PAUSE || it.type == RIDE_EVENT_AUTO_PAUSE }
    }
    val altitudes = remember(points) { points.mapNotNull { it.altitude } }
    BikeSectionTitle(stringResource(R.string.ride_tracking_elevation_profile))
    if (pauses.isNotEmpty()) {
        Text(
            text = stringResource(R.string.ride_tracking_pause_markers, pauses.size),
            color = Color(0xFFFFB74D),
            fontSize = 11.sp
        )
    }
    if (altitudes.size < 3) {
        Text(
            text = stringResource(R.string.ride_tracking_elevation_empty),
            color = TextTertiary,
            fontSize = 12.sp
        )
        return
    }
    val smoothed = remember(altitudes) { RideMetrics.smoothAltitudes(altitudes) }
    val minValue = smoothed.minOrNull() ?: 0.0
    val maxValue = smoothed.maxOrNull() ?: 0.0
    val span = (maxValue - minValue).takeIf { it > 0.001 } ?: 1.0
    val firstTimestamp = points.firstOrNull()?.timestamp ?: 0L
    val lastTimestamp = points.lastOrNull()?.timestamp ?: 0L
    val timeSpan = (lastTimestamp - firstTimestamp).coerceAtLeast(1L)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier
                .width(56.dp)
                .height(150.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.ride_tracking_value_meters, maxValue),
                color = TextSecondary,
                fontSize = 10.sp
            )
            Text(
                text = stringResource(R.string.ride_tracking_value_meters, minValue),
                color = TextTertiary,
                fontSize = 10.sp
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(150.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BgCard)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepX = size.width / (smoothed.size - 1)
                val line = Path()
                val area = Path()
                smoothed.forEachIndexed { index, value ->
                    val x = index * stepX
                    val y = size.height - ((value - minValue) / span * size.height).toFloat()
                    if (index == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, size.height)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(size.width, size.height)
                area.close()
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        listOf(RideAccent.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
                drawPath(
                    path = line,
                    brush = Brush.horizontalGradient(listOf(RideAccent, RideAccentSecondary)),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                pauses.forEach { pause ->
                    val fraction = ((pause.timestamp - firstTimestamp).toFloat() / timeSpan)
                        .coerceIn(0f, 1f)
                    val x = fraction * size.width
                    drawLine(
                        color = Color(0xFFFFB74D),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                }
            }
        }
    }
}

@Composable
private fun RideSplitRow(split: RideSplit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(80.dp)) {
            Text(
                text = stringResource(R.string.ride_tracking_split_km, split.index + 1),
                color = TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = String.format(Locale.getDefault(), "%.2f km", split.distanceM / 1000.0),
                color = TextTertiary,
                fontSize = 10.sp
            )
        }
        Text(
            text = formatDuration(split.durationMs),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp)
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatSpeed(split.avgSpeedMps),
                color = RideAccentSecondary,
                fontSize = 12.sp
            )
            Text(
                text = stringResource(R.string.ride_tracking_value_meters, split.elevationGainM),
                color = TextTertiary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RidePhotoTimelineRow(
    photo: RidePhoto,
    file: File,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = RidePhotoStore.photoDateLabel(photo.timestamp),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            val offset = photo.trackOffsetM
            Text(
                text = if (offset != null) {
                    stringResource(R.string.ride_tracking_photo_at_km, offset / 1000.0)
                } else {
                    stringResource(R.string.ride_tracking_photo_no_position)
                },
                color = RideAccentSecondary,
                fontSize = 11.sp
            )
            photo.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(text = caption, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RidePhotoViewer(
    photo: RidePhoto,
    file: File,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp, bottom = 96.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.ride_tracking_close),
                        tint = Color.White
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.loschen),
                        tint = Color(0xFFFF8A80)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = RidePhotoStore.photoDateLabel(photo.timestamp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                val offset = photo.trackOffsetM
                Text(
                    text = if (offset != null) {
                        stringResource(R.string.ride_tracking_photo_at_km, offset / 1000.0)
                    } else {
                        stringResource(R.string.ride_tracking_photo_no_position)
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                photo.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                    Text(text = caption, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: String,
    selected: Boolean,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    Brush.horizontalGradient(colors.map { it.copy(alpha = 0.28f) })
                } else {
                    Brush.horizontalGradient(listOf(BgCard, BgCard))
                }
            )
            .border(
                1.dp,
                if (selected) colors.first().copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun RideActionButton(
    text: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(colors))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun handleStartError(context: Context, result: RideStartResult, show: (String) -> Unit) {
    when (result) {
        RideStartResult.FEATURE_OFF -> show(context.getString(R.string.ride_tracking_error_feature_off))
        RideStartResult.NO_PERMISSION -> show(context.getString(R.string.ride_tracking_error_no_permission))
        RideStartResult.FAILED -> show(context.getString(R.string.ride_tracking_start_failed))
        else -> Unit
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(
    if (mode == RIDE_MODE_EBIKE) R.string.ride_tracking_mode_ebike else R.string.ride_tracking_mode_bike
)

@Composable
private fun batteryProfileLabel(profile: String): String = stringResource(
    when (profile) {
        BATTERY_PROFILE_HIGH -> R.string.ride_tracking_profile_high
        BATTERY_PROFILE_BALANCED -> R.string.ride_tracking_profile_balanced
        BATTERY_PROFILE_SAVER -> R.string.ride_tracking_profile_saver
        else -> R.string.ride_tracking_profile_paused
    }
)

private fun formatDistance(meters: Double): String =
    String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)

private fun formatSpeed(mps: Double): String =
    String.format(Locale.getDefault(), "%.1f km/h", mps * 3.6)

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
