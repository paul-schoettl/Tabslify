package com.tabslify.tabs.tiktok

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tiktok_cached_conversations")
data class CachedConversation(
    @PrimaryKey val convId: String,
    val peerId: String,
    val name: String,
    val lastTimestamp: Long,
    val preview: String,
    val unread: Int,
    val streakValue: Int,
    val streakActive: Boolean,
    val streakRestorable: Boolean,
    val updatedAt: Long
)

@Entity(
    tableName = "tiktok_cached_messages",
    indices = [Index(value = ["convId", "timestamp"])]
)
data class CachedMessage(
    @PrimaryKey val key: String,
    val messageId: String,
    val convId: String,
    val convHash: Long,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isOwn: Boolean,
    val mediaUrl: String,
    val mediaKind: String,
    val mediaKey: String,
    val reactions: String = "",
    val mediaStream: String = "",
    val mediaDuration: Long = 0L,
    val mediaWave: String = "",
    val mediaVid: String = ""
)