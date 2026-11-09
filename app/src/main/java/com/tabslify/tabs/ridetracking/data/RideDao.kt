package com.tabslify.tabs.ridetracking.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: Ride)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateRide(ride: Ride)

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun ride(id: String): Ride?

    @Query("SELECT * FROM rides WHERE id = :id")
    fun rideFlow(id: String): Flow<Ride?>

    @Query("SELECT * FROM rides WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startTime DESC LIMIT 1")
    suspend fun activeRide(): Ride?

    @Query("SELECT * FROM rides WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startTime DESC LIMIT 1")
    fun activeRideFlow(): Flow<Ride?>

    @Query("SELECT * FROM rides WHERE status = 'FINISHED' ORDER BY startTime DESC LIMIT :limit")
    fun recentFinishedFlow(limit: Int): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE status = 'FINISHED' AND startTime >= :from AND startTime < :to ORDER BY startTime ASC")
    fun finishedBetweenFlow(from: Long, to: Long): Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE status = 'FINISHED' AND startTime >= :from AND startTime < :to ORDER BY startTime ASC")
    suspend fun finishedBetween(from: Long, to: Long): List<Ride>

    @Query("SELECT * FROM rides WHERE status = 'RECORDING' AND heartbeatAt < :cutoff ORDER BY startTime ASC")
    suspend fun staleRecordings(cutoff: Long): List<Ride>

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RidePoint>)

    @Query("SELECT * FROM ride_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    suspend fun pointsForRide(rideId: String): List<RidePoint>

    @Query("SELECT * FROM ride_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    fun pointsFlowForRide(rideId: String): Flow<List<RidePoint>>

    @Query("SELECT COUNT(*) FROM ride_points WHERE rideId = :rideId")
    suspend fun pointCount(rideId: String): Int

    @Query("DELETE FROM ride_points WHERE rideId = :rideId")
    suspend fun deletePointsForRide(rideId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<RidePhoto>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: RidePhoto)

    @Query("SELECT * FROM ride_photos WHERE rideId = :rideId ORDER BY timestamp ASC")
    suspend fun photosForRide(rideId: String): List<RidePhoto>

    @Query("SELECT * FROM ride_photos WHERE rideId = :rideId ORDER BY timestamp ASC")
    fun photosFlowForRide(rideId: String): Flow<List<RidePhoto>>

    @Query("DELETE FROM ride_photos WHERE id = :id")
    suspend fun deletePhoto(id: String)

    @Query("DELETE FROM ride_photos WHERE rideId = :rideId")
    suspend fun deletePhotosForRide(rideId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<RideSplit>)

    @Query("SELECT * FROM ride_splits WHERE rideId = :rideId ORDER BY `index` ASC")
    suspend fun splitsForRide(rideId: String): List<RideSplit>

    @Query("SELECT * FROM ride_splits WHERE rideId = :rideId ORDER BY `index` ASC")
    fun splitsFlowForRide(rideId: String): Flow<List<RideSplit>>

    @Query("DELETE FROM ride_splits WHERE rideId = :rideId")
    suspend fun deleteSplitsForRide(rideId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: RideEvent)

    @Query("SELECT * FROM ride_events WHERE rideId = :rideId ORDER BY timestamp ASC")
    suspend fun eventsForRide(rideId: String): List<RideEvent>

    @Query("SELECT * FROM ride_events WHERE rideId = :rideId ORDER BY timestamp ASC")
    fun eventsFlowForRide(rideId: String): Flow<List<RideEvent>>

    @Query("DELETE FROM ride_events WHERE rideId = :rideId")
    suspend fun deleteEventsForRide(rideId: String)
}
