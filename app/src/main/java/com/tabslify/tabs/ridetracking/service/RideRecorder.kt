package com.tabslify.tabs.ridetracking.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tabslify.core.logging.AppLog
import com.tabslify.core.ui.FeatureToggle
import com.tabslify.core.ui.isFeatureEnabled
import com.tabslify.tabs.exploretab.ExploreRepository
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_BALANCED
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_HIGH
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_PAUSED
import com.tabslify.tabs.ridetracking.data.BATTERY_PROFILE_SAVER
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_AUTO_PAUSE
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_AUTO_RESUME
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_GPS_GAP
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_PAUSE
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_RESUME
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_START
import com.tabslify.tabs.ridetracking.data.RIDE_EVENT_STOP
import com.tabslify.tabs.ridetracking.data.RIDE_MODE_BIKE
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_FINISHED
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_PAUSED
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_RECORDING
import com.tabslify.tabs.ridetracking.data.Ride
import com.tabslify.tabs.ridetracking.data.RideEvent
import com.tabslify.tabs.ridetracking.data.RideMetrics
import com.tabslify.tabs.ridetracking.data.RidePoint
import com.tabslify.tabs.ridetracking.data.RidePointFilter
import com.tabslify.tabs.ridetracking.data.RideRecorderState
import com.tabslify.tabs.ridetracking.data.RideRepository
import com.tabslify.tabs.ridetracking.data.RideStatus
import com.tabslify.tabs.ridetracking.data.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

enum class RideStartResult {
    STARTED,
    ALREADY_ACTIVE,
    FEATURE_OFF,
    NO_PERMISSION,
    FAILED
}

class RideActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        RideRecorder.onActivityResult(context, result)
    }
}

object RideRecorder {

    private const val TAG = "RideRecorder"
    private const val MAX_ACCURACY_METERS = 50f
    private const val WEAK_ACCURACY_METERS = 30f
    private const val WEAK_ACCURACY_MS = 30_000L
    private const val MAX_SPEED_MPS = 25.0
    private const val MAX_JUMP_SPEED_MPS = 30.0
    private const val GPS_GAP_MS = 90_000L
    private const val AUTO_PAUSE_AFTER_MS = 60_000L
    private const val AUTO_RESUME_AFTER_MS = 20_000L
    private const val AUTO_PAUSE_SPEED_MPS = 1.2
    private const val AUTO_RESUME_SPEED_MPS = 2.0
    private const val AUTO_PAUSE_MOVEMENT_M = 30.0
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val FLUSH_POINT_LIMIT = 20
    private const val HEARTBEAT_MS = 15_000L
    private const val TICK_MS = 1_000L
    private const val ACTIVITY_UPDATE_MS = 20_000L

    private val _state = MutableStateFlow(RideRecorderState())
    val state: StateFlow<RideRecorderState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var repository: RideRepository? = null
    private var exploreRepository: ExploreRepository? = null
    private var mainScope: CoroutineScope? = null
    private var tickerJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    private var locationCallback: LocationCallback? = null
    private var activityPendingIntent: PendingIntent? = null
    private var currentProfile: String = ""

    private var rideRow: Ride? = null
    private var rideId: String? = null
    private var mode: String = RIDE_MODE_BIKE
    private var startAt: Long = 0L
    private var lastTickAt: Long = 0L
    private var movingAccumMs: Long = 0L
    private var pausedAccumMs: Long = 0L
    private var distanceMeters: Double = 0.0
    private var elevationGain: Double = 0.0
    private var elevationLoss: Double = 0.0
    private var maxSpeed: Double = 0.0
    private var currentSpeed: Double = 0.0
    private var pointCount: Int = 0
    private var lastAccepted: TrackPoint? = null
    private var pausedAnchor: TrackPoint? = null
    private val pendingPoints = mutableListOf<RidePoint>()
    private var lastFlushAt: Long = 0L
    private var lastHeartbeatAt: Long = 0L
    private var slowSince: Long = 0L
    private var slowStartDistance: Double = 0.0
    private var fastSince: Long = 0L
    private var lastActivityAt: Long = 0L
    private var lastActivityStill: Boolean = false
    private var lastActivityBike: Boolean = false
    private var manualPaused: Boolean = false
    private var autoPaused: Boolean = false
    private var weakSince: Long = 0L
    private var lastAltitude: Double? = null
    private val altitudeWindow = ArrayDeque<Double>()
    private val filter = RidePointFilter(
        maxAccuracyMeters = MAX_ACCURACY_METERS,
        maxSpeedMps = MAX_SPEED_MPS,
        maxJumpSpeedMps = MAX_JUMP_SPEED_MPS
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun start(context: Context, requestedMode: String = RIDE_MODE_BIKE): RideStartResult {
        val app = context.applicationContext
        if (_state.value.active) return RideStartResult.ALREADY_ACTIVE
        if (!isFeatureEnabled(app, FeatureToggle.RIDE_TRACKING)) return RideStartResult.FEATURE_OFF
        if (!hasLocationPermission(app)) return RideStartResult.NO_PERMISSION
        ensure(app)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        mode = requestedMode
        rideId = id
        startAt = now
        lastTickAt = now
        lastFlushAt = now
        lastHeartbeatAt = now
        movingAccumMs = 0L
        pausedAccumMs = 0L
        distanceMeters = 0.0
        elevationGain = 0.0
        elevationLoss = 0.0
        maxSpeed = 0.0
        currentSpeed = 0.0
        pointCount = 0
        lastAccepted = null
        pausedAnchor = null
        pendingPoints.clear()
        slowSince = 0L
        fastSince = 0L
        lastActivityAt = 0L
        lastActivityStill = false
        lastActivityBike = false
        manualPaused = false
        autoPaused = false
        weakSince = 0L
        lastAltitude = null
        altitudeWindow.clear()
        currentProfile = ""

        val row = Ride(
            id = id,
            mode = requestedMode,
            status = RIDE_STATUS_RECORDING,
            startTime = now,
            heartbeatAt = now,
            createdAt = now,
            updatedAt = now
        )
        rideRow = row
        _state.value = RideRecorderState(
            status = RIDE_STATUS_RECORDING,
            rideId = id,
            mode = requestedMode
        )
        ioScope.launch { runCatching { repository?.createRide(row) } }

        val intent = Intent(app, RideTrackingService::class.java).apply {
            action = RideCommand.ACTION_START
            putExtra(RideCommand.EXTRA_RIDE_ID, id)
            putExtra(RideCommand.EXTRA_MODE, requestedMode)
        }
        return try {
            ContextCompat.startForegroundService(app, intent)
            RideStartResult.STARTED
        } catch (e: Exception) {
            AppLog.e(TAG, "Service-Start fehlgeschlagen: ${e.message}")
            rideId = null
            rideRow = null
            _state.value = RideRecorderState(error = e.message)
            RideStartResult.FAILED
        }
    }

    fun pause(context: Context, automatic: Boolean = false) {
        if (_state.value.status != RIDE_STATUS_RECORDING) return
        accumulate()
        lastTickAt = System.currentTimeMillis()
        manualPaused = !automatic
        autoPaused = automatic
        slowSince = 0L
        fastSince = 0L
        rideRow = rideRow?.copy(status = RIDE_STATUS_PAUSED, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(
            status = RIDE_STATUS_PAUSED,
            autoPaused = automatic,
            manualPaused = !automatic
        )
        addEvent(if (automatic) RIDE_EVENT_AUTO_PAUSE else RIDE_EVENT_PAUSE)
        persistSummary()
        applyLocationProfile(force = true)
    }

    fun resume(context: Context, automatic: Boolean = false) {
        if (_state.value.status != RIDE_STATUS_PAUSED) return
        if (manualPaused && automatic) return
        accumulate()
        lastTickAt = System.currentTimeMillis()
        manualPaused = false
        autoPaused = false
        slowSince = 0L
        fastSince = 0L
        lastAccepted = pausedAnchor
        pausedAnchor = null
        rideRow = rideRow?.copy(status = RIDE_STATUS_RECORDING, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(
            status = RIDE_STATUS_RECORDING,
            autoPaused = false,
            manualPaused = false
        )
        addEvent(if (automatic) RIDE_EVENT_AUTO_RESUME else RIDE_EVENT_RESUME)
        persistSummary()
        applyLocationProfile(force = true)
    }

    fun stop(context: Context) {
        finishInternal(context, stopService = true)
    }

    fun clearFinished() {
        if (_state.value.status != RIDE_STATUS_FINISHED) return
        _state.value = RideRecorderState()
    }

    fun onServiceStarted(context: Context) {
        val app = context.applicationContext
        ensure(app)
        mainScope?.cancel()
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (rideId == null) {
            ioScope.launch {
                val active = runCatching { repository?.activeRide() }.getOrNull() ?: return@launch
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    restoreActiveRide(active)
                }
            }
        }
        lastTickAt = System.currentTimeMillis()
        addEvent(RIDE_EVENT_START)
        registerLocationUpdates(app)
        registerActivityUpdates(app)
        startTicker(app)
        persistSummary()
    }

    fun onServiceStartDenied(context: Context) {
        AppLog.e(TAG, "Foreground-Start verweigert")
        _state.value = _state.value.copy(error = "foreground_service_denied")
        finishInternal(context, stopService = false)
    }

    fun onServiceDestroyed(context: Context) {
        stopLocationUpdates()
        stopActivityUpdates()
        tickerJob?.cancel()
        tickerJob = null
        if (_state.value.active) {
            finishInternal(context.applicationContext, stopService = false)
        }
    }

    fun onLocationResult(context: Context, location: Location) {
        handleLocation(context.applicationContext, location)
    }

    fun onActivityResult(context: Context, result: ActivityRecognitionResult) {
        val most = result.mostProbableActivity
        val now = System.currentTimeMillis()
        lastActivityAt = now
        lastActivityStill = most.type == DetectedActivity.STILL && most.confidence >= 55
        lastActivityBike = most.type == DetectedActivity.ON_BICYCLE && most.confidence >= 55
        if (lastActivityStill) {
            if (slowSince == 0L) {
                slowSince = now
                slowStartDistance = distanceMeters
            }
        }
        if (lastActivityBike && _state.value.status == RIDE_STATUS_PAUSED && autoPaused) {
            if (fastSince == 0L) fastSince = now
        }
    }

    private fun restoreActiveRide(active: Ride) {
        rideId = active.id
        rideRow = active
        mode = active.mode
        startAt = active.startTime
        movingAccumMs = active.movingTimeMs
        pausedAccumMs = active.pausedTimeMs
        distanceMeters = active.distanceMeters
        elevationGain = active.elevationGainM
        elevationLoss = active.elevationLossM
        maxSpeed = active.maxSpeedMps
        pointCount = active.pointCount
        _state.value = RideRecorderState(
            status = active.status,
            rideId = active.id,
            mode = active.mode,
            distanceMeters = active.distanceMeters,
            movingMs = active.movingTimeMs,
            pausedMs = active.pausedTimeMs,
            elevationGainM = active.elevationGainM,
            elevationLossM = active.elevationLossM,
            maxSpeedMps = active.maxSpeedMps,
            pointCount = active.pointCount
        )
    }

    private fun ensure(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            repository = RideRepository(context.applicationContext)
            exploreRepository = ExploreRepository(context.applicationContext)
        }
        if (mainScope == null) {
            mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates(context: Context) {
        val callback = locationCallback ?: object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { handleLocation(context, it) }
                if (result.locations.isEmpty()) {
                    result.lastLocation?.let { handleLocation(context, it) }
                }
            }
        }
        locationCallback = callback
        currentProfile = profileFor()
        runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(
                    buildRequest(currentProfile),
                    callback,
                    Looper.getMainLooper()
                )
        }.onFailure {
            AppLog.e(TAG, "Location-Updates fehlgeschlagen: ${it.message}")
        }
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback ?: return
        appContext?.let { context ->
            runCatching {
                LocationServices.getFusedLocationProviderClient(context)
                    .removeLocationUpdates(callback)
            }
        }
        locationCallback = null
        currentProfile = ""
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityUpdates(context: Context) {
        val intent = Intent(context, RideActivityReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            42,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        activityPendingIntent = pending
        runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(ACTIVITY_UPDATE_MS, pending)
        }.onFailure {
            AppLog.w(TAG, "Activity-Recognition fehlgeschlagen: ${it.message}")
        }
    }

    private fun stopActivityUpdates() {
        val pending = activityPendingIntent ?: return
        appContext?.let { context ->
            runCatching {
                ActivityRecognition.getClient(context).removeActivityUpdates(pending)
            }
        }
        activityPendingIntent = null
    }

    private fun startTicker(context: Context) {
        tickerJob?.cancel()
        tickerJob = mainScope?.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(context)
            }
        }
    }

    private fun tick(context: Context) {
        val now = System.currentTimeMillis()
        if (_state.value.active) {
            accumulate(now)
        }
        lastTickAt = now
        if (_state.value.status == RIDE_STATUS_RECORDING) {
            evaluateAutoPause(now)
        } else if (_state.value.status == RIDE_STATUS_PAUSED && autoPaused) {
            evaluateAutoResume(now)
        }
        applyLocationProfile()
        updateStateTimes(now)
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            flushPoints()
        }
        if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
            lastHeartbeatAt = now
            persistSummary()
        }
    }

    private fun accumulate(now: Long = System.currentTimeMillis()) {
        val delta = (now - lastTickAt).coerceAtLeast(0L)
        when (_state.value.status) {
            RIDE_STATUS_RECORDING -> movingAccumMs += delta
            RIDE_STATUS_PAUSED -> pausedAccumMs += delta
        }
    }

    private fun updateStateTimes(now: Long) {
        if (!_state.value.active) return
        val total = (now - startAt).coerceAtLeast(0L)
        val moving = if (_state.value.status == RIDE_STATUS_RECORDING) {
            movingAccumMs + (now - lastTickAt).coerceAtLeast(0L)
        } else {
            movingAccumMs
        }
        val paused = if (_state.value.status == RIDE_STATUS_PAUSED) {
            pausedAccumMs + (now - lastTickAt).coerceAtLeast(0L)
        } else {
            pausedAccumMs
        }
        val avg = if (moving > 0) distanceMeters / (moving / 1000.0) else 0.0
        _state.value = _state.value.copy(
            totalMs = total,
            movingMs = moving,
            pausedMs = paused,
            avgSpeedMps = avg
        )
    }

    private fun handleLocation(context: Context, location: Location) {
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        updateAccuracy(location.accuracy, now)
        if (location.accuracy > MAX_ACCURACY_METERS) return

        val speed = when {
            location.hasSpeed() && location.speed >= 0f -> location.speed.toDouble()
            else -> currentSpeed
        }
        currentSpeed = speed
        val candidate = TrackPoint(
            timestamp = now,
            lat = location.latitude,
            lon = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            accuracy = location.accuracy,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearing = if (location.hasBearing()) location.bearing else null
        )

        if (_state.value.status == RIDE_STATUS_PAUSED) {
            pausedAnchor = candidate
            _state.value = _state.value.copy(
                currentSpeedMps = speed,
                accuracy = location.accuracy,
                lastLat = candidate.lat,
                lastLon = candidate.lon
            )
            if (speed > AUTO_RESUME_SPEED_MPS && autoPaused) {
                if (fastSince == 0L) fastSince = now
            }
            evaluateAutoResume(now)
            return
        }

        if (_state.value.status != RIDE_STATUS_RECORDING) return

        val previous = lastAccepted
        val result = filter.evaluate(previous, candidate)
        if (!result.accepted) {
            _state.value = _state.value.copy(
                currentSpeedMps = speed,
                accuracy = location.accuracy,
                lastLat = candidate.lat,
                lastLon = candidate.lon
            )
            return
        }

        var newDistance = distanceMeters
        var deltaDistance = 0.0
        if (previous != null) {
            val gap = candidate.timestamp - previous.timestamp
            if (gap > GPS_GAP_MS) {
                addEvent(RIDE_EVENT_GPS_GAP, "gap_ms=$gap")
            } else {
                deltaDistance = result.distanceMeters
                newDistance += deltaDistance
            }
        }

        val persisted = candidate.copy(distanceFromStartM = newDistance)
        lastAccepted = persisted
        distanceMeters = newDistance
        pointCount += 1
        maxSpeed = maxOf(maxSpeed, speed, result.impliedSpeedMps ?: 0.0)
        addElevation(persisted.altitude, persisted.verticalAccuracy)

        pendingPoints += RidePoint(
            rideId = rideId ?: return,
            timestamp = persisted.timestamp,
            lat = persisted.lat,
            lon = persisted.lon,
            altitude = persisted.altitude,
            verticalAccuracy = persisted.verticalAccuracy,
            accuracy = persisted.accuracy,
            speedMps = persisted.speedMps,
            bearing = persisted.bearing,
            distanceFromStartM = persisted.distanceFromStartM
        )
        _state.value = _state.value.copy(
            distanceMeters = distanceMeters,
            currentSpeedMps = speed,
            maxSpeedMps = maxSpeed,
            elevationGainM = elevationGain,
            elevationLossM = elevationLoss,
            pointCount = pointCount,
            accuracy = location.accuracy,
            lastLat = persisted.lat,
            lastLon = persisted.lon,
            avgSpeedMps = if (movingAccumMs > 0) distanceMeters / (movingAccumMs / 1000.0) else 0.0
        )
        if (pendingPoints.size >= FLUSH_POINT_LIMIT) {
            flushPoints()
        }
        evaluateAutoPause(now)
        applyLocationProfile()
        if (deltaDistance > 0.0 && speed > AUTO_RESUME_SPEED_MPS) {
            slowSince = 0L
        }
    }

    private fun updateAccuracy(accuracy: Float, now: Long) {
        if (accuracy > WEAK_ACCURACY_METERS) {
            if (weakSince == 0L) weakSince = now
        } else {
            weakSince = 0L
        }
        val weak = weakSince > 0L && now - weakSince >= WEAK_ACCURACY_MS
        _state.value = _state.value.copy(accuracy = accuracy, gpsWeak = weak)
    }

    private fun addElevation(altitude: Double?, verticalAccuracy: Float?) {
        if (altitude == null) return
        if (verticalAccuracy != null && verticalAccuracy > 20f) return
        altitudeWindow.addLast(altitude)
        while (altitudeWindow.size > 5) altitudeWindow.removeFirst()
        val sorted = altitudeWindow.sorted()
        val median = sorted[sorted.size / 2]
        val last = lastAltitude
        if (last == null) {
            lastAltitude = median
            return
        }
        val delta = median - last
        if (abs(delta) >= 3.0) {
            if (delta > 0) elevationGain += delta else elevationLoss += -delta
            lastAltitude = median
        }
    }

    private fun evaluateAutoPause(now: Long) {
        val stillRecently = lastActivityStill && now - lastActivityAt <= 60_000L
        val slow = currentSpeed < AUTO_PAUSE_SPEED_MPS || stillRecently
        if (!slow) {
            slowSince = 0L
            return
        }
        if (slowSince == 0L) {
            slowSince = now
            slowStartDistance = distanceMeters
            return
        }
        if (now - slowSince >= AUTO_PAUSE_AFTER_MS &&
            distanceMeters - slowStartDistance < AUTO_PAUSE_MOVEMENT_M
        ) {
            pause(appContext ?: return, automatic = true)
        }
    }

    private fun evaluateAutoResume(now: Long) {
        if (!autoPaused) return
        val bikeRecently = lastActivityBike && now - lastActivityAt <= 60_000L
        val fast = currentSpeed > AUTO_RESUME_SPEED_MPS || bikeRecently
        if (!fast) {
            fastSince = 0L
            return
        }
        if (fastSince == 0L) {
            fastSince = now
            return
        }
        if (now - fastSince >= AUTO_RESUME_AFTER_MS) {
            resume(appContext ?: return, automatic = true)
        }
    }

    private fun profileFor(): String = when {
        _state.value.status == RIDE_STATUS_PAUSED -> BATTERY_PROFILE_PAUSED
        currentSpeed > 3.0 -> BATTERY_PROFILE_HIGH
        currentSpeed > 1.0 -> BATTERY_PROFILE_BALANCED
        else -> BATTERY_PROFILE_SAVER
    }

    private fun buildRequest(profile: String): LocationRequest {
        val interval = when (profile) {
            BATTERY_PROFILE_HIGH -> 3_000L
            BATTERY_PROFILE_BALANCED -> 5_000L
            BATTERY_PROFILE_PAUSED -> 30_000L
            else -> 25_000L
        }
        val distance = when (profile) {
            BATTERY_PROFILE_HIGH -> 3f
            BATTERY_PROFILE_BALANCED -> 5f
            BATTERY_PROFILE_PAUSED -> 20f
            else -> 10f
        }
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMinUpdateDistanceMeters(distance)
            .setMaxUpdateDelayMillis(interval * 2)
            .setWaitForAccurateLocation(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationProfile(force: Boolean = false) {
        val callback = locationCallback ?: return
        val profile = profileFor()
        if (!force && profile == currentProfile) return
        val context = appContext ?: return
        currentProfile = profile
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(buildRequest(profile), callback, Looper.getMainLooper())
        }
        _state.value = _state.value.copy(batteryProfile = profile)
    }

    private fun flushPoints() {
        if (pendingPoints.isEmpty()) return
        val points = pendingPoints.toList()
        pendingPoints.clear()
        lastFlushAt = System.currentTimeMillis()
        ioScope.launch {
            runCatching { repository?.addPoints(points) }
            points.forEach { point ->
                runCatching { exploreRepository?.ingestTile(point.lat, point.lon) }
            }
        }
        persistSummary()
    }

    private fun persistSummary() {
        val id = rideId ?: return
        val base = rideRow ?: return
        val now = System.currentTimeMillis()
        val updated = base.copy(
            status = _state.value.status,
            movingTimeMs = movingAccumMs,
            pausedTimeMs = pausedAccumMs,
            distanceMeters = distanceMeters,
            elevationGainM = elevationGain,
            elevationLossM = elevationLoss,
            avgSpeedMps = if (movingAccumMs > 0) distanceMeters / (movingAccumMs / 1000.0) else 0.0,
            maxSpeedMps = maxSpeed,
            pointCount = pointCount,
            heartbeatAt = now,
            updatedAt = now
        )
        rideRow = updated
        ioScope.launch {
            runCatching { repository?.updateRide(updated) }
        }
    }

    private fun addEvent(type: String, detail: String? = null) {
        val id = rideId ?: return
        val now = System.currentTimeMillis()
        rideRow = rideRow?.copy(updatedAt = now)
        ioScope.launch {
            runCatching {
                repository?.addEvent(
                    RideEvent(rideId = id, timestamp = now, type = type, detail = detail)
                )
            }
        }
    }

    private fun finishInternal(context: Context, stopService: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val id = rideId ?: return
            accumulate()
            val end = System.currentTimeMillis()
            val pending = pendingPoints.toList()
            pendingPoints.clear()
            val moving = movingAccumMs
            val paused = pausedAccumMs
            val distance = distanceMeters
            val gain = elevationGain
            val loss = elevationLoss
            val maxSpeedValue = maxSpeed
            val points = pointCount
            val avg = if (moving > 0) distance / (moving / 1000.0) else 0.0

            tickerJob?.cancel()
            tickerJob = null
            stopLocationUpdates()
            stopActivityUpdates()

            _state.value = _state.value.copy(
                status = RIDE_STATUS_FINISHED,
                rideId = null,
                finishedRideId = id,
                totalMs = (end - startAt).coerceAtLeast(0L),
                movingMs = moving,
                pausedMs = paused,
                distanceMeters = distance,
                elevationGainM = gain,
                elevationLossM = loss,
                avgSpeedMps = avg,
                maxSpeedMps = maxSpeedValue,
                pointCount = points,
                autoPaused = false,
                manualPaused = false,
                currentSpeedMps = 0.0,
                batteryProfile = BATTERY_PROFILE_PAUSED
            )

            addEvent(RIDE_EVENT_STOP)
            ioScope.launch {
                runCatching {
                    repository?.addPoints(pending)
                    pending.forEach { point ->
                        runCatching { exploreRepository?.ingestTile(point.lat, point.lon) }
                    }
                    val allPoints = repository?.pointsForRide(id).orEmpty()
                    val splits = RideMetrics.computeSplits(allPoints, id)
                    repository?.finishRide(
                        rideId = id,
                        endTime = end,
                        movingTimeMs = moving,
                        pausedTimeMs = paused,
                        distanceMeters = distance,
                        elevationGainM = gain,
                        elevationLossM = loss,
                        avgSpeedMps = avg,
                        maxSpeedMps = maxSpeedValue,
                        pointCount = points,
                        splits = splits
                    )
                    AppLog.i(TAG, "Fahrt beendet: $id km=${"%.2f".format(distance / 1000.0)}")
                }
            }

            rideId = null
            rideRow = null
            manualPaused = false
            autoPaused = false
            lastAccepted = null
            pausedAnchor = null
            if (stopService) {
                context.stopService(Intent(context, RideTrackingService::class.java))
            }
        } finally {
            busy.set(false)
        }
    }
}
