package com.tabslify.tabs.exploretab

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.tabslify.R
import com.tabslify.core.functions.errorInsert
import com.tabslify.core.objects.Config
import com.tabslify.core.ui.FeatureToggle
import com.tabslify.core.ui.isFeatureEnabled
import com.tabslify.quiethoursnotificationhelper.isLaptopConnected
import com.tabslify.quiethoursnotificationhelper.stopAllSyncServices
import com.tabslify.quiethoursnotificationhelper.syncTodosWithLaptop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class TrackerStatus(@StringRes val labelRes: Int) {
    STOPPED_HOME(R.string.tracker_status_stopped_home),
    OUTSIDE(R.string.tracker_status_outside),
    PAUSED_NIGHT(R.string.tracker_status_paused_night),
    WAITING_GEOFENCE(R.string.tracker_status_waiting_geofence),
    BACKGROUND_LOCATION_MISSING(R.string.tracker_status_background_location_missing),
    ACTIVE_BACKGROUND(R.string.tracker_status_active_background),
    UPDATE_ERROR(R.string.tracker_status_update_error),
    DISABLED(R.string.tracker_status_disabled),
    STARTING(R.string.tracker_status_starting),
    ERROR(R.string.tracker_status_error),
    ACTIVE(R.string.tracker_status_active),
    FGS_NOT_STARTED(R.string.tracker_status_fgs_not_started),
    INACTIVE(R.string.tracker_status_inactive),
}

data class ExploreTrackerInfo(
    val homeLat: Double = 0.0,
    val homeLng: Double = 0.0,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastUpdate: String? = null,
    val distanceToHomeMeters: Float? = null,
    val geofenceRegistered: Boolean = false,
    val geofenceError: String? = null,
    val lastWifiHome: Boolean? = null,
    val lastRecheckAt: String? = null,
    val lastNetwork: String? = null,
    val isNight: Boolean = false,
    val isEnabled: Boolean = false,
)

data class ExploreActivityInfo(
    val mode: String = "UNKNOWN",
    val confidence: Int = 0,
    val held: Boolean = false,
)

class ExploreGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            errorInsert(
                "ExploreTracker",
                "GeofenceReceiver - Fehler: ${event.errorCode}",
                Instant.now().toString(),
                "ERROR"
            )
            return
        }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                errorInsert(
                    "ExploreTracker",
                    "GeofenceReceiver - ENTER (triggeringLocation=${event.triggeringLocation?.latitude},${event.triggeringLocation?.longitude})",
                    Instant.now().toString(),
                    "LOG"
                )
                ExploreLocationTracker.onGeofenceEnter(context)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                errorInsert(
                    "ExploreTracker",
                    "GeofenceReceiver - EXIT (triggeringLocation=${event.triggeringLocation?.latitude},${event.triggeringLocation?.longitude})",
                    Instant.now().toString(),
                    "LOG"
                )
                ExploreLocationTracker.onGeofenceExit(context)
            }
        }
    }
}

class ExploreLocationUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        ExploreLocationTracker.onBackgroundLocationResult(context, result)
    }
}

class ExploreActivityRecognitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        ExploreLocationTracker.onActivityRecognitionResult(context, result)
    }
}

object ExploreLocationTracker {

    private val _trackerStatus = MutableStateFlow(TrackerStatus.INACTIVE)
    val trackerStatus: StateFlow<TrackerStatus> = _trackerStatus.asStateFlow()

    private val _trackerStatusDetail = MutableStateFlow<String?>(null)
    val trackerStatusDetail: StateFlow<String?> = _trackerStatusDetail.asStateFlow()

    private fun setTrackerStatus(status: TrackerStatus, detail: String? = null) {
        _trackerStatus.value = status
        _trackerStatusDetail.value = detail
    }

    private val _trackerInfo = MutableStateFlow(ExploreTrackerInfo())
    val trackerInfo: StateFlow<ExploreTrackerInfo> = _trackerInfo.asStateFlow()

    private val _currentActivity = MutableStateFlow(ExploreActivityInfo())
    val currentActivity: StateFlow<ExploreActivityInfo> = _currentActivity.asStateFlow()

    private const val GEOFENCE_ID = "HOME"
    private const val GEOFENCE_RADIUS = 100f
    private const val GEOFENCE_TRANSITION_COOLDOWN_MS = 10 * 60_000L
    private const val PRESENCE_PREFS = "explore_presence"
    private const val PRESENCE_KEY = "presence"
    private const val PRESENCE_HOME = "home"
    private const val PRESENCE_OUTSIDE = "outside"
    private const val RECHECK_TAG = "explore_cooldown_recheck"
    private const val WIFI_CHECK_TIMEOUT_MS = 3_000L
    private const val BACKGROUND_LOCATION_REQUEST_CODE = 3
    private const val BACKGROUND_UPDATE_INTERVAL_MS = 60_000L
    private const val BACKGROUND_UPDATE_MIN_INTERVAL_MS = 30_000L
    private const val BACKGROUND_UPDATE_MIN_DISTANCE_M = 25f
    private const val BACKGROUND_UPDATE_BATCH_MS = 2 * 60_000L
    const val HOME_WIFI_SSID = "FRITZ!Box 5590 XO"
    private const val NIGHT_START_HOUR = 0
    private const val NIGHT_END_HOUR = 5

    private const val MAX_ACCURACY_METERS = 50f
    private const val MAX_VERTICAL_ACCURACY_METERS = 15f
    private const val UNIVERSAL_SPEED_CEILING_MPS = 55f
    private const val AR_UPDATE_INTERVAL_MS = 30_000L
    private const val STILL_BATCH_INTERVAL_MS = 5 * 60_000L
    private const val AR_CONFIDENCE_FLOOR = 50
    private const val AR_CEILING_CONFIDENCE_FLOOR = 75

    private const val AR_MODE_HOLD_MS = 3 * 60_000L
    private const val MIN_IMPLIED_SPEED_INTERVAL_S = 2.0

    private const val AR_LOG_CONFIDENCE_DELTA = 15
    private const val AR_LOG_MAX_SILENCE_MS = 5 * 60_000L

    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var currentMode: String = "UNKNOWN"
    private var currentProfile: String = requestProfile("UNKNOWN")
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastConfidentMode = "UNKNOWN"
    private var lastConfidentAt = 0L

    private var lastLoggedActivityMode: String? = null
    private var lastLoggedActivityConfidence = 0
    private var lastLoggedActivityAt = 0L

    @Volatile
    private var isEnabled = false

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var backgroundLocationRequested = false

    @Volatile
    private var geofenceArmed = false

    @Volatile
    private var lastGeofenceEnter = false

    @Volatile
    private var lastGeofenceTransitionAt = 0L

    private fun isNightTime(): Boolean {
        val hour = Instant.now().atZone(java.time.ZoneId.systemDefault()).hour
        return hour in NIGHT_START_HOUR until NIGHT_END_HOUR
    }

    val trackingActive: Boolean
        get() = (serviceRunning || backgroundLocationRequested) && isEnabled && !isNightTime()

    private fun distanceToHome(lat: Double, lng: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat, lng, Config.LAT, Config.LON, results)
        return results[0]
    }

    fun onArrivedHome(context: Context) {
        val appCtx = context.applicationContext
        setPresence(appCtx, PRESENCE_HOME)
        syncTodosWithLaptop(appCtx)
    }

    fun onLeftHome(context: Context) {
        val appCtx = context.applicationContext
        if (isLaptopConnected) {
            stopAllSyncServices(appCtx)
        }
        if (presence(appCtx) == PRESENCE_OUTSIDE) {
            return
        }
        setPresence(appCtx, PRESENCE_OUTSIDE)
        runCatching { com.tabslify.widget.LampWidgetSync.reportOff(appCtx, "explore_verlassen") }
    }

    private fun presence(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
            .getString(PRESENCE_KEY, null)

    private fun setPresence(context: Context, value: String) {
        context.applicationContext
            .getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PRESENCE_KEY, value)
            .apply()
    }

    fun onGeofenceEnter(context: Context) {
        if (swallowedByCooldown(enter = true)) {
            logEvent(context, "GEOFENCE", "ENTER", 0, "cooldown")
            scheduleRecheckAfterCooldown(context)
            return
        }
        logEvent(context, "GEOFENCE", "ENTER", 0, null)
        setTrackerStatus(TrackerStatus.STOPPED_HOME)
        stop(context)
        onArrivedHome(context)
    }

    fun onGeofenceExit(context: Context) {
        if (swallowedByCooldown(enter = false)) {
            logEvent(context, "GEOFENCE", "EXIT", 0, "cooldown")
            scheduleRecheckAfterCooldown(context)
            return
        }
        val appCtx = context.applicationContext
        logEvent(appCtx, "GEOFENCE", "EXIT", 0, null)
        ExploreNetworkWatcher.arm(appCtx)
        if (!isNightTime()) {
            setTrackerStatus(TrackerStatus.OUTSIDE)
        }
        startTracking(appCtx)
        onLeftHome(appCtx)
    }

    private fun swallowedByCooldown(enter: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!enter || lastGeofenceEnter == enter || now - lastGeofenceTransitionAt >= GEOFENCE_TRANSITION_COOLDOWN_MS) {
            lastGeofenceEnter = enter
            lastGeofenceTransitionAt = now
            return false
        }
        return true
    }

    private fun scheduleRecheckAfterCooldown(context: Context) {
        val appCtx = context.applicationContext
        val remaining = (GEOFENCE_TRANSITION_COOLDOWN_MS - (System.currentTimeMillis() - lastGeofenceTransitionAt))
            .coerceIn(1_000L, GEOFENCE_TRANSITION_COOLDOWN_MS)

        val req = OneTimeWorkRequestBuilder<ExploreRecheckWorker>()
            .setInitialDelay(remaining, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(appCtx)
            .enqueueUniqueWork(RECHECK_TAG, ExistingWorkPolicy.REPLACE, req)
    }

    private fun startTracking(context: Context) {
        val appCtx = context.applicationContext
        if (isNightTime() || serviceRunning) {
            return
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            return
        }
        isEnabled = true
        _trackerInfo.value = _trackerInfo.value.copy(isNight = false, isEnabled = true)
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, ExploreForegroundService::class.java))
        } catch (e: Exception) {
            errorInsert(
                "ExploreTracker",
                "FGS-Start fehlgeschlagen (${e::class.java.simpleName}): ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
            startBackgroundLocationUpdates(appCtx)
        }
    }

    fun recheck(context: Context, network: String? = null) {
        val appCtx = context.applicationContext

        if (isNightTime()) {
            setTrackerStatus(TrackerStatus.PAUSED_NIGHT)
            _trackerInfo.value = _trackerInfo.value.copy(isNight = true, isEnabled = false)
            ExploreNightRestartWorker.schedule(appCtx)
            return
        }
        ExploreNightRestartWorker.cancel(appCtx)

        _trackerInfo.value = _trackerInfo.value.copy(
            isNight = false,
            lastRecheckAt = Instant.now().toString(),
            lastNetwork = network,
        )

        try {
            registerGeofence(appCtx)
        } catch (_: Exception) {
        }

        if (!serviceRunning && !backgroundLocationRequested) {
            setTrackerStatus(TrackerStatus.WAITING_GEOFENCE)
        }

        homeWifiStatus(appCtx, HOME_WIFI_SSID) { isHomeWifi ->
            _trackerInfo.value = _trackerInfo.value.copy(lastWifiHome = isHomeWifi)
            if (isHomeWifi) {
                setTrackerStatus(TrackerStatus.STOPPED_HOME)
                stop(appCtx)
                onArrivedHome(appCtx)
                return@homeWifiStatus
            }
            evaluateCurrentLocation(appCtx) { distanceHome ->
                if (distanceHome <= GEOFENCE_RADIUS) {
                    setTrackerStatus(TrackerStatus.STOPPED_HOME)
                    stop(appCtx)
                    onArrivedHome(appCtx)
                } else {
                    setTrackerStatus(TrackerStatus.OUTSIDE)
                    startTracking(appCtx)
                    onLeftHome(appCtx)
                }
            }
        }
    }

    private fun getClient(context: Context) =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    private fun homeWifiStatus(context: Context, homeSsid: String, onResult: (Boolean) -> Unit) {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            onResult(false)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var done = false
        val finish: (Boolean) -> Unit = { isHome ->
            if (!done) {
                done = true
                handler.removeCallbacksAndMessages(null)
                onResult(isHome)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (done) return
                val wifiInfo = caps.transportInfo as? WifiInfo
                val ssid = wifiInfo?.ssid
                    ?.removeSurrounding("\"")
                    ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
                if (ssid == null) return
                runCatching { cm.unregisterNetworkCallback(this) }
                finish(ssid.equals(homeSsid, ignoreCase = true))
            }

            override fun onUnavailable() {
                runCatching { cm.unregisterNetworkCallback(this) }
                finish(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        handler.postDelayed({
            if (!done) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
            finish(false)
        }, WIFI_CHECK_TIMEOUT_MS)

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            finish(false)
        }
    }

    private fun backgroundLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, BACKGROUND_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(BACKGROUND_UPDATE_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(BACKGROUND_UPDATE_MIN_DISTANCE_M)
            .setMaxUpdateDelayMillis(BACKGROUND_UPDATE_BATCH_MS)
            .setWaitForAccurateLocation(false)
            .build()

    @SuppressLint("MissingPermission")
    private fun startBackgroundLocationUpdates(context: Context) {
        val appCtx = context.applicationContext
        if (backgroundLocationRequested) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            isEnabled = false
            _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
            setTrackerStatus(TrackerStatus.BACKGROUND_LOCATION_MISSING)
            errorInsert(
                "ExploreTracker",
                "Hintergrund-Updates nicht möglich: ACCESS_BACKGROUND_LOCATION fehlt",
                Instant.now().toString(),
                "ERROR"
            )
            return
        }
        try {
            isEnabled = true
            _trackerInfo.value = _trackerInfo.value.copy(isNight = false, isEnabled = true)
            getClient(appCtx).requestLocationUpdates(backgroundLocationRequest(), locationUpdatePendingIntent(appCtx))
                .addOnSuccessListener {
                    backgroundLocationRequested = true
                    setTrackerStatus(TrackerStatus.ACTIVE_BACKGROUND)
                    startActivityRecognitionUpdates(appCtx)
                    ExploreWorker.schedule(appCtx)
                    logEvent(appCtx, "TRACK", "START", 0, "HINTERGRUND")
                    errorInsert(
                        "ExploreTracker",
                        "Hintergrund-Updates aktiv",
                        Instant.now().toString(),
                        "LOG"
                    )
                }
                .addOnFailureListener { e ->
                    backgroundLocationRequested = false
                    isEnabled = false
                    _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
                    setTrackerStatus(TrackerStatus.UPDATE_ERROR)
                    errorInsert(
                        "ExploreTracker",
                        "Hintergrund-Updates fehlgeschlagen: ${e.message}",
                        Instant.now().toString(),
                        "ERROR"
                    )
                }
        } catch (e: Exception) {
            backgroundLocationRequested = false
            isEnabled = false
            _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
            setTrackerStatus(TrackerStatus.UPDATE_ERROR)
            errorInsert(
                "ExploreTracker",
                "Hintergrund-Updates Exception: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
        }
    }

    private fun stopBackgroundLocationUpdates(context: Context) {
        val appCtx = context.applicationContext
        try {
            getClient(appCtx).removeLocationUpdates(locationUpdatePendingIntent(appCtx))
        } catch (_: Exception) {
        }
        backgroundLocationRequested = false
    }

    fun onBackgroundLocationResult(context: Context, result: LocationResult) {
        val appCtx = context.applicationContext
        if (isNightTime()) {
            stopBackgroundLocationUpdates(appCtx)
            if (!serviceRunning) {
                stopActivityRecognitionUpdates(appCtx)
            }
            return
        }
        if (presence(appCtx) == PRESENCE_HOME) {
            stopBackgroundLocationUpdates(appCtx)
            if (!serviceRunning) {
                stopActivityRecognitionUpdates(appCtx)
            }
            return
        }
        result.locations.lastOrNull()?.let { loc ->
            handleLocation(appCtx, loc, ExploreRepository(appCtx))
        }
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ExploreGeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun activityRecognitionPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ExploreActivityRecognitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun locationUpdatePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ExploreLocationUpdateReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, BACKGROUND_LOCATION_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofence(context: Context) {
        val homeLat = Config.LAT
        val homeLng = Config.LON

        _trackerInfo.value = _trackerInfo.value.copy(
            homeLat = homeLat,
            homeLng = homeLng,
        )

        if (homeLat == 0.0 && homeLng == 0.0) {
            _trackerInfo.value = _trackerInfo.value.copy(
                geofenceRegistered = false,
                geofenceError = "Heimatkoordinaten fehlen",
            )
            return
        }

        val appCtx = context.applicationContext
        val fineGranted = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            _trackerInfo.value = _trackerInfo.value.copy(
                geofenceRegistered = false,
                geofenceError = "Feinstandort fehlt",
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            _trackerInfo.value = _trackerInfo.value.copy(
                geofenceRegistered = false,
                geofenceError = "Hintergrundstandort fehlt",
            )
            return
        }

        if (geofenceArmed) {
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(homeLat, homeLng, GEOFENCE_RADIUS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        LocationServices.getGeofencingClient(appCtx)
            .addGeofences(request, geofencePendingIntent(appCtx))
            .addOnSuccessListener {
                geofenceArmed = true
                _trackerInfo.value = _trackerInfo.value.copy(
                    geofenceRegistered = true,
                    geofenceError = null,
                )
                errorInsert(
                    "ExploreTracker",
                    "Geofence registriert (lat=%.6f, lng=%.6f, radius=%.0f)".format(homeLat, homeLng, GEOFENCE_RADIUS),
                    Instant.now().toString(),
                    "LOG"
                )
            }
            .addOnFailureListener { e ->
                geofenceArmed = false
                _trackerInfo.value = _trackerInfo.value.copy(
                    geofenceRegistered = false,
                    geofenceError = e.message,
                )
                errorInsert(
                    "ExploreTracker",
                    "Geofence-Registrierung fehlgeschlagen: ${e.message}",
                    Instant.now().toString(),
                    "ERROR"
                )
            }
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        if (!isFeatureEnabled(appCtx, FeatureToggle.EXPLORE)) {
            setTrackerStatus(TrackerStatus.DISABLED)
            _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
            return
        }
        ExploreNetworkWatcher.arm(appCtx)

        if (isNightTime()) {
            setTrackerStatus(TrackerStatus.PAUSED_NIGHT)
            _trackerInfo.value = _trackerInfo.value.copy(isNight = true, isEnabled = false)
            ExploreNightRestartWorker.schedule(appCtx)
            return
        }
        ExploreNightRestartWorker.cancel(appCtx)
        if (isEnabled && serviceRunning) {
            recheck(appCtx)
            return
        }
        isEnabled = true
        setTrackerStatus(TrackerStatus.STARTING)
        _trackerInfo.value = _trackerInfo.value.copy(
            isNight = false,
            isEnabled = true,
            lastWifiHome = null,
        )

        recheck(appCtx)
    }

    @SuppressLint("MissingPermission")
    private fun evaluateCurrentLocation(context: Context, onResolved: (Float) -> Unit) {
        if (Config.LAT == 0.0 && Config.LON == 0.0) {
            setTrackerStatus(TrackerStatus.WAITING_GEOFENCE)
            return
        }

        scope.launch {
            try {
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(30_000L)
                    .setDurationMillis(5_000L)
                    .build()

                val location = Tasks.await(getClient(context).getCurrentLocation(request, null))
                if (location == null) {
                    setTrackerStatus(TrackerStatus.WAITING_GEOFENCE)
                    return@launch
                }

                val distanceHome = distanceToHome(location.latitude, location.longitude)
                _trackerInfo.value = _trackerInfo.value.copy(
                    lastLat = location.latitude,
                    lastLng = location.longitude,
                    lastUpdate = Instant.now().toString(),
                    distanceToHomeMeters = distanceHome,
                )

                onResolved(distanceHome)
            } catch (_: Exception) {
                setTrackerStatus(TrackerStatus.WAITING_GEOFENCE)
            }
        }
    }

    private fun locationRequestFor(mode: String): LocationRequest = when (mode) {
        "STILL" -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L)
            .setMinUpdateIntervalMillis(60_000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(STILL_BATCH_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        "WALKING", "ON_FOOT" -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(8_000L)
            .setMinUpdateDistanceMeters(10f)
            .setWaitForAccurateLocation(false)
            .build()

        "ON_BICYCLE" -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(4_000L)
            .setMinUpdateDistanceMeters(15f)
            .setWaitForAccurateLocation(false)
            .build()

        "IN_VEHICLE" -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(4_000L)
            .setMinUpdateDistanceMeters(30f)
            .setWaitForAccurateLocation(false)
            .build()

        else -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(20f)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun modeSpeedCeiling(mode: String): Float = when (mode) {
        "STILL" -> 2f
        "WALKING", "ON_FOOT" -> 3f
        "ON_BICYCLE" -> 20f
        "IN_VEHICLE" -> 60f
        else -> UNIVERSAL_SPEED_CEILING_MPS
    }

    private fun requestProfile(mode: String): String = when (mode) {
        "STILL" -> "STILL"
        "WALKING", "ON_FOOT" -> "FOOT"
        "ON_BICYCLE" -> "BICYCLE"
        "IN_VEHICLE" -> "VEHICLE"
        else -> "DEFAULT"
    }

    private fun usableAltitude(location: Location): Double? {
        if (!location.hasAltitude()) return null
        if (location.hasVerticalAccuracy() && location.verticalAccuracyMeters > MAX_VERTICAL_ACCURACY_METERS) return null
        return location.altitude
    }

    private fun activityTypeToMode(type: Int): String = when (type) {
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        else -> "UNKNOWN"
    }

    private fun logEvent(context: Context, type: String, mode: String, confidence: Int, detail: String?, timestamp: Long = System.currentTimeMillis()) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                ExploreRepository(appCtx).logTrackerEvent(
                    TrackerEvent(
                        timestamp = timestamp,
                        type = type,
                        mode = mode,
                        confidence = confidence,
                        detail = detail
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    fun tuningSnapshot(): Map<String, Any> {
        val modes = listOf("STILL", "WALKING", "ON_FOOT", "ON_BICYCLE", "IN_VEHICLE", "UNKNOWN")
        return mapOf(
            "maxAccuracyMeters" to MAX_ACCURACY_METERS,
            "maxVerticalAccuracyMeters" to MAX_VERTICAL_ACCURACY_METERS,
            "universalSpeedCeilingMps" to UNIVERSAL_SPEED_CEILING_MPS,
            "arUpdateIntervalMs" to AR_UPDATE_INTERVAL_MS,
            "arConfidenceFloor" to AR_CONFIDENCE_FLOOR,
            "arCeilingConfidenceFloor" to AR_CEILING_CONFIDENCE_FLOOR,
            "arModeHoldMs" to AR_MODE_HOLD_MS,
            "minImpliedSpeedIntervalSeconds" to MIN_IMPLIED_SPEED_INTERVAL_S,
            "geofenceRadiusMeters" to GEOFENCE_RADIUS,
            "nightStartHour" to NIGHT_START_HOUR,
            "nightEndHour" to NIGHT_END_HOUR,
            "speedCeilingsMps" to modes.associateWith { modeSpeedCeiling(it) },
            "requestProfiles" to modes.associateWith { requestProfile(it) },
            "locationRequests" to modes.associateWith { mode ->
                val request = locationRequestFor(mode)
                mapOf(
                    "intervalMs" to request.intervalMillis,
                    "minIntervalMs" to request.minUpdateIntervalMillis,
                    "minDistanceMeters" to request.minUpdateDistanceMeters,
                    "priority" to request.priority
                )
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationRequest(context: Context, mode: String) {
        val callback = locationCallback ?: return
        currentMode = mode
        currentProfile = requestProfile(mode)
        try {
            val client = getClient(context)
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(locationRequestFor(mode), callback, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    fun onActivityRecognitionResult(context: Context, result: ActivityRecognitionResult) {
        val appCtx = context.applicationContext
        val most = result.mostProbableActivity
        val now = System.currentTimeMillis()

        val detected = activityTypeToMode(most.type)
        val confident = most.confidence >= AR_CONFIDENCE_FLOOR && detected != "UNKNOWN"
        var held = false
        val mode = when {
            confident -> {
                lastConfidentMode = detected
                lastConfidentAt = now
                detected
            }

            lastConfidentMode != "UNKNOWN" && now - lastConfidentAt <= AR_MODE_HOLD_MS -> {
                held = true
                lastConfidentMode
            }

            else -> {
                lastConfidentMode = "UNKNOWN"
                "UNKNOWN"
            }
        }
        _currentActivity.value = ExploreActivityInfo(mode, most.confidence, held)

        val worthLogging = mode != lastLoggedActivityMode ||
            kotlin.math.abs(most.confidence - lastLoggedActivityConfidence) >= AR_LOG_CONFIDENCE_DELTA ||
            now - lastLoggedActivityAt >= AR_LOG_MAX_SILENCE_MS
        if (worthLogging) {
            lastLoggedActivityMode = mode
            lastLoggedActivityConfidence = most.confidence
            lastLoggedActivityAt = now
            val candidates = result.probableActivities.joinToString(" ") {
                "${activityTypeToMode(it.type)}:${it.confidence}"
            }
            val suffix = if (held) " gehalten" else ""
            logEvent(appCtx, "AR", mode, most.confidence, candidates + suffix, result.time)
        }

        val profile = requestProfile(mode)
        currentMode = mode
        if (locationCallback != null && profile != currentProfile) {
            logEvent(appCtx, "MODE_SWITCH", mode, most.confidence, "profil=$currentProfile->$profile")
            applyLocationRequest(appCtx, mode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startActivityRecognitionUpdates(context: Context) {
        try {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(AR_UPDATE_INTERVAL_MS, activityRecognitionPendingIntent(context))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityRecognitionUpdates(context: Context) {
        try {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(activityRecognitionPendingIntent(context))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(context: Context, repo: ExploreRepository) {
        if (locationCallback != null || backgroundLocationRequested) {
            return
        }

        val client = getClient(context)

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isEnabled || isNightTime()) {
                    stop(context)
                    return
                }
                val loc = result.lastLocation ?: return
                handleLocation(context, loc, repo)
            }
        }

        locationCallback = callback
        try {
            client.requestLocationUpdates(locationRequestFor(currentMode), callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    errorInsert(
                        "ExploreTracker",
                        "Location-Updates aktiv (profil=$currentProfile)",
                        Instant.now().toString(),
                        "LOG"
                    )
                }
                .addOnFailureListener { e ->
                    if (locationCallback === callback) {
                        locationCallback = null
                    }
                    setTrackerStatus(TrackerStatus.ERROR, e.message)
                    errorInsert(
                        "ExploreTracker",
                        "Location-Updates fehlgeschlagen: ${e.message}",
                        Instant.now().toString(),
                        "ERROR"
                    )
                    startBackgroundLocationUpdates(context)
                }
        } catch (e: Exception) {
            locationCallback = null
            setTrackerStatus(TrackerStatus.ERROR, e.message)
            errorInsert(
                "ExploreTracker",
                "Location-Updates Exception: ${e.message}",
                Instant.now().toString(),
                "ERROR"
            )
        }
    }

    private fun handleLocation(context: Context, loc: Location, repo: ExploreRepository) {
        val distanceHome = distanceToHome(loc.latitude, loc.longitude)
        _trackerInfo.value = _trackerInfo.value.copy(
            lastLat = loc.latitude,
            lastLng = loc.longitude,
            lastUpdate = Instant.now().toString(),
            distanceToHomeMeters = distanceHome,
        )

        val activityInfo = _currentActivity.value

        if (loc.accuracy > MAX_ACCURACY_METERS) {
            logEvent(
                context, "REJECT_ACCURACY", activityInfo.mode, activityInfo.confidence,
                "acc=%.1f limit=%.1f".format(loc.accuracy, MAX_ACCURACY_METERS)
            )
            return
        }

        val previous = lastLocation
        val deltaSeconds = if (previous != null) (loc.time - previous.time) / 1000.0 else 0.0
        val distance = previous?.distanceTo(loc) ?: 0f

        val measuredSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else null
        val impliedSpeed = if (deltaSeconds >= MIN_IMPLIED_SPEED_INTERVAL_S) distance / deltaSeconds else null
        val speedSource = if (measuredSpeed != null) "doppler" else "implied"
        val speed = measuredSpeed ?: impliedSpeed

        if (speed != null) {
            val ceiling = if (activityInfo.confidence >= AR_CEILING_CONFIDENCE_FLOOR && !activityInfo.held) {
                modeSpeedCeiling(activityInfo.mode)
            } else {
                UNIVERSAL_SPEED_CEILING_MPS
            }
            if (speed > ceiling) {
                logEvent(
                    context, "REJECT_SPEED", activityInfo.mode, activityInfo.confidence,
                    "v=%.1f quelle=%s ceiling=%.1f dt=%.1f dist=%.1f acc=%.1f".format(
                        speed, speedSource, ceiling, deltaSeconds, distance, loc.accuracy
                    )
                )
                return
            }
        }

        lastLocation = loc
        scope.launch {
            try {
                repo.ingest(
                    RawPoint(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        accuracy = loc.accuracy,
                        speed = if (loc.hasSpeed()) loc.speed else null,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        activityType = _currentActivity.value.mode,
                        activityConfidence = _currentActivity.value.confidence,
                        timestamp = System.currentTimeMillis(),
                        altitude = usableAltitude(loc)
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    fun onServiceStarted(context: Context) {
        val appCtx = context.applicationContext
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serviceRunning = true
        stopBackgroundLocationUpdates(appCtx)
        startLocationUpdates(appCtx, ExploreRepository(appCtx))
        startActivityRecognitionUpdates(appCtx)
        ExploreWorker.schedule(appCtx)
        setTrackerStatus(TrackerStatus.ACTIVE)
        logEvent(appCtx, "TRACK", "START", 0, "FGS")
    }

    fun onServiceStartDenied(context: Context) {
        serviceRunning = false
        isEnabled = false
        setTrackerStatus(TrackerStatus.FGS_NOT_STARTED)
        _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
        errorInsert(
            "ExploreTracker",
            "FGS-Start verweigert (startForeground SecurityException)",
            Instant.now().toString(),
            "ERROR"
        )
        startBackgroundLocationUpdates(context)
    }

    fun onServiceDestroyed(context: Context) {
        val appCtx = context.applicationContext
        val wasEnabled = isEnabled
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serviceRunning = false
        locationCallback?.let {
            try {
                getClient(appCtx).removeLocationUpdates(it)
            } catch (_: Exception) {
            }
        }
        stopActivityRecognitionUpdates(appCtx)
        locationCallback = null
        lastLocation = null
        currentMode = "UNKNOWN"
        currentProfile = requestProfile("UNKNOWN")
        lastConfidentMode = "UNKNOWN"
        lastConfidentAt = 0L
        _currentActivity.value = ExploreActivityInfo()
        if (wasEnabled && !isNightTime() && presence(appCtx) != PRESENCE_HOME) {
            errorInsert(
                "ExploreTracker",
                "FGS unerwartet beendet, Hintergrund-Updates werden aktiviert",
                Instant.now().toString(),
                "LOG"
            )
            startBackgroundLocationUpdates(appCtx)
        } else {
            isEnabled = false
            _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)
            logEvent(appCtx, "TRACK", "STOP", 0, null)
        }
    }

    fun stop(context: Context) {
        val appCtx = context.applicationContext
        isEnabled = false
        _trackerInfo.value = _trackerInfo.value.copy(isEnabled = false)

        locationCallback?.let {
            try {
                getClient(appCtx).removeLocationUpdates(it)
            } catch (_: Exception) {
            }
        }
        locationCallback = null
        lastLocation = null
        currentMode = "UNKNOWN"
        currentProfile = requestProfile("UNKNOWN")
        stopBackgroundLocationUpdates(appCtx)
        stopActivityRecognitionUpdates(appCtx)
        ExploreWorker.cancel(appCtx)
        serviceRunning = false

        try {
            appCtx.stopService(Intent(appCtx, ExploreForegroundService::class.java))
        } catch (_: Exception) {
        }

        if (!isFeatureEnabled(appCtx, FeatureToggle.EXPLORE)) {
            ExploreNightRestartWorker.cancel(appCtx)
            setTrackerStatus(TrackerStatus.DISABLED)
        } else if (isNightTime()) {
            setTrackerStatus(TrackerStatus.PAUSED_NIGHT)
            _trackerInfo.value = _trackerInfo.value.copy(isNight = true)
            ExploreNightRestartWorker.schedule(appCtx)
        } else if (_trackerStatus.value != TrackerStatus.STOPPED_HOME) {
            setTrackerStatus(TrackerStatus.INACTIVE)
        }
    }
}
