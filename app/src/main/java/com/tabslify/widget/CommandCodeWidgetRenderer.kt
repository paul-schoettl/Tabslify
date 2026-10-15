package com.tabslify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.tabslify.R
import java.util.Locale
import kotlin.math.abs

object CommandCodeWidgetRenderer {

    private const val TAG = "CommandCodeWidget"
    private const val PENDING_ALPHA = 96
    private const val NORMAL_ALPHA = 255

    enum class PaceState { DATA, LOADING, EXPIRED, BLOCKED, ERROR }

    fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, CommandCodeUsageWidget::class.java))
    }

    fun render(context: Context, state: PaceState): RemoteViews =
        build(context, state, null)

    suspend fun renderWithFooter(context: Context, state: PaceState): RemoteViews =
        build(context, state, footerText(context, readChargeEstimate(context)))

    private fun build(
        context: Context,
        state: PaceState,
        footer: String?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.home_bar_widget)
        views.setOnClickPendingIntent(R.id.home_bar_usage, refreshPendingIntent(context))
        views.setOnClickPendingIntent(R.id.home_bar_lamp, lampPendingIntent(context))
        views.setImageViewResource(R.id.lamp_widget_icon, lampIcon(context))
        views.setInt(
            R.id.lamp_widget_icon,
            "setAlpha",
            if (LampWidgetPending.isPending(context)) PENDING_ALPHA else NORMAL_ALPHA
        )

        val snapshot = WidgetSnapshotStore.read(context)
        if (state == PaceState.DATA && snapshot != null) {
            views.setImageViewBitmap(
                R.id.cc_widget_bar,
                CommandCodeBarRenderer.render(snapshot.pct, snapshot.pacePct)
            )
            views.setTextViewText(R.id.cc_widget_pace, paceText(context, snapshot))
        } else {
            views.setImageViewBitmap(R.id.cc_widget_bar, CommandCodeBarRenderer.render(0.0, 0.0))
            views.setTextViewText(R.id.cc_widget_pace, stateText(context, state))
        }

        applyFooter(context, views, footer)
        return views
    }

    fun push(context: Context, views: RemoteViews) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    fun pushCurrent(context: Context) {
        val snapshot = WidgetSnapshotStore.read(context)
        push(context, render(context, if (snapshot == null) PaceState.LOADING else PaceState.DATA))
    }

    fun pushError(context: Context, state: PaceState) {
        push(context, render(context, state))
    }

    private fun applyFooter(context: Context, views: RemoteViews, footer: String?) {
        val text = footer ?: footerText(context, readChargeEstimateFast(context))
        if (text == null) {
            views.setViewVisibility(R.id.home_bar_footer, View.GONE)
        } else {
            views.setTextViewText(R.id.home_bar_footer, text)
            views.setViewVisibility(R.id.home_bar_footer, View.VISIBLE)
        }
    }

    private fun paceText(context: Context, snapshot: WidgetUsageSnapshot): String {
        val diffText = String.format(Locale.US, "%.2f %%", abs(snapshot.pct - snapshot.pacePct))
        return if (snapshot.overPace) {
            context.getString(R.string.commandcode_widget_over, diffText)
        } else {
            context.getString(R.string.commandcode_widget_under, diffText)
        }
    }

    private fun stateText(context: Context, state: PaceState): String = when (state) {
        PaceState.DATA -> context.getString(R.string.commandcode_widget_loading)
        PaceState.LOADING -> context.getString(R.string.commandcode_widget_loading)
        PaceState.EXPIRED -> context.getString(R.string.commandcode_widget_expired)
        PaceState.BLOCKED -> context.getString(R.string.commandcode_widget_blocked)
        PaceState.ERROR -> context.getString(R.string.commandcode_widget_error)
    }

    private fun lampIcon(context: Context): Int =
        if (HandyLampClient.cachedOn(context) == true) {
            R.drawable.ic_lightbulb_on
        } else {
            R.drawable.ic_lightbulb_off
        }

    private fun refreshPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CommandCodeUsageWidget::class.java).setAction(CommandCodeUsageWidget.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun lampPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, CommandCodeUsageWidget::class.java).setAction(CommandCodeUsageWidget.ACTION_LAMP_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun logRender(context: Context, state: PaceState, cached: Boolean) {
        Log.i(TAG, "Render: Zustand=$state cache=$cached")
    }
}

object LampWidgetPending {

    private const val PREFS = "lamp_widget_pending"
    private const val KEY_TAP_AT = "tap_at"
    private const val WINDOW_MS = 20_000L

    fun markTap(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TAP_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun isPending(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_TAP_AT, 0L)
        return at > 0L && System.currentTimeMillis() - at < WINDOW_MS
    }
}