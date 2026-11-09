package com.tabslify.tabs.ridetracking.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val RIDE_SOURCE_RECORDED = "RECORDED"

const val RIDE_STATUS_RECORDING = "RECORDING"
const val RIDE_STATUS_PAUSED = "PAUSED"
const val RIDE_STATUS_FINISHED = "FINISHED"
const val RIDE_STATUS_DISCARDED = "DISCARDED"

const val RIDE_MODE_BIKE = "ON_BICYCLE"
const val RIDE_MODE_EBIKE = "ON_EBIKE"

const val RIDE_EVENT_START = "START"
const val RIDE_EVENT_PAUSE = "PAUSE"
const val RIDE_EVENT_RESUME = "RESUME"
const val RIDE_EVENT_AUTO_PAUSE = "AUTO_PAUSE"
const val RIDE_EVENT_AUTO_RESUME = "AUTO_RESUME"
const val RIDE_EVENT_STOP = "STOP"
const val RIDE_EVENT_GPS_GAP = "GPS_GAP"
const val RIDE_EVENT_MODE_CHANGE = "MODE_CHANGE"

@Entity(
    tableName = "rides",
    indices = [Index(value = ["startTime"]), Index(value = ["status"])]
)
data class Ride(
    @PrimaryKey val id: String,
    val source: String = RIDE_SOURCE_RECORDED,
    val mode: String = RIDE_MODE_BIKE,
    val title: String? = null,
    val notes: String? = null,
    val status: String = RIDE_STATUS_RECORDING,
    val startTime: Long,
    val endTime: Long? = null,
    val movingTimeMs: Long = 0L,
    val pausedTimeMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val calories: Double? = null,
    val pointCount: Int = 0,
    val photoCount: Int = 0,
    val heartbeatAt: Long = 0L,
    val explorePublished: Boolean = false,
    val createdAt: Long = startTime,
    val updatedAt: Long = startTime
)

@Entity(
    tableName = "ride_points",
    indices = [
        Index(value = ["rideId", "timestamp"]),
        Index(value = ["rideId", "distanceFromStartM"])
    ]
)
data class RidePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: String,
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

@Entity(
    tableName = "ride_photos",
    indices = [Index(value = ["rideId", "timestamp"])]
)
data class RidePhoto(
    @PrimaryKey val id: String,
    val rideId: String,
    val timestamp: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val path: String,
    val caption: String? = null,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val routePointIndex: Int = -1,
    val trackOffsetM: Double? = null,
    val createdAt: Long = timestamp
)

@Entity(
    tableName = "ride_splits",
    indices = [Index(value = ["rideId", "index"])]
)
data class RideSplit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: String,
    val index: Int,
    val startTime: Long,
    val endTime: Long,
    val distanceM: Double,
    val durationMs: Long,
    val elevationGainM: Double = 0.0,
    val avgSpeedMps: Double = 0.0
)

@Entity(
    tableName = "ride_events",
    indices = [Index(value = ["rideId", "timestamp"])]
)
data class RideEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: String,
    val timestamp: Long,
    val type: String,
    val detail: String? = null
)
