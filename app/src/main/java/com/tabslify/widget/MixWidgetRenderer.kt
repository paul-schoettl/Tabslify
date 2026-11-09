package com.tabslify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.tabslify.R
import com.tabslify.core.activities.MainActivity
import com.tabslify.quiethoursnotificationhelper.getTodayKey
import com.tabslify.quiethoursnotificationhelper.loadAllAiResponses
import java.io.File
import java.time.LocalTime
import kotlin.random.Random

private data class MixCard(val title: String, val subtitle: String)

private val WHITESPACE = Regex("\\s+")

object MixWidgetRenderer {

    fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, TabslifyMixWidget::class.java))
    }

    fun push(context: Context) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(context).updateAppWidget(ids, render(context))
    }

    private fun render(context: Context): RemoteViews {
        val rng = Random(MixWidgetStore.seed(context))
        val refreshing = MixWidgetStore.isRefreshing(context)
        val views = RemoteViews(context.packageName, R.layout.tabslify_mix_widget)

        views.setTextViewText(R.id.mix_widget_greeting, greeting(context, rng))
        views.setTextViewText(
            R.id.mix_widget_subtitle,
            context.getString(
                if (refreshing) R.string.mix_widget_loading else R.string.mix_widget_subtitle_default
            )
        )

        val music = musicCard(context, rng)
        views.setTextViewText(R.id.mix_widget_music_title, music.title)
        views.setTextViewText(R.id.mix_widget_music_subtitle, music.subtitle)

        val ai = aiCard(context, rng)
        views.setTextViewText(R.id.mix_widget_ai_title, ai.title)
        views.setTextViewText(R.id.mix_widget_ai_subtitle, ai.subtitle)

        val youtube = youtubeCard(context)
        views.setTextViewText(R.id.mix_widget_yt_title, youtube.title)
        views.setTextViewText(R.id.mix_widget_yt_subtitle, youtube.subtitle)

        views.setViewVisibility(
            R.id.mix_widget_progress,
            if (refreshing) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.mix_widget_shuffle,
            if (refreshing) View.GONE else View.VISIBLE
        )

        val shuffle = shufflePendingIntent(context)
        views.setOnClickPendingIntent(R.id.mix_widget_root, shuffle)
        views.setOnClickPendingIntent(R.id.mix_widget_shuffle, shuffle)
        views.setOnClickPendingIntent(
            R.id.mix_widget_card_music,
            openPendingIntent(context, 211, "mediaplayer")
        )
        views.setOnClickPendingIntent(
            R.id.mix_widget_card_ai,
            openPendingIntent(context, 212, "aitab")
        )
        views.setOnClickPendingIntent(
            R.id.mix_widget_card_youtube,
            openPendingIntent(context, 213, "suggestion")
        )

        return views
    }

    private fun greeting(context: Context, rng: Random): String {
        val hour = LocalTime.now().hour
        val arrayRes = when (hour) {
            in 5..10 -> R.array.mix_widget_greetings_morning
            in 11..16 -> R.array.mix_widget_greetings_day
            in 17..21 -> R.array.mix_widget_greetings_evening
            else -> R.array.mix_widget_greetings_night
        }
        val options = context.resources.getStringArray(arrayRes)
        return if (options.isEmpty()) {
            context.getString(R.string.mix_widget_greeting_default)
        } else {
            options.random(rng)
        }
    }

    private fun musicCard(context: Context, rng: Random): MixCard {
        val musicPrefs = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
        val mode = musicPrefs.getString("current_mode", "music") ?: "music"

        if (mode == "podcast") {
            val path = context.getSharedPreferences("podcast_player_prefs", Context.MODE_PRIVATE)
                .getString("current_podcast_path", null)
            if (!path.isNullOrBlank()) {
                val title = File(path).nameWithoutExtension
                    .ifBlank { context.getString(R.string.mix_widget_music_fallback) }
                return MixCard(title, context.getString(R.string.mix_widget_music_podcast))
            }
        }

        val song = musicPrefs.getString("current_song_name", "")?.trim().orEmpty()
        if (song.isNotEmpty()) {
            val subtitle = if (musicPrefs.getBoolean("is_playing", false)) {
                R.string.mix_widget_music_playing
            } else {
                R.string.mix_widget_music_last
            }
            return MixCard(song, context.getString(subtitle))
        }

        val favorites = favoriteNames(context)
        if (favorites.isNotEmpty()) {
            return MixCard(
                favorites.random(rng),
                context.getString(R.string.mix_widget_music_favorite)
            )
        }

        return MixCard(
            context.getString(R.string.mix_widget_music_fallback),
            context.getString(R.string.mix_widget_music_hint)
        )
    }

    private fun aiCard(context: Context, rng: Random): MixCard {
        val entries = loadAllAiResponses(context).filter { it.text.isNotBlank() }
        if (entries.isEmpty()) {
            return MixCard(
                context.getString(R.string.mix_widget_ai_empty),
                context.getString(R.string.mix_widget_ai_hint)
            )
        }
        val entry = entries.random(rng)
        val snippet = clean(entry.text).take(150).trimEnd()
        val label = if (entry.dateKey == getTodayKey()) {
            context.getString(R.string.mix_widget_today)
        } else {
            entry.dateKey
        }
        return MixCard(
            snippet.ifBlank { context.getString(R.string.mix_widget_ai_empty) },
            label.ifBlank { context.getString(R.string.mix_widget_ai_hint) }
        )
    }

    private fun youtubeCard(context: Context): MixCard {
        val suggestion = MixWidgetStore.readSuggestion(context)
            ?: return MixCard(
                context.getString(R.string.mix_widget_yt_empty),
                context.getString(R.string.mix_widget_yt_hint)
            )
        val title = clean(suggestion.title).ifBlank {
            context.getString(R.string.mix_widget_yt_empty)
        }
        val channel = clean(suggestion.channel)
        return MixCard(
            title,
            channel.ifBlank { context.getString(R.string.mix_widget_yt_hint) }
        )
    }

    private fun favoriteNames(context: Context): List<String> {
        val prefs = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
        val paths: Set<String> = try {
            prefs.getStringSet("favorite_songs", emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            prefs.getString("favorite_songs", null)
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        }
        return paths.mapNotNull { path ->
            path.substringAfterLast('/')
                .substringBeforeLast('.')
                .trim()
                .takeIf { it.isNotEmpty() }
        }
    }

    private fun clean(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun shufflePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            201,
            Intent(context, TabslifyMixWidget::class.java)
                .setAction(TabslifyMixWidget.ACTION_SHUFFLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openPendingIntent(
        context: Context,
        requestCode: Int,
        target: String
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.tabslify.widget.OPEN_$target"
            putExtra(TabslifyMixWidget.EXTRA_WIDGET_TARGET, target)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
