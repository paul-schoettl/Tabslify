package com.tabslify.tabs.ridetracking.data

import android.content.Context
import com.tabslify.tabs.exploretab.dayBounds
import com.tabslify.tabs.fitnesstab.data.EXERCISE_ID_CYCLING
import com.tabslify.tabs.fitnesstab.data.ExerciseSet
import com.tabslify.tabs.fitnesstab.data.ExploreActivityBridge
import com.tabslify.tabs.fitnesstab.data.WorkoutEntry
import com.tabslify.tabs.fitnesstab.data.WorkoutSession
import java.time.LocalDate

object RideSessionAdapter {

    const val RIDE_SESSION_PREFIX = "ride_"
    private const val MIN_DURATION_MS = 120_000L
    private const val MIN_DISTANCE_METERS = 150.0

    fun isRideSession(sessionId: String): Boolean = sessionId.startsWith(RIDE_SESSION_PREFIX)

    suspend fun sessionsForLastDays(
        context: Context,
        days: Int,
        calorieFactor: Float
    ): List<WorkoutSession> {
        val repo = RideRepository(context.applicationContext)
        val today = LocalDate.now()
        val sessions = mutableListOf<WorkoutSession>()
        for (offset in 0 until days) {
            val day = today.minusDays(offset.toLong())
            val (dayStart, dayEnd) = dayBounds(day)
            repo.finishedBetween(dayStart, dayEnd).forEach { ride ->
                toSession(ride, calorieFactor)?.let { sessions += it }
            }
        }
        return sessions
    }

    fun withoutOverlappingPassiveSessions(
        passive: List<WorkoutSession>,
        recorded: List<WorkoutSession>
    ): List<WorkoutSession> {
        if (recorded.isEmpty()) return passive
        return passive.filter { session ->
            val cycling = session.entries.any { it.exerciseId == EXERCISE_ID_CYCLING }
            if (!cycling) {
                true
            } else {
                recorded.none { ride ->
                    ride.dateStartMs < session.dateEndMs && session.dateStartMs < ride.dateEndMs
                }
            }
        }
    }

    private fun toSession(ride: Ride, calorieFactor: Float): WorkoutSession? {
        val end = ride.endTime ?: return null
        val durationMs = (end - ride.startTime).coerceAtLeast(0L)
        if (durationMs < MIN_DURATION_MS || ride.distanceMeters < MIN_DISTANCE_METERS) return null
        val km = (ride.distanceMeters / 1000.0).toFloat()
        val sessionId = "$RIDE_SESSION_PREFIX${ride.id}"
        val calories = ride.calories?.toFloat()
            ?: ExploreActivityBridge.caloriesFor(
                EXERCISE_ID_CYCLING,
                km,
                ride.movingTimeMs.takeIf { it > 0L } ?: durationMs,
                ride.mode,
                calorieFactor
            )
        return WorkoutSession(
            sessionId = sessionId,
            dateStartMs = ride.startTime,
            dateEndMs = end,
            entries = listOf(
                WorkoutEntry(
                    entryId = "${sessionId}_entry",
                    exerciseId = EXERCISE_ID_CYCLING,
                    sets = listOf(
                        ExerciseSet(
                            setNumber = 1,
                            durationSeconds = (ride.movingTimeMs / 1000L).toInt(),
                            distanceKm = km,
                            completed = true
                        )
                    ),
                    notes = ride.title.orEmpty(),
                    startedAtMs = ride.startTime,
                    finishedAtMs = end
                )
            ),
            totalCalories = calories,
            name = ride.title.orEmpty()
        )
    }
}
