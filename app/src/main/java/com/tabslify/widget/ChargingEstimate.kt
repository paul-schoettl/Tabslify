package com.tabslify.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.tabslify.R
import com.tabslify.quicksettingsfunctions.BatteryDataRepository

data class ChargeEstimate(
    val level: Int,
    val isCharging: Boolean,
    val minutesToTarget: Int?
)

private const val CHARGE_TARGET = 95
private const val HISTORY_WINDOW_MS = 90L * 60 * 1000

suspend fun readChargeEstimate(context: Context): ChargeEstimate {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (scale > 0 && level >= 0) level * 100 / scale else level
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val minutes = if (charging) estimateMinutesToTarget(context, pct) else null
    return ChargeEstimate(pct, charging, minutes)
}

fun readChargeEstimateFast(context: Context): ChargeEstimate {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (scale > 0 && level >= 0) level * 100 / scale else level
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val minutes = if (charging) fastMinutesToTarget(context, pct) else null
    return ChargeEstimate(pct, charging, minutes)
}

fun footerText(context: Context, estimate: ChargeEstimate): String? {
    if (!estimate.isCharging) return null
    val minutes = estimate.minutesToTarget
        ?: return context.getString(R.string.home_bar_widget_charging)
    val duration = if (minutes >= 60) {
        context.getString(R.string.home_bar_widget_eta_h_min, minutes / 60, minutes % 60)
    } else {
        context.getString(R.string.home_bar_widget_eta_min, minutes)
    }
    return context.getString(R.string.home_bar_widget_charging_eta, duration)
}

private suspend fun estimateMinutesToTarget(context: Context, level: Int): Int? {
    if (level >= CHARGE_TARGET) return 0
    val rate = systemRate(context, level) ?: historyRate(context)
    if (rate == null || rate <= 0f) return null
    return ((CHARGE_TARGET - level) / rate).toInt().coerceAtLeast(0)
}

private fun fastMinutesToTarget(context: Context, level: Int): Int? {
    if (level >= CHARGE_TARGET) return 0
    val rate = systemRate(context, level) ?: return null
    if (rate <= 0f) return null
    return ((CHARGE_TARGET - level) / rate).toInt().coerceAtLeast(0)
}

private fun systemRate(context: Context, level: Int): Float? {
    if (level >= 100) return null
    val manager = context.getSystemService(BatteryManager::class.java) ?: return null
    val remainingMs = runCatching { manager.computeChargeTimeRemaining() }.getOrDefault(-1L)
    if (remainingMs <= 0L) return null
    val pctPerMs = (100 - level).toFloat() / remainingMs.toFloat()
    return if (pctPerMs > 0f) pctPerMs * 60_000f else null
}

private suspend fun historyRate(context: Context): Float? {
    BatteryDataRepository.init(context)
    val samples = BatteryDataRepository.recentSamples(HISTORY_WINDOW_MS)
    if (samples.size < 2) return null
    val first = samples.first()
    val last = samples.last()
    val levelDiff = last.level - first.level
    val minutes = (last.timestamp - first.timestamp) / 60_000f
    if (levelDiff <= 0 || minutes <= 0f) return null
    return levelDiff / minutes
}
