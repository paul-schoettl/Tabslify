package com.tabslify.tabs.ridetracking.data

import android.content.Context
import com.tabslify.tabs.exploretab.dayBounds
import com.tabslify.tabs.fitnesstab.data.BIKE_SOURCE_RECORDED
import com.tabslify.tabs.fitnesstab.data.BikeRide
import com.tabslify.tabs.fitnesstab.data.BikeRoutePoint
import com.tabslify.tabs.fitnesstab.data.EXERCISE_ID_CYCLING
import com.tabslify.tabs.fitnesstab.data.ExploreActivityBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object RideBikeAdapter {

    suspend fun ridesForLastDays(
        context: Context,
        days: Int,
        calorieFactor: Float
    ): List<BikeRide> = withContext(Dispatchers.Default) {
        val repo = RideRepository(context.applicationContext)
        val today = LocalDate.now()
        val rides = mutableListOf<BikeRide>()
        for (offset in 0 until days) {
            val day = today.minusDays(offset.toLong())
            val (dayStart, dayEnd) = dayBounds(day)
            repo.finishedBetween(dayStart, dayEnd).forEach { ride ->
                val points = runCatching { repo.pointsForRide(ride.id) }.getOrDefault(emptyList())
                rides += toBikeRide(ride, points, calorieFactor)
            }
        }
        rides.sortedByDescending { it.startTime }
    }

    fun withoutOverlappingAuto(auto: List<BikeRide>, recorded: List<BikeRide>): List<BikeRide> {
        if (recorded.isEmpty()) return auto
        return auto.filterNot { ride ->
            ride.source == BIKE_SOURCE_RECORDED || recorded.any { recordedRide ->
                ride.startTime < recordedRide.endTime && recordedRide.startTime < ride.endTime
            }
        }
    }

    private fun toBikeRide(
        ride: Ride,
        points: List<RidePoint>,
        calorieFactor: Float
    ): BikeRide {
        val end = ride.endTime ?: ride.startTime
        val km = (ride.distanceMeters / 1000.0).toFloat()
        val movingMs = ride.movingTimeMs.takeIf { it > 0L } ?: (end - ride.startTime)
        val hours = movingMs / 3_600_000f
        val avgKmh = if (hours > 0f) km / hours else (ride.avgSpeedMps * 3.6).toFloat()
        val calories = ride.calories?.toFloat() ?: ExploreActivityBridge.caloriesFor(
            exerciseId = EXERCISE_ID_CYCLING,
            distanceKm = km,
            durationMs = movingMs,
            mode = ride.mode,
            calorieFactor = calorieFactor
        )
        return BikeRide(
            rideId = "${RideSessionAdapter.RIDE_SESSION_PREFIX}${ride.id}",
            mode = ride.mode,
            autoMode = ride.mode,
            overridden = false,
            classifierScore = 0.0,
            classifierDecided = true,
            startTime = ride.startTime,
            endTime = end,
            distanceKm = km,
            movingSeconds = (movingMs / 1000L).toInt(),
            avgSpeedKmh = avgKmh,
            maxSpeedKmh = (ride.maxSpeedMps * 3.6).toFloat(),
            elevationGainM = ride.elevationGainM.toFloat(),
            elevationLossM = ride.elevationLossM.toFloat(),
            calories = calories,
            route = RideMetrics.downsample(points, 400).map { point ->
                BikeRoutePoint(
                    lat = point.lat,
                    lon = point.lon,
                    timestamp = point.timestamp,
                    speedKmh = ((point.speedMps ?: 0.0) * 3.6).toFloat(),
                    altitude = point.altitude
                )
            },
            source = BIKE_SOURCE_RECORDED,
            title = ride.title.orEmpty(),
            photoCount = ride.photoCount
        )
    }
}
