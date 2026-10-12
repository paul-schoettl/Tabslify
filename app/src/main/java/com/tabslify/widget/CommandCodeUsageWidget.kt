package com.tabslify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.tabslify.R
import com.tabslify.core.functions.PcCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private const val TAG = "CommandCodeWidget"

class CommandCodeUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { HandyLampClient.refresh(context) }
                val views = try {
                    buildViews(context, CommandCodeUsageRepository.load(context))
                } catch (error: CookieExpiredException) {
                    Log.w(TAG, "Cookie abgelaufen: ${error.message}")
                    buildErrorViews(context, ErrorState.EXPIRED)
                } catch (error: CommandCodeBlockedException) {
                    Log.w(TAG, "Server blockiert die Anfrage: ${error.message}")
                    buildErrorViews(context, ErrorState.BLOCKED)
                } catch (error: Exception) {
                    Log.e(TAG, "Abruf fehlgeschlagen", error)
                    buildErrorViews(context, ErrorState.UNKNOWN)
                }
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private enum class ErrorState { EXPIRED, BLOCKED, UNKNOWN }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, CommandCodeUsageWidget::class.java))
                if (ids.isNotEmpty()) onUpdate(context, manager, ids)
            }
            ACTION_LAMP_TOGGLE -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        toggleLamp(context)
                        updateLampIcon(context)
                    } catch (error: Exception) {
                        Log.e(TAG, "Lampen-Befehl fehlgeschlagen", error)
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun toggleLamp(context: Context) {
        val sentOverLan = HandyLampClient.toggle(context)
        if (!sentOverLan) {
            PcCommands.queue(PcCommands.LAMP_TOGGLE, source = "widget_lampe", cooldownMs = 0)
            HandyLampClient.flipCached(context)
        }
    }

    private fun updateLampIcon(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CommandCodeUsageWidget::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.home_bar_widget)
        views.setImageViewResource(R.id.lamp_widget_icon, lampIcon(context))
        manager.partiallyUpdateAppWidget(ids, views)
    }

    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.home_bar_widget)
        views.setOnClickPendingIntent(R.id.home_bar_usage, refreshPendingIntent(context))
        views.setOnClickPendingIntent(R.id.home_bar_lamp, lampPendingIntent(context))
        views.setImageViewResource(R.id.lamp_widget_icon, lampIcon(context))
        return views
    }

    private fun buildViews(context: Context, usage: CommandCodeUsage): RemoteViews {
        val views = baseViews(context)
        val diffText = String.format(Locale.US, "%.2f %%", abs(usage.pct - usage.pacePct))
        val paceLine = if (usage.overPace) {
            context.getString(R.string.commandcode_widget_over, diffText)
        } else {
            context.getString(R.string.commandcode_widget_under, diffText)
        }
        views.setImageViewBitmap(R.id.cc_widget_bar, CommandCodeBarRenderer.render(usage.pct, usage.pacePct))
        views.setTextViewText(R.id.cc_widget_pace, paceLine)
        return views
    }

    private fun buildErrorViews(context: Context, state: ErrorState): RemoteViews {
        val views = baseViews(context)
        views.setImageViewBitmap(R.id.cc_widget_bar, CommandCodeBarRenderer.render(0.0, 0.0))
        val text = when (state) {
            ErrorState.EXPIRED -> context.getString(R.string.commandcode_widget_expired)
            ErrorState.BLOCKED -> context.getString(R.string.commandcode_widget_blocked)
            ErrorState.UNKNOWN -> context.getString(R.string.commandcode_widget_error)
        }
        views.setTextViewText(R.id.cc_widget_pace, text)
        return views
    }

    private fun lampIcon(context: Context): Int =
        if (HandyLampClient.cachedOn(context) == true) R.drawable.ic_lightbulb_on else R.drawable.ic_lightbulb_off

    private fun refreshPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CommandCodeUsageWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun lampPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, CommandCodeUsageWidget::class.java).setAction(ACTION_LAMP_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val ACTION_REFRESH = "com.tabslify.widget.COMMANDCODE_REFRESH"
        const val ACTION_LAMP_TOGGLE = "com.tabslify.widget.LAMP_TOGGLE"

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
