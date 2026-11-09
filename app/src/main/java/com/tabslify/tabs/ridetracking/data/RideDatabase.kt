package com.tabslify.tabs.ridetracking.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Ride::class,
        RidePoint::class,
        RidePhoto::class,
        RideSplit::class,
        RideEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RideDatabase : RoomDatabase() {
    abstract fun dao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        fun get(context: Context): RideDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ride_db"
                ).build().also { INSTANCE = it }
            }
    }
}
