package com.tabslify.tabs.mediaplayer.song_downloader_backend.domain

import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun downloadTrack(
        spotifyUrl: String,
        confirmWithoutAi: suspend () -> Boolean = { true }
    ): Flow<DownloadState>
}
