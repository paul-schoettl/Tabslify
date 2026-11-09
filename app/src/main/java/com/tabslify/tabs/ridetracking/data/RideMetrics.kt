package com.tabslify.tabs.ridetracking.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RideMetrics {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val ELEVATION_THRESHOLD_M = 3.0
    private const val ELEVATION_WINDOW = 5

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun elevationGainLoss(points: List<RidePoint>): Pair<Double, Double> {
        val altitudes = smoothAltitudes(points.mapNotNull { it.altitude })
        if (altitudes.size < 2) return 0.0 to 0.0
        var gain = 0.0
        var loss = 0.0
        var last = altitudes.first()
        for (index in 1 until altitudes.size) {
            val delta = altitudes[index] - last
            if (abs(delta) >= ELEVATION_THRESHOLD_M) {
                if (delta > 0) gain += delta else loss += -delta
                last = altitudes[index]
            }
        }
        return gain to loss
    }

    fun smoothAltitudes(values: List<Double>): List<Double> {
        if (values.size < ELEVATION_WINDOW) return values
        val half = ELEVATION_WINDOW / 2
        return values.indices.map { index ->
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(values.size - 1)
            values.subList(from, to + 1).sorted()[((to - from) / 2)]
        }
    }

    fun computeSplits(
        points: List<RidePoint>,
        rideId: String,
        intervalMeters: Double = 1000.0
    ): List<RideSplit> {
        if (points.size < 2) return emptyList()
        val sorted = points.sortedBy { it.timestamp }
        val splits = mutableListOf<RideSplit>()
        var splitStart = sorted.first()
        var splitStartDistance = splitStart.distanceFromStartM
        var nextBoundary = splitStartDistance + intervalMeters
        var splitIndex = 0

        for (index in 1 until sorted.size) {
            val point = sorted[index]
            if (point.distanceFromStartM >= nextBoundary) {
                val distance = point.distanceFromStartM - splitStartDistance
                val duration = (point.timestamp - splitStart.timestamp).coerceAtLeast(0L)
                val gain = splitElevationGain(sorted, splitStart.timestamp, point.timestamp)
                splits += RideSplit(
                    rideId = rideId,
                    index = splitIndex,
                    startTime = splitStart.timestamp,
                    endTime = point.timestamp,
                    distanceM = distance,
                    durationMs = duration,
                    elevationGainM = gain,
                    avgSpeedMps = if (duration > 0) distance / (duration / 1000.0) else 0.0
                )
                splitIndex += 1
                splitStart = point
                splitStartDistance = point.distanceFromStartM
                nextBoundary = splitStartDistance + intervalMeters
            }
        }

        val last = sorted.last()
        val remaining = last.distanceFromStartM - splitStartDistance
        if (remaining >= 50.0) {
            val duration = (last.timestamp - splitStart.timestamp).coerceAtLeast(0L)
            splits += RideSplit(
                rideId = rideId,
                index = splitIndex,
                startTime = splitStart.timestamp,
                endTime = last.timestamp,
                distanceM = remaining,
                durationMs = duration,
                elevationGainM = splitElevationGain(sorted, splitStart.timestamp, last.timestamp),
                avgSpeedMps = if (duration > 0) remaining / (duration / 1000.0) else 0.0
            )
        }
        return splits
    }

    private fun splitElevationGain(points: List<RidePoint>, from: Long, to: Long): Double {
        val slice = points.filter { it.timestamp in from..to }.mapNotNull { it.altitude }
        if (slice.size < 2) return 0.0
        val smoothed = smoothAltitudes(slice)
        var gain = 0.0
        var last = smoothed.first()
        for (index in 1 until smoothed.size) {
            val delta = smoothed[index] - last
            if (delta >= ELEVATION_THRESHOLD_M) {
                gain += delta
                last = smoothed[index]
            } else if (delta <= -ELEVATION_THRESHOLD_M) {
                last = smoothed[index]
            }
        }
        return gain
    }

    fun downsample(points: List<RidePoint>, maxPoints: Int): List<RidePoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { index ->
            points[(index * step).toInt().coerceIn(points.indices)]
        }.distinctBy { it.timestamp }
    }
}
