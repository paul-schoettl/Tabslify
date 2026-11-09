package com.tabslify.tabs.ridetracking.data

import kotlin.math.max

data class RideFilterResult(
    val accepted: Boolean,
    val distanceMeters: Double,
    val impliedSpeedMps: Double?,
    val reason: String? = null
)

class RidePointFilter(
    private val maxAccuracyMeters: Float = 50f,
    private val maxSpeedMps: Double = 25.0,
    private val maxJumpSpeedMps: Double = 30.0
) {
    fun evaluate(previous: TrackPoint?, candidate: TrackPoint): RideFilterResult {
        if (candidate.accuracy > maxAccuracyMeters) {
            return RideFilterResult(false, 0.0, null, "accuracy=${candidate.accuracy}")
        }
        if (previous == null) {
            return RideFilterResult(true, 0.0, candidate.speedMps)
        }
        val deltaSeconds = (candidate.timestamp - previous.timestamp) / 1000.0
        if (deltaSeconds <= 0.0) {
            return RideFilterResult(false, 0.0, null, "timestamp")
        }
        val distance = RideMetrics.distanceMeters(
            previous.lat,
            previous.lon,
            candidate.lat,
            candidate.lon
        )
        val implied = distance / deltaSeconds
        val measured = candidate.speedMps
        val effective = when {
            measured != null && measured >= 0.0 -> max(measured, implied)
            else -> implied
        }
        if (effective > maxJumpSpeedMps) {
            return RideFilterResult(false, distance, implied, "jump")
        }
        if (effective > maxSpeedMps) {
            return RideFilterResult(false, distance, implied, "speed")
        }
        return RideFilterResult(true, distance, implied)
    }
}
