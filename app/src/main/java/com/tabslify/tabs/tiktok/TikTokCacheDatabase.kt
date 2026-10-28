package com.tabslify.tabs.tiktok

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Database(
    entities = [CachedConversation::class, CachedMessage::class],
    version = 1,
    exportSchema = false
)
abstract class TikTokCacheDatabase : RoomDatabase() {
    abstract fun conversationDao(): TikTokConversationDao
    abstract fun messageDao(): TikTokMessageDao

    companion object {
        @Volatile
        private var INSTANCE: TikTokCacheDatabase? = null

        private const val DATEINAME = "tiktok_cache_db"
        private const val ORDNER = "tiktok"

        fun get(context: Context): TikTokCacheDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: aufraeumen(context.applicationContext).let {
                    val app = context.applicationContext
                    val ordner = File(app.cacheDir, ORDNER)
                    if (!ordner.exists()) ordner.mkdirs()
                    Room.databaseBuilder(
                        app,
                        TikTokCacheDatabase::class.java,
                        File(ordner, DATEINAME).absolutePath
                    ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
                }
            }

        private fun aufraeumen(context: Context) {
            runCatching {
                val alt = context.getDatabasePath(DATEINAME)
                if (alt.exists()) {
                    alt.delete()
                    File(alt.absolutePath + "-wal").delete()
                    File(alt.absolutePath + "-shm").delete()
                }
            }
        }
    }
}
