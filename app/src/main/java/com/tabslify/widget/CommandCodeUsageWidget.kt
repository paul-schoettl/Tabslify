package com.tabslify.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "CommandCodeWidget"

class CommandCodeUsageWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val app = context.applicationContext
        runCatching { LampWidgetSync.start(app) }
        CommandCodeWidgetRenderer.pushCurrent(app)
        CommandCodeWidgetRefreshWorker.enqueue(app)
    }

    override fun onDisabled(context: Context) {
        runCatching { LampWidgetSync.stop(context.applicationContext) }
        super.onDisabled(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        Log.i(TAG, "Broadcast APPWIDGET_UPDATE fuer ${ids.size} Instanzen")
        CommandCodeWidgetRenderer.pushCurrent(app)
        CommandCodeWidgetRefreshWorker.enqueueUnique(app)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_REFRESH -> {
                Log.i(TAG, "Broadcast REFRESH")
                CommandCodeWidgetRenderer.pushCurrent(app)
                CommandCodeWidgetRefreshWorker.enqueue(app)
            }

            ACTION_LAMP_TOGGLE -> {
                Log.i(TAG, "Broadcast LAMP_TOGGLE, Ansicht bleibt bis zum Worker-Ergebnis unveraendert")
                LampWidgetPending.markTap(app)
                CommandCodeWidgetRenderer.pushCurrent(app)
                LampWidgetToggleWorker.enqueue(app)
            }

            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.tabslify.widget.COMMANDCODE_REFRESH"
        const val ACTION_LAMP_TOGGLE = "com.tabslify.widget.LAMP_TOGGLE"

        fun updateAll(context: Context) {
            val app = context.applicationContext
            CommandCodeWidgetRenderer.pushCurrent(app)
            CommandCodeWidgetRefreshWorker.enqueueUnique(app)
        }

        fun updateChargeFooter(context: Context) {
            CommandCodeWidgetRenderer.pushCurrent(context.applicationContext)
        }
    }
}
