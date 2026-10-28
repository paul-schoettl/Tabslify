package com.tabslify.tabs.tiktok

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tiktok_offline_videos",
    indices = [Index(value = ["state"]), Index(value = ["lastPlayedAt"])]
)
data class OfflineVideo(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val streamUrl: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val fileName: String,
    val state: String,
    val error: String,
    val positionMs: Long,
    val addedAt: Long,
    val lastPlayedAt: Long
)

data class OfflineAntrag(
    val videoId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationMs: Long,
    val urls: List<String>
)
