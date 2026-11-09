package com.tabslify.widget

import android.content.Context
import androidx.core.content.edit

data class MixWidgetSuggestion(
    val videoId: String,
    val title: String,
    val channel: String,
    val reason: String,
    val thumbnailUrl: String,
    val validUntil: String
)

object MixWidgetStore {

    private const val PREFS = "tabslify_mix_widget"
    private const val KEY_SEED = "mix_seed"
    private const val KEY_REFRESHING = "refreshing"
    private const val KEY_VIDEO_ID = "suggestion_video_id"
    private const val KEY_TITLE = "suggestion_title"
    private const val KEY_CHANNEL = "suggestion_channel"
    private const val KEY_REASON = "suggestion_reason"
    private const val KEY_THUMBNAIL = "suggestion_thumbnail"
    private const val KEY_VALID_UNTIL = "suggestion_valid_until"

    fun seed(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_SEED, 0L)
        if (stored > 0L) return stored
        val now = System.currentTimeMillis()
        prefs.edit { putLong(KEY_SEED, now) }
        return now
    }

    fun bumpSeed(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putLong(KEY_SEED, System.currentTimeMillis()) }
    }

    fun isRefreshing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REFRESHING, false)

    fun setRefreshing(context: Context, refreshing: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_REFRESHING, refreshing) }
    }

    fun saveSuggestion(context: Context, suggestion: MixWidgetSuggestion) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_VIDEO_ID, suggestion.videoId)
            putString(KEY_TITLE, suggestion.title)
            putString(KEY_CHANNEL, suggestion.channel)
            putString(KEY_REASON, suggestion.reason)
            putString(KEY_THUMBNAIL, suggestion.thumbnailUrl)
            putString(KEY_VALID_UNTIL, suggestion.validUntil)
        }
    }

    fun readSuggestion(context: Context): MixWidgetSuggestion? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val videoId = prefs.getString(KEY_VIDEO_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return MixWidgetSuggestion(
            videoId = videoId,
            title = prefs.getString(KEY_TITLE, "").orEmpty(),
            channel = prefs.getString(KEY_CHANNEL, "").orEmpty(),
            reason = prefs.getString(KEY_REASON, "").orEmpty(),
            thumbnailUrl = prefs.getString(KEY_THUMBNAIL, "").orEmpty(),
            validUntil = prefs.getString(KEY_VALID_UNTIL, "").orEmpty()
        )
    }
}
