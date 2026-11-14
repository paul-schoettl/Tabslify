package com.tabslify.tabs.fitnesstab.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val sessionsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class SetRecord(
    @SerialName("n") val setNumber: Int,
    @SerialName("reps") val reps: Int = 0,
    @SerialName("w") val weightKg: Float = 0f,
    @SerialName("dur") val durationSeconds: Int = 0,
    @SerialName("dist") val distanceKm: Float = 0f,
    @SerialName("done") val completed: Boolean = false
)

@Serializable
private data class EntryRecord(
    @SerialName("id") val entryId: String,
    @SerialName("exId") val exerciseId: String,
    val sets: List<SetRecord>,
    val notes: String = "",
    @SerialName("start") val startedAtMs: Long = 0L,
    @SerialName("end") val finishedAtMs: Long = 0L
)

@Serializable
private data class SessionRecord(
    @SerialName("id") val sessionId: String,
    @SerialName("start") val dateStartMs: Long,
    @SerialName("end") val dateEndMs: Long,
    val entries: List<EntryRecord>,
    @SerialName("kcal") val totalCalories: Float = 0f,
    val name: String = "",
    @SerialName("repTimes") val repTimesMs: List<Long> = emptyList()
)

class FitnessStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(session: WorkoutSession) {
        val sessions = loadAllSessions().toMutableList()
        sessions.removeAll { it.sessionId == session.sessionId }
        sessions.add(session)
        saveSessions(sessions)
    }

    fun deleteSession(sessionId: String) {
        val sessions = loadAllSessions().filter { it.sessionId != sessionId }
        saveSessions(sessions)
    }

    fun loadAllSessions(): List<WorkoutSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val elements = runCatching { sessionsJson.decodeFromString<List<JsonElement>>(raw) }.getOrNull()
            ?: return emptyList()
        return elements.mapNotNull { element ->
            runCatching { sessionsJson.decodeFromJsonElement<SessionRecord>(element).toSession() }
                .getOrNull()
        }
    }

    fun mergeWithStored(extraSessions: List<WorkoutSession>): List<WorkoutSession> {
        val stored = loadAllSessions()
        if (extraSessions.isEmpty()) return stored
        val storedIds = stored.mapTo(mutableSetOf()) { it.sessionId }
        return stored + extraSessions.filter { it.sessionId !in storedIds }
    }

    fun loadLastNDays(days: Int, extraSessions: List<WorkoutSession> = emptyList()): Map<String, DayStats> {
        val result = mutableMapOf<String, DayStats>()
        val sessions = mergeWithStored(extraSessions)
        val sessionsByDay = sessions.groupBy { ymdOf(it.dateStartMs) }

        val cal = Calendar.getInstance()
        repeat(days) {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val ymd = ymdOf(cal.timeInMillis)
            val daySessions = sessionsByDay[ymd].orEmpty()
            result[ymd] = buildDayStats(ymd, daySessions)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return result
    }

    fun getFitnessStats(extraSessions: List<WorkoutSession> = emptyList()): FitnessStats {
        val all = mergeWithStored(extraSessions)
        val todayYmd = ymdOf(System.currentTimeMillis())
        val todaySessions = all.filter { ymdOf(it.dateStartMs) == todayYmd }
        val today = buildDayStats(todayYmd, todaySessions)

        val streak = calcStreak(all)
        val totalMinutes = all.sumOf { sessionMinutes(it) }
        val totalCalories = all.sumOf { it.totalCalories.toDouble() }.toFloat()
        val totalDistanceKm = all.sumOf { sessionDistanceKm(it).toDouble() }.toFloat()

        return FitnessStats(
            streakDays = streak,
            totalWorkouts = all.size,
            totalMinutes = totalMinutes,
            totalCalories = totalCalories,
            totalDistanceKm = totalDistanceKm,
            lastWorkoutMs = all.maxOfOrNull { it.dateEndMs } ?: 0L,
            today = today
        )
    }

    fun getUserWeightKg(): Float = prefs.getFloat(KEY_USER_WEIGHT, 75f)

    fun setUserWeightKg(kg: Float) = prefs.edit { putFloat(KEY_USER_WEIGHT, kg) }

    fun getUserAgeYears(): Int = prefs.getInt(KEY_USER_AGE, 30)

    fun setUserAgeYears(age: Int) = prefs.edit { putInt(KEY_USER_AGE, age) }

    fun calorieFactor(): Float {
        val weightFactor = getUserWeightKg() / 75f
        val age = getUserAgeYears()
        val ageFactor = if (age <= 25) 1f else (1f - (age - 25) * 0.003f).coerceIn(0.85f, 1f)
        return weightFactor * ageFactor
    }

    fun getDailyGoalMinutes(): Int = prefs.getInt(KEY_GOAL_MINUTES, 30)

    fun getDailyGoalCalories(): Int = prefs.getInt(KEY_GOAL_CALORIES, 300)

    // ----- private helpers -----

    private fun saveSessions(sessions: List<WorkoutSession>) {
        val encoded = runCatching {
            sessionsJson.encodeToString(sessions.sortedByDescending { it.dateStartMs }.map { it.toRecord() })
        }.getOrDefault("[]")
        prefs.edit { putString(KEY_SESSIONS, encoded) }
    }

    private fun WorkoutSession.toRecord(): SessionRecord = SessionRecord(
        sessionId = sessionId,
        dateStartMs = dateStartMs,
        dateEndMs = dateEndMs,
        entries = entries.map { it.toRecord() },
        totalCalories = totalCalories,
        name = name,
        repTimesMs = repTimesMs
    )

    private fun WorkoutEntry.toRecord(): EntryRecord = EntryRecord(
        entryId = entryId,
        exerciseId = exerciseId,
        sets = sets.map { it.toRecord() },
        notes = notes,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs
    )

    private fun ExerciseSet.toRecord(): SetRecord = SetRecord(
        setNumber = setNumber,
        reps = reps,
        weightKg = weightKg,
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        completed = completed
    )

    private fun SessionRecord.toSession(): WorkoutSession = WorkoutSession(
        sessionId = sessionId,
        dateStartMs = dateStartMs,
        dateEndMs = dateEndMs,
        entries = entries.map { it.toEntry() },
        totalCalories = totalCalories,
        name = name,
        repTimesMs = repTimesMs
    )

    private fun EntryRecord.toEntry(): WorkoutEntry = WorkoutEntry(
        entryId = entryId,
        exerciseId = exerciseId,
        sets = sets.map { it.toSet() },
        notes = notes,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs
    )

    private fun SetRecord.toSet(): ExerciseSet = ExerciseSet(
        setNumber = setNumber,
        reps = reps,
        weightKg = weightKg,
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        completed = completed
    )

    private fun buildDayStats(ymd: String, sessions: List<WorkoutSession>): DayStats {
        val minutes = sessions.sumOf { sessionMinutes(it) }
        val kcal = sessions.sumOf { it.totalCalories.toDouble() }.toFloat()
        var totalReps = 0
        var exercisesCount = 0
        sessions.forEach { s ->
            s.entries.forEach { e ->
                exercisesCount++
                totalReps += e.sets.sumOf { it.reps }
            }
        }
        return DayStats(
            dateYmd = ymd,
            totalMinutes = minutes,
            totalCalories = kcal,
            workoutsCount = sessions.size,
            exercisesCount = exercisesCount,
            totalReps = totalReps,
            totalDistanceKm = sessions.sumOf { sessionDistanceKm(it).toDouble() }.toFloat(),
            sessionIds = sessions.map { it.sessionId }
        )
    }

    private fun sessionDistanceKm(s: WorkoutSession): Float =
        s.entries.sumOf { e -> e.sets.sumOf { it.distanceKm.toDouble() } }.toFloat()

    private fun calcStreak(sessions: List<WorkoutSession>): Int {
        if (sessions.isEmpty()) return 0
        val daysSet = sessions.map { ymdOf(it.dateStartMs) }.toSet()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (!daysSet.contains(ymdOf(cal.timeInMillis))) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if (!daysSet.contains(ymdOf(cal.timeInMillis))) return 0
        }

        var streak = 0
        while (daysSet.contains(ymdOf(cal.timeInMillis))) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private fun sessionMinutes(s: WorkoutSession): Int {
        val diff = (s.dateEndMs - s.dateStartMs).coerceAtLeast(0L)
        return (diff / 60_000L).toInt()
    }

    private fun ymdOf(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(ms))
    }

    companion object {
        private const val PREFS_NAME = "fitness_storage_v1"
        private const val KEY_SESSIONS = "sessions_json"
        private const val KEY_USER_WEIGHT = "user_weight_kg"
        private const val KEY_USER_AGE = "user_age_years"
        private const val KEY_GOAL_MINUTES = "goal_minutes"
        private const val KEY_GOAL_CALORIES = "goal_calories"
    }
}
