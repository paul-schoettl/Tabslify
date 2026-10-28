package com.tabslify.spotifydownloader_own.domain

import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun downloadTrack(
        spotifyUrl: String,
        confirmWithoutAi: suspend () -> Boolean = { true }
    ): Flow<DownloadState>
}
