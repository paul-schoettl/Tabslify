package com.tabslify.tabs.focusguard.data

import android.content.Context
import com.tabslify.core.activities.Tabslify.Companion.appScope
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.prvt
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
private data class UsageLogRow(
    val id: String,
    @SerialName("package_name") val packageName: String,
    val category: String,
    val date: String,
    @SerialName("session_start") val sessionStart: Long,
    @SerialName("session_end") val sessionEnd: Long,
    @SerialName("duration_ms") val durationMs: Long,
    val synced: Boolean = true
)

@Serializable
private data class RestrictionRuleRow(
    val id: String,
    val category: String,
    @SerialName("start_minute") val startMinute: Int,
    @SerialName("end_minute") val endMinute: Int,
    @SerialName("days_of_week_mask") val daysOfWeekMask: Int,
    val enabled: Boolean
)

@Serializable
private data class SleepRecordRow(
    val id: String,
    val date: String,
    val bedtime: Long,
    val wake: Long,
    @SerialName("duration_ms") val durationMs: Long
)

@Serializable
private data class StudyGoalRow(
    val id: String,
    val date: String,
    @SerialName("target_count") val targetCount: Int,
    @SerialName("completed_count") val completedCount: Int,
    val deadline: Long? = null,
    @SerialName("reminder_interval_min") val reminderIntervalMin: Int,
    @SerialName("last_reminder_at") val lastReminderAt: Long? = null
)

@Serializable
private data class UserAchievementRow(
    val id: String,
    val type: String,
    @SerialName("earned_at") val earnedAt: Long,
    val points: Int,
    val meta: String? = null
)

object FocusGuardRepository {
    private lateinit var db: FocusGuardDatabase

    private val _todayUsage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val todayUsage: StateFlow<Map<String, Long>> = _todayUsage.asStateFlow()

    private val _todayPackages = MutableStateFlow<List<PackageTotal>>(emptyList())

    private val _sleepWeek = MutableStateFlow<List<SleepRecord>>(emptyList())
    val sleepWeek: StateFlow<List<SleepRecord>> = _sleepWeek.asStateFlow()

    private val _todayGoal = MutableStateFlow<StudyGoal?>(null)
    val todayGoal: StateFlow<StudyGoal?> = _todayGoal.asStateFlow()

    private val _achievements = MutableStateFlow<List<UserAchievement>>(emptyList())
    val achievements: StateFlow<List<UserAchievement>> = _achievements.asStateFlow()

    private val _rules = MutableStateFlow<List<RestrictionRule>>(emptyList())
    val rules: StateFlow<List<RestrictionRule>> = _rules.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(ctx: Context) {
        val appContext = ctx.applicationContext
        if (initialized) return
        initialized = true
        db = FocusGuardDatabase.get(appContext)
        FocusGuardConfig.init(appContext)
        refresh()
    }

    fun refresh() {
        appScope.launch(Dispatchers.IO) {
            runCatching { refreshNow() }
        }
    }

    suspend fun refreshNow() {
        withContext(Dispatchers.IO) {
            val today = todayYmd()
            _todayUsage.value = db.appUsageLogDao().totalsByCategory(today)
                .associate { it.category to it.total }
            _todayPackages.value = db.appUsageLogDao().totalsByPackage(today)
            _sleepWeek.value = db.sleepRecordDao().lastN(today, 7)
            _todayGoal.value = db.studyGoalDao().forDate(today)
            _achievements.value = db.userAchievementDao().all()
            _rules.value = db.restrictionRuleDao().all()
        }
    }

    fun logUsage(logs: List<AppUsageLog>) {
        if (logs.isEmpty()) return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                db.appUsageLogDao().insertAll(logs)
            }
            refreshUsage()
        }
    }

    fun saveRules(rules: List<RestrictionRule>) {
        appScope.launch(Dispatchers.IO) {
            runCatching { db.restrictionRuleDao().upsertAll(rules) }
            _rules.value = rules
        }
    }

    suspend fun upsertSleep(record: SleepRecord) {
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = db.sleepRecordDao().forDate(record.date)
                val changed = existing != null && (existing.bedtimeMs != record.bedtimeMs
                    || existing.wakeMs != record.wakeMs || existing.durationMs != record.durationMs)
                when {
                    existing == null -> db.sleepRecordDao().upsert(record)
                    changed -> db.sleepRecordDao().upsert(existing.copy(
                        bedtimeMs = record.bedtimeMs,
                        wakeMs = record.wakeMs,
                        durationMs = record.durationMs,
                        synced = false
                    ))
                }
                removeLegacyNight(record.date)
            }
        }
        refresh()
    }

    suspend fun clearSleep(date: String) {
        withContext(Dispatchers.IO) {
            val existing = runCatching { db.sleepRecordDao().forDate(date) }.getOrNull()
            if (existing != null) deleteSleepRecord(date)
            removeLegacyNight(date)
            refresh()
        }
    }

    private suspend fun removeLegacyNight(wakeDate: String) {
        val previousKey = runCatching {
            LocalDate.parse(wakeDate).minusDays(1).toString()
        }.getOrNull() ?: return
        val legacy = runCatching { db.sleepRecordDao().forDate(previousKey) }.getOrNull()
            ?: return
        val legacyWakeDate = Instant.ofEpochMilli(legacy.wakeMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        if (legacyWakeDate == wakeDate) deleteSleepRecord(previousKey)
    }

    private suspend fun deleteSleepRecord(date: String) {
        runCatching { db.sleepRecordDao().deleteForDate(date) }
        if (prvt()) {
            runCatching {
                Config.client.from("sleep_records").delete {
                    filter { eq("date", date) }
                }
            }
        }
    }

    suspend fun ensureTodayGoal(targetCount: Int = FocusGuardConfig.studyDailyTarget, deadlineMs: Long? = null): StudyGoal {
        val today = todayYmd()
        val existing = db.studyGoalDao().forDate(today)
        val goal = existing ?: StudyGoal(date = today, targetCount = targetCount, deadlineMs = deadlineMs)
        if (existing == null) {
            runCatching { db.studyGoalDao().upsert(goal) }
        }
        _todayGoal.value = goal
        return goal
    }

    fun addStudyProgress(count: Int) {
        appScope.launch(Dispatchers.IO) {
            val today = todayYmd()
            val goal = runCatching { db.studyGoalDao().forDate(today) }
                .getOrNull()
                ?: StudyGoal(
                    date = today,
                    targetCount = FocusGuardConfig.studyDailyTarget
                )
            val updated = goal.copy(completedCount = goal.completedCount + count)
            runCatching { db.studyGoalDao().upsert(updated) }
            _todayGoal.value = updated
        }
    }

    fun resetTodayGoal() {
        appScope.launch(Dispatchers.IO) {
            val today = todayYmd()
            val goal = runCatching { db.studyGoalDao().forDate(today) }.getOrNull() ?: return@launch
            val updated = goal.copy(completedCount = 0)
            runCatching { db.studyGoalDao().upsert(updated) }
            _todayGoal.value = updated
        }
    }

    fun markGoalReminderSent() {
        appScope.launch(Dispatchers.IO) {
            val today = todayYmd()
            val goal = runCatching { db.studyGoalDao().forDate(today) }.getOrNull() ?: return@launch
            val updated = goal.copy(lastReminderAtMs = System.currentTimeMillis())
            runCatching { db.studyGoalDao().upsert(updated) }
            _todayGoal.value = updated
        }
    }

    suspend fun dailyUsageForLastDays(days: Int): Map<String, Map<String, Long>> =
        withContext(Dispatchers.IO) {
            val from = ymdDaysAgo(days - 1)
            runCatching {
                db.appUsageLogDao().dailyTotals(from)
                    .groupBy({ it.date }, { it.category to it.total })
                    .mapValues { e -> e.value.toMap() }
            }.getOrDefault(emptyMap())
        }

    suspend fun goalsSince(days: Int): List<StudyGoal> = withContext(Dispatchers.IO) {
        val from = ymdDaysAgo(days - 1)
        runCatching { db.studyGoalDao().since(from) }.getOrDefault(emptyList())
    }

    suspend fun sleepSince(days: Int): List<SleepRecord> = withContext(Dispatchers.IO) {
        runCatching { db.sleepRecordDao().lastN(todayYmd(), days) }.getOrDefault(emptyList())
    }

    fun recordAchievement(type: String, points: Int, meta: String? = null) {
        appScope.launch(Dispatchers.IO) {
            val existing = runCatching { db.userAchievementDao().byType(type) }.getOrNull()
            if (existing != null) return@launch
            val achievement = UserAchievement(
                type = type,
                earnedAtMs = System.currentTimeMillis(),
                points = points,
                meta = meta
            )
            runCatching { db.userAchievementDao().insert(achievement) }
            FocusGuardConfig.points += points
            refresh()
        }
    }

    fun syncToSupabase() {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                if (!prvt()) return@runCatching
                val logs = db.appUsageLogDao().unsynced()
                if (logs.isNotEmpty()) {
                    Config.client.from("app_usage_logs").upsert(logs.map { usageLogToJson(it) })
                    db.appUsageLogDao().markSynced(logs.map { it.id })
                }
                val rules = db.restrictionRuleDao().unsynced()
                if (rules.isNotEmpty()) {
                    Config.client.from("restriction_rules").upsert(rules.map { ruleToJson(it) })
                    db.restrictionRuleDao().markSynced(rules.map { it.id })
                }
                db.sleepRecordDao().deleteDuplicates()
                val sleeps = db.sleepRecordDao().unsynced()
                if (sleeps.isNotEmpty()) {
                    val uniqueSleeps = sleeps.sortedWith(
                        compareByDescending<SleepRecord> { it.wakeMs }
                            .thenByDescending { it.bedtimeMs }
                    ).distinctBy { it.date }
                    Config.client.from("sleep_records").upsert(
                        uniqueSleeps.map { sleepToJson(it) }
                    ) { onConflict = "date" }
                    db.sleepRecordDao().markSynced(uniqueSleeps.map { it.id })
                }
                val goals = db.studyGoalDao().unsynced()
                if (goals.isNotEmpty()) {
                    val uniqueGoals = goals.distinctBy { it.date }
                    Config.client.from("study_goals").upsert(
                        uniqueGoals.map { goalToJson(it) }
                    ) { onConflict = "date" }
                    db.studyGoalDao().markSynced(uniqueGoals.map { it.id })
                }
                val achievements = db.userAchievementDao().unsynced()
                if (achievements.isNotEmpty()) {
                    Config.client.from("user_achievements").upsert(achievements.map { achievementToJson(it) })
                    db.userAchievementDao().markSynced(achievements.map { it.id })
                }
            }
        }
    }

    private fun refreshUsage() {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val today = todayYmd()
                _todayUsage.value = db.appUsageLogDao().totalsByCategory(today)
                    .associate { it.category to it.total }
                _todayPackages.value = db.appUsageLogDao().totalsByPackage(today)
            }
        }
    }

    private fun usageLogToJson(log: AppUsageLog): UsageLogRow = UsageLogRow(
        id = log.id,
        packageName = log.packageName,
        category = log.category,
        date = log.date,
        sessionStart = log.sessionStartMs,
        sessionEnd = log.sessionEndMs,
        durationMs = log.durationMs,
        synced = true
    )

    private fun ruleToJson(rule: RestrictionRule): RestrictionRuleRow = RestrictionRuleRow(
        id = rule.id,
        category = rule.category,
        startMinute = rule.startMinute,
        endMinute = rule.endMinute,
        daysOfWeekMask = rule.daysOfWeekMask,
        enabled = rule.enabled
    )

    private fun sleepToJson(record: SleepRecord): SleepRecordRow = SleepRecordRow(
        id = record.id,
        date = record.date,
        bedtime = record.bedtimeMs,
        wake = record.wakeMs,
        durationMs = record.durationMs
    )

    private fun goalToJson(goal: StudyGoal): StudyGoalRow = StudyGoalRow(
        id = goal.id,
        date = goal.date,
        targetCount = goal.targetCount,
        completedCount = goal.completedCount,
        deadline = goal.deadlineMs,
        reminderIntervalMin = goal.reminderIntervalMin,
        lastReminderAt = goal.lastReminderAtMs
    )

    private fun achievementToJson(achievement: UserAchievement): UserAchievementRow = UserAchievementRow(
        id = achievement.id,
        type = achievement.type,
        earnedAt = achievement.earnedAtMs,
        points = achievement.points,
        meta = achievement.meta
    )
}
