package com.tabslify.tabs.exploretab

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.LocalDate

object ExploreConfig {
    private const val PREFS_NAME = "explore_prefs"
    private const val KEY_LAST_REBUILD_DAY = "last_rebuild_day"
    private const val KEY_LAST_STALE_WARN_MS = "last_stale_warn_ms"

    private lateinit var sharedPrefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::sharedPrefs.isInitialized) {
            sharedPrefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun needsRebuild(day: LocalDate): Boolean = prefs().getString(KEY_LAST_REBUILD_DAY, null) != day.toString()

    fun markRebuilt(day: LocalDate) {
        prefs().edit { putString(KEY_LAST_REBUILD_DAY, day.toString()) }
    }

    fun shouldWarnStale(now: Long, minGapMs: Long): Boolean =
        now - prefs().getLong(KEY_LAST_STALE_WARN_MS, 0L) >= minGapMs

    fun markStaleWarned(now: Long) {
        prefs().edit { putLong(KEY_LAST_STALE_WARN_MS, now) }
    }

    private fun prefs(): SharedPreferences {
        if (!::sharedPrefs.isInitialized) {
            throw IllegalStateException("ExploreConfig.init() wurde noch nicht aufgerufen")
        }
        return sharedPrefs
    }
}