package com.tabslify.tabs.ridetracking.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class RideRepository(context: Context) {

    private val dao = RideDatabase.get(context).dao()

    fun activeRideFlow(): Flow<Ride?> = dao.activeRideFlow()

    suspend fun activeRide(): Ride? = dao.activeRide()

    fun rideFlow(id: String): Flow<Ride?> = dao.rideFlow(id)

    suspend fun ride(id: String): Ride? = dao.ride(id)

    fun recentFinishedFlow(limit: Int = 5): Flow<List<Ride>> = dao.recentFinishedFlow(limit)

    fun finishedBetweenFlow(from: Long, to: Long): Flow<List<Ride>> =
        dao.finishedBetweenFlow(from, to)

    suspend fun finishedBetween(from: Long, to: Long): List<Ride> =
        dao.finishedBetween(from, to)

    suspend fun staleRecordings(cutoff: Long): List<Ride> = dao.staleRecordings(cutoff)

    suspend fun createRide(ride: Ride) = dao.insertRide(ride)

    suspend fun updateRide(ride: Ride) = dao.updateRide(ride)

    suspend fun addPoints(points: List<RidePoint>) {
        if (points.isNotEmpty()) dao.insertPoints(points)
    }

    suspend fun pointsForRide(rideId: String): List<RidePoint> = dao.pointsForRide(rideId)

    fun pointsFlowForRide(rideId: String): Flow<List<RidePoint>> = dao.pointsFlowForRide(rideId)

    suspend fun pointCount(rideId: String): Int = dao.pointCount(rideId)

    suspend fun addEvent(event: RideEvent) = dao.insertEvent(event)

    suspend fun eventsForRide(rideId: String): List<RideEvent> = dao.eventsForRide(rideId)

    fun eventsFlowForRide(rideId: String): Flow<List<RideEvent>> = dao.eventsFlowForRide(rideId)

    suspend fun addPhoto(photo: RidePhoto) {
        dao.insertPhoto(photo)
        refreshPhotoCount(photo.rideId)
    }

    suspend fun photosForRide(rideId: String): List<RidePhoto> = dao.photosForRide(rideId)

    fun photosFlowForRide(rideId: String): Flow<List<RidePhoto>> = dao.photosFlowForRide(rideId)

    suspend fun deletePhoto(photo: RidePhoto) {
        dao.deletePhoto(photo.id)
        refreshPhotoCount(photo.rideId)
    }

    suspend fun splitsForRide(rideId: String): List<RideSplit> = dao.splitsForRide(rideId)

    fun splitsFlowForRide(rideId: String): Flow<List<RideSplit>> = dao.splitsFlowForRide(rideId)

    suspend fun finishRide(
        rideId: String,
        endTime: Long,
        movingTimeMs: Long,
        pausedTimeMs: Long,
        distanceMeters: Double,
        elevationGainM: Double,
        elevationLossM: Double,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
        pointCount: Int,
        splits: List<RideSplit>
    ) {
        val existing = dao.ride(rideId) ?: return
        val finished = existing.copy(
            status = RIDE_STATUS_FINISHED,
            endTime = endTime,
            movingTimeMs = movingTimeMs,
            pausedTimeMs = pausedTimeMs,
            distanceMeters = distanceMeters,
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps,
            pointCount = pointCount,
            calories = estimateCalories(distanceMeters, movingTimeMs, existing.mode),
            heartbeatAt = endTime,
            updatedAt = endTime
        )
        dao.updateRide(finished)
        dao.deleteSplitsForRide(rideId)
        dao.insertSplits(splits)
    }

    suspend fun markStaleRecordings(cutoff: Long): List<Ride> {
        val stale = dao.staleRecordings(cutoff)
        stale.forEach { ride ->
            val lastPoint = dao.pointsForRide(ride.id).lastOrNull()
            val end = lastPoint?.timestamp ?: ride.startTime
            dao.updateRide(
                ride.copy(
                    status = RIDE_STATUS_FINISHED,
                    endTime = end,
                    heartbeatAt = cutoff,
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.insertEvent(
                RideEvent(
                    rideId = ride.id,
                    timestamp = end,
                    type = RIDE_EVENT_GPS_GAP,
                    detail = "recovered"
                )
            )
        }
        return stale
    }

    suspend fun deleteRideCascade(rideId: String) {
        dao.deletePointsForRide(rideId)
        dao.deletePhotosForRide(rideId)
        dao.deleteSplitsForRide(rideId)
        dao.deleteEventsForRide(rideId)
        dao.deleteRide(rideId)
    }

    private suspend fun refreshPhotoCount(rideId: String) {
        val ride = dao.ride(rideId) ?: return
        val count = dao.photosForRide(rideId).size
        dao.updateRide(ride.copy(photoCount = count, updatedAt = System.currentTimeMillis()))
    }

    private fun estimateCalories(
        distanceMeters: Double,
        movingTimeMs: Long,
        mode: String
    ): Double {
        val hours = movingTimeMs / 3_600_000.0
        if (hours <= 0.0) return 0.0
        val speedKmh = (distanceMeters / 1000.0) / hours
        val met = when {
            mode == RIDE_MODE_EBIKE -> 5.0
            speedKmh < 16.0 -> 6.0
            speedKmh < 20.0 -> 8.0
            speedKmh < 25.0 -> 10.0
            else -> 12.0
        }
        return met * 75.0 * hours
    }
}
