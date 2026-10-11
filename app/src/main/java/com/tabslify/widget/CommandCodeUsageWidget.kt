package com.tabslify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tabslify.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class CommandCodeUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val usage = CommandCodeUsageRepository.load(context)
                val views = buildViews(context, usage)
                ids.forEach { manager.updateAppWidget(it, views) }
            } catch (error: Exception) {
                val views = buildErrorViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CommandCodeUsageWidget::class.java))
            if (ids.isNotEmpty()) onUpdate(context, manager, ids)
            return
        }
        super.onReceive(context, intent)
    }

    private fun buildViews(context: Context, usage: CommandCodeUsage): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.commandcode_usage_widget)
        views.setOnClickPendingIntent(R.id.cc_widget_settings, configPendingIntent(context))
        views.setOnClickPendingIntent(R.id.cc_widget_root, refreshPendingIntent(context))

        val usedText = String.format(Locale.US, "\$%.2f", usage.used)
        val capText = String.format(Locale.US, "\$%.2f", usage.cap)
        val pctText = String.format(Locale.US, "%.1f%%", usage.pct)
        val paceText = String.format(Locale.US, "%.1f%%", usage.pacePct)
        val diffText = String.format(Locale.US, "%.1f%%", kotlin.math.abs(usage.pct - usage.pacePct))

        val title = if (usage.planId.isNotEmpty()) {
            "${context.getString(R.string.commandcode_widget_title)} · ${usage.planId}"
        } else {
            context.getString(R.string.commandcode_widget_title)
        }
        val paceLine = context.getString(R.string.commandcode_widget_pace_max, paceText) + " · " +
            if (usage.overPace) {
                context.getString(R.string.commandcode_widget_over, diffText)
            } else {
                context.getString(R.string.commandcode_widget_under, diffText)
            }
        val resetLine = if (usage.periodEnd > 0L) {
            val remaining = formatRemaining(usage.periodEnd - System.currentTimeMillis())
            val base = context.getString(R.string.commandcode_widget_reset, remaining)
            if (usage.offlineDays > 0) {
                base + " · " + context.getString(R.string.commandcode_widget_offline, usage.offlineDays)
            } else {
                base
            }
        } else {
            ""
        }

        views.setTextViewText(R.id.cc_widget_title, title)
        views.setImageViewBitmap(R.id.cc_widget_bar, CommandCodeBarRenderer.render(usage.pct, usage.pacePct))
        views.setTextViewText(
            R.id.cc_widget_amount,
            context.getString(R.string.commandcode_widget_amount, usedText, capText, pctText)
        )
        views.setTextViewText(R.id.cc_widget_pace, paceLine)
        views.setTextViewText(R.id.cc_widget_reset, resetLine)
        return views
    }

    private fun buildErrorViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.commandcode_usage_widget)
        val hasCookie = CommandCodeUsageRepository.readCookie(context).isNotEmpty()
        val target = if (hasCookie) refreshPendingIntent(context) else configPendingIntent(context)
        views.setOnClickPendingIntent(R.id.cc_widget_root, target)
        views.setOnClickPendingIntent(R.id.cc_widget_settings, configPendingIntent(context))
        views.setTextViewText(R.id.cc_widget_title, context.getString(R.string.commandcode_widget_title))
        views.setImageViewBitmap(R.id.cc_widget_bar, CommandCodeBarRenderer.render(0.0, 0.0))
        views.setTextViewText(
            R.id.cc_widget_amount,
            context.getString(
                if (hasCookie) R.string.commandcode_widget_error else R.string.commandcode_widget_no_cookie
            )
        )
        views.setTextViewText(R.id.cc_widget_pace, "")
        views.setTextViewText(R.id.cc_widget_reset, "")
        return views
    }

    private fun refreshPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CommandCodeUsageWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun configPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, CommandCodeWidgetConfigActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun formatRemaining(millis: Long): String {
        if (millis <= 0L) return "0h"
        val totalMinutes = millis / 60_000L
        val days = totalMinutes / (60L * 24L)
        val hours = (totalMinutes % (60L * 24L)) / 60L
        return if (days > 0L) "${days}d ${hours}h" else "${hours}h"
    }

    companion object {
        const val ACTION_REFRESH = "com.tabslify.widget.COMMANDCODE_REFRESH"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CommandCodeUsageWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, CommandCodeUsageWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
