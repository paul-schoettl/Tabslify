package com.tabslify.tabs.tiktok

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TikTokConversationDao {
    @Query("SELECT * FROM tiktok_cached_conversations ORDER BY lastTimestamp DESC")
    suspend fun all(): List<CachedConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CachedConversation>)

    @Query("DELETE FROM tiktok_cached_conversations WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM tiktok_cached_conversations")
    suspend fun deleteAll()
}

@Dao
interface TikTokMessageDao {
    @Query("SELECT * FROM tiktok_cached_messages ORDER BY timestamp ASC")
    suspend fun all(): List<CachedMessage>

    @Query("SELECT * FROM tiktok_cached_messages WHERE convId = :convId ORDER BY timestamp ASC")
    suspend fun forConversation(convId: String): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<CachedMessage>)

    @Query("DELETE FROM tiktok_cached_messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM tiktok_cached_messages WHERE convId = :convId AND key NOT IN (:keep)")
    suspend fun deleteStale(convId: String, keep: List<String>)

    @Query("DELETE FROM tiktok_cached_messages WHERE convId = :convId")
    suspend fun deleteByConversation(convId: String)

    @Query("DELETE FROM tiktok_cached_messages WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM tiktok_cached_messages WHERE convId = :convId AND timestamp >= :abUnd AND key NOT IN (:keep)")
    suspend fun deleteStaleImFenster(convId: String, abUnd: Long, keep: List<String>)

    @Query("DELETE FROM tiktok_cached_messages")
    suspend fun deleteAll()
}