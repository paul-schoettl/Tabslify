package com.tabslify.tabs.focusguard.monitoring

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.tabslify.tabs.focusguard.data.SleepRecord
import com.tabslify.core.ui.FeatureToggle
import com.tabslify.core.ui.isFeatureEnabled
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object SleepTracker {
    private const val MIN_SLEEP_MS = 60L * 60 * 1000
    private const val MAX_SLEEP_MS = 18L * 60 * 60 * 1000
    private const val MIN_GAP_MS = 4L * 60 * 60 * 1000

    private val WINDOW_START = LocalTime.of(18, 0)
    private val WINDOW_END = LocalTime.of(12, 0)
    private val CORE_NIGHT_START = LocalTime.of(22, 0)
    private val CORE_NIGHT_END = LocalTime.of(8, 0)
    private val PLAUSIBLE_BEDTIME = LocalTime.of(20, 0)

    sealed interface SleepResult {
        data class Found(val record: SleepRecord) : SleepResult
        data object NoData : SleepResult
        data object Unavailable : SleepResult
    }

    suspend fun computeSleep(context: Context, nightOf: LocalDate): SleepRecord? =
        (lookup(context, nightOf) as? SleepResult.Found)?.record

    suspend fun lookup(context: Context, nightOf: LocalDate): SleepResult {
        val healthConnect = readHealthConnect(context, nightOf)
        if (healthConnect is SleepResult.Found) return healthConnect
        val fallback = readUsageFallback(context, nightOf)
        if (fallback != null) return SleepResult.Found(fallback)
        return if (healthConnect is SleepResult.NoData) SleepResult.NoData
        else SleepResult.Unavailable
    }

    suspend fun hasSleepPermission(context: Context): Boolean {
        if (!isFeatureEnabled(context, FeatureToggle.HEALTH)) return false
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return false
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrNull() ?: return false
        return HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
    }

    fun isHealthConnectAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private suspend fun readHealthConnect(context: Context, nightOf: LocalDate): SleepResult {
        if (!isFeatureEnabled(context, FeatureToggle.HEALTH)) return SleepResult.Unavailable
        if (!isHealthConnectAvailable(context)) return SleepResult.Unavailable
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return SleepResult.Unavailable
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrNull() ?: return SleepResult.Unavailable
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
            return SleepResult.Unavailable
        }

        val zone = ZoneId.systemDefault()
        val wakeDate = nightOf.plusDays(1)
        val windowStart = nightOf.atTime(WINDOW_START).atZone(zone).toInstant()
        val windowEnd = wakeDate.atTime(WINDOW_END).atZone(zone).toInstant()
        val records = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd)
                )
            ).records
        }.getOrNull() ?: return SleepResult.Unavailable

        val coreStart = nightOf.atTime(CORE_NIGHT_START).atZone(zone).toInstant().toEpochMilli()
        val coreEnd = wakeDate.atTime(CORE_NIGHT_END).atZone(zone).toInstant().toEpochMilli()
        val sessions = records.filter { record ->
            val start = record.startTime.toEpochMilli()
            val end = record.endTime.toEpochMilli()
            end > start &&
                start < coreEnd &&
                end > coreStart &&
                record.endTime.atZone(zone).toLocalDate() == wakeDate
        }
        if (sessions.isEmpty()) return SleepResult.NoData

        val bedtimeMs = sessions.minOf { it.startTime.toEpochMilli() }
        val wakeMs = sessions.maxOf { it.endTime.toEpochMilli() }
        val durationMs = wakeMs - bedtimeMs
        if (durationMs !in MIN_SLEEP_MS..MAX_SLEEP_MS) return SleepResult.NoData

        return SleepResult.Found(
            SleepRecord(
                date = wakeDate.toString(),
                bedtimeMs = bedtimeMs,
                wakeMs = wakeMs,
                durationMs = durationMs
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun readUsageFallback(context: Context, nightOf: LocalDate): SleepRecord? {
        val zone = ZoneId.systemDefault()
        val wakeDate = nightOf.plusDays(1)
        val windowStart = nightOf.atTime(WINDOW_START).atZone(zone).toInstant().toEpochMilli()
        val windowEnd = wakeDate.atTime(WINDOW_END).atZone(zone).toInstant().toEpochMilli()
        val queryEnd = minOf(System.currentTimeMillis(), windowEnd)
        if (queryEnd <= windowStart) return null

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = runCatching { usm.queryEvents(windowStart, queryEnd) }.getOrNull() ?: return null
        val points = mutableListOf<Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> points.add(event.timeStamp)

                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = event.packageName
                    if (pkg != null && !isIgnoredPackage(pkg)) points.add(event.timeStamp)
                }
            }
        }
        if (points.size < 2) return null
        points.sort()

        val coreStart = nightOf.atTime(CORE_NIGHT_START).atZone(zone).toInstant().toEpochMilli()
        val coreEnd = wakeDate.atTime(CORE_NIGHT_END).atZone(zone).toInstant().toEpochMilli()
        var bestStart = 0L
        var bestEnd = 0L
        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            val duration = end - start
            if (duration < MIN_GAP_MS || duration > MAX_SLEEP_MS) continue
            if (start >= coreEnd || end <= coreStart) continue
            if (Instant.ofEpochMilli(end).atZone(zone).toLocalDate() != wakeDate) continue
            if (Instant.ofEpochMilli(start).atZone(zone).toLocalTime() < PLAUSIBLE_BEDTIME) continue
            if (duration > bestEnd - bestStart) {
                bestStart = start
                bestEnd = end
            }
        }
        if (bestStart == 0L || bestEnd <= bestStart) return null
        val duration = bestEnd - bestStart
        if (duration !in MIN_SLEEP_MS..MAX_SLEEP_MS) return null

        return SleepRecord(
            date = wakeDate.toString(),
            bedtimeMs = bestStart,
            wakeMs = bestEnd,
            durationMs = duration
        )
    }

    private fun isIgnoredPackage(packageName: String): Boolean =
        packageName.startsWith("com.google.android.gms") ||
            packageName.startsWith("com.sec.android") ||
            packageName == "com.android.systemui" ||
            packageName.contains("launcher", ignoreCase = true) ||
            packageName.contains("deskclock", ignoreCase = true) ||
            packageName.contains("clockpackage", ignoreCase = true) ||
            packageName == "com.android.alarmclock"
}
