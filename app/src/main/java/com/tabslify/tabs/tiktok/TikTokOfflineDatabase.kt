package com.tabslify.tabs.tiktok

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OfflineVideo::class],
    version = 1,
    exportSchema = false
)
abstract class TikTokOfflineDatabase : RoomDatabase() {
    abstract fun videoDao(): TikTokOfflineDao

    companion object {
        @Volatile
        private var INSTANCE: TikTokOfflineDatabase? = null

        private const val DATEINAME = "tiktok_offline_db"

        fun get(context: Context): TikTokOfflineDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TikTokOfflineDatabase::class.java,
                    DATEINAME
                ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
            }
    }
}
