package com.tabslify.tabs.tiktok

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TikTokOfflineDao {
    @Query("SELECT * FROM tiktok_offline_videos ORDER BY addedAt DESC")
    suspend fun all(): List<OfflineVideo>

    @Query("SELECT * FROM tiktok_offline_videos WHERE videoId = :id")
    suspend fun get(id: String): OfflineVideo?

    @Query("SELECT * FROM tiktok_offline_videos WHERE state = :state ORDER BY addedAt ASC")
    suspend fun byState(state: String): List<OfflineVideo>

    @Query("SELECT * FROM tiktok_offline_videos ORDER BY lastPlayedAt ASC, addedAt ASC")
    suspend fun oldestFirst(): List<OfflineVideo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: OfflineVideo)

    @Query("DELETE FROM tiktok_offline_videos WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tiktok_offline_videos")
    suspend fun deleteAll()
}
