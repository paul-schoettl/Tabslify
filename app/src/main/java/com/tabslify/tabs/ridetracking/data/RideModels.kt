package com.tabslify.tabs.ridetracking.data

data class TrackPoint(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val verticalAccuracy: Float? = null,
    val accuracy: Float = 0f,
    val speedMps: Double? = null,
    val bearing: Float? = null,
    val distanceFromStartM: Double = 0.0,
    val source: String = "FUSED"
)

data class RideDetail(
    val ride: Ride,
    val points: List<RidePoint>,
    val splits: List<RideSplit>,
    val photos: List<RidePhoto> = emptyList()
)

object RideStatus {
    const val IDLE = "IDLE"
}

data class RideRecorderState(
    val status: String = RideStatus.IDLE,
    val rideId: String? = null,
    val mode: String = RIDE_MODE_BIKE,
    val distanceMeters: Double = 0.0,
    val totalMs: Long = 0L,
    val movingMs: Long = 0L,
    val pausedMs: Long = 0L,
    val currentSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val accuracy: Float? = null,
    val gpsWeak: Boolean = false,
    val autoPaused: Boolean = false,
    val manualPaused: Boolean = false,
    val pointCount: Int = 0,
    val batteryProfile: String = BATTERY_PROFILE_BALANCED,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val finishedRideId: String? = null,
    val error: String? = null
) {
    val active: Boolean
        get() = status == RIDE_STATUS_RECORDING || status == RIDE_STATUS_PAUSED

    val recording: Boolean
        get() = status == RIDE_STATUS_RECORDING
}

const val BATTERY_PROFILE_HIGH = "high_accuracy"
const val BATTERY_PROFILE_BALANCED = "balanced"
const val BATTERY_PROFILE_SAVER = "saver"
const val BATTERY_PROFILE_PAUSED = "paused"
