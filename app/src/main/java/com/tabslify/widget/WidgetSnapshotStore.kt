package com.tabslify.widget

import android.content.Context
import androidx.core.content.edit

data class WidgetUsageSnapshot(
    val pct: Double,
    val pacePct: Double,
    val at: Long
) {
    val overPace: Boolean get() = pct > pacePct
}

object WidgetSnapshotStore {

    private const val PREFS = "commandcode_widget_snapshot"
    private const val KEY_PCT = "pct"
    private const val KEY_PACE = "pace_pct"
    private const val KEY_AT = "at"

    fun save(context: Context, usage: CommandCodeUsage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_PCT, usage.pct.toString())
                    .putString(KEY_PACE, usage.pacePct.toString())
                    .putLong(KEY_AT, System.currentTimeMillis())
            }
    }

    fun read(context: Context): WidgetUsageSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PCT) || !prefs.contains(KEY_PACE)) return null
        val pct = prefs.getString(KEY_PCT, null)?.toDoubleOrNull() ?: return null
        val pace = prefs.getString(KEY_PACE, null)?.toDoubleOrNull() ?: return null
        return WidgetUsageSnapshot(pct, pace, prefs.getLong(KEY_AT, 0L))
    }
}