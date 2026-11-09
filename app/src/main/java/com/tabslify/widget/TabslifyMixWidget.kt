package com.tabslify.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.tabslify.core.logging.Log

private const val TAG = "MixWidget"

class TabslifyMixWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        requestUpdate(context)
    }

    override fun onDisabled(context: Context) {
        val app = context.applicationContext
        MixWidgetRefreshWorker.cancel(app)
        MixWidgetStore.setRefreshing(app, false)
        super.onDisabled(context)
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        Log.i(TAG, "Update fuer ${ids.size} Instanzen")
        requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHUFFLE -> {
                Log.i(TAG, "Neuer Mix angefordert")
                requestUpdate(context)
            }

            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_SHUFFLE = "com.tabslify.widget.MIX_SHUFFLE"
        const val EXTRA_WIDGET_TARGET = "widget_target"

        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            MixWidgetStore.bumpSeed(app)
            MixWidgetStore.setRefreshing(app, true)
            MixWidgetRenderer.push(app)
            MixWidgetRefreshWorker.enqueue(app)
        }
    }
}
