package com.tabslify.tabs.fitnesstab

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabslify.BuildConfig
import com.tabslify.R
import com.tabslify.tabs.fitnesstab.data.BIKE_MODE_AUTO
import com.tabslify.tabs.fitnesstab.data.BikeModeOverrides
import com.tabslify.tabs.fitnesstab.data.BikeRide
import com.tabslify.tabs.fitnesstab.data.BikeRideRepository
import com.tabslify.tabs.fitnesstab.data.BikeStats
import com.tabslify.tabs.fitnesstab.data.DayStats
import com.tabslify.tabs.fitnesstab.data.Exercise
import com.tabslify.tabs.fitnesstab.data.ExerciseRepository
import com.tabslify.tabs.fitnesstab.data.ExerciseSet
import com.tabslify.tabs.fitnesstab.data.ExploreActivityBridge
import com.tabslify.tabs.fitnesstab.data.FitnessStats
import com.tabslify.tabs.fitnesstab.data.FitnessStorage
import com.tabslify.tabs.fitnesstab.data.WorkoutEntry
import com.tabslify.tabs.fitnesstab.data.WorkoutSession
import com.tabslify.tabs.ridetracking.data.RideBikeAdapter
import com.tabslify.tabs.ridetracking.data.RideRecovery
import com.tabslify.tabs.ridetracking.data.RideRepository
import com.tabslify.tabs.ridetracking.data.RideSessionAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private const val PREFS_NAME = "fitness_pushups"
private const val KEY_BEST = "best_reps"
private const val KEY_SEEN_HINT = "seen_pushup_hint"

enum class TrackingIssue {
    NONE,
    NO_BODY,
    ARMS_HIDDEN,
    NOT_IN_POSITION,
    SUPPORT_HIDDEN,
    CALIBRATING,
    HANDS_NOT_PLANTED,
    EXTEND_ARMS,
    CAMERA_ANGLE,
    LOW_SIGNAL_QUALITY,
    TRACKING_UNSTABLE,
    CAMERA_MOVED
}

enum class FitnessScreen { DASHBOARD, EXERCISES, WORKOUT, BIKE, HISTORY }

enum class WorkoutMode { CAMERA_POSE, MANUAL }

enum class BikeFilter { ALL, BIKE, EBIKE }

data class PushUpEditorState(
    val sessionId: String,
    val durationMs: Long,
    val originalReps: Int,
    val repTimesMs: List<Long>,
    val fromHistory: Boolean
)

class FitnessViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val counter = PushUpCounter()
    private val extractor = PushUpSignalExtractor()
    private val counterV3 = PushUpCounterV3()
    private val extractorV3 = PushUpSignalExtractorV3()
    private var gravitySource: PushUpGravitySource? = null
    private var captureRecorder: PushUpCaptureRecorder? = null
    private val gravityBumpDetector = GravityBumpDetector()
    private var timerJob: Job? = null
    private var sessionStartedAtMs = 0L
    private var sessionStartedAtElapsedMs = 0L
    private var lostFrames = 0
    private val detectedRepTimes = mutableStateListOf<Long>()
    private var editingPushUpSession: WorkoutSession? = null
    private var pushUpBestBeforeSession = 0

    private val storage = FitnessStorage(application)

    var frame by mutableStateOf<PoseFrame?>(null)
        private set

    var reps by mutableIntStateOf(0)
        private set

    var phase by mutableStateOf(PushUpPhase.UNKNOWN)
        private set

    var elbowAngle by mutableIntStateOf(180)
        private set

    var poseDetected by mutableStateOf(false)
        private set

    var trackingIssue by mutableStateOf(TrackingIssue.NO_BODY)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var elapsedMs by mutableLongStateOf(0L)
        private set

    var personalBest by mutableIntStateOf(prefs.getInt(KEY_BEST, 0))
        private set

    var isNewRecord by mutableStateOf(false)
        private set

    var formHintRes by mutableStateOf<Int?>(null)
        private set

    var lastRepAtMs by mutableLongStateOf(0L)
        private set

    var pushUpEditor by mutableStateOf<PushUpEditorState?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)

    var showSkeleton by mutableStateOf(true)

    var useFrontCamera by mutableStateOf(true)

    var useV3 by mutableStateOf(BuildConfig.IS_DEV)
        private set

    var debugSignalLine by mutableStateOf("")
        private set

    var captureActive by mutableStateOf(false)
        private set

    var captureId by mutableStateOf<String?>(null)
        private set

    var captureError by mutableStateOf<String?>(null)
        private set

    var capturePresetIndex by mutableIntStateOf(0)
        private set

    val capturePreset: PushUpCapturePreset
        get() = PUSH_UP_CAPTURE_PRESETS[capturePresetIndex.coerceIn(0, PUSH_UP_CAPTURE_PRESETS.lastIndex)]

    var showHint by mutableStateOf(!prefs.getBoolean(KEY_SEEN_HINT, false))
        private set

    var currentScreen by mutableStateOf(FitnessScreen.DASHBOARD)
        private set

    private val _stats = MutableStateFlow(FitnessStats())
    val stats: StateFlow<FitnessStats> = _stats.asStateFlow()

    private val _recentDays = MutableStateFlow<Map<String, DayStats>>(emptyMap())
    val recentDays: StateFlow<Map<String, DayStats>> = _recentDays.asStateFlow()

    private val _sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    val goalMinutes: Int get() = storage.getDailyGoalMinutes()
    val goalCalories: Int get() = storage.getDailyGoalCalories()

    var userAgeYears by mutableIntStateOf(storage.getUserAgeYears())
        private set

    var userWeightKg by mutableFloatStateOf(storage.getUserWeightKg())
        private set

    var profileDialogVisible by mutableStateOf(false)
        private set

    fun openProfileDialog() {
        profileDialogVisible = true
    }

    fun closeProfileDialog() {
        profileDialogVisible = false
    }

    fun updateProfile(ageYears: Int, weightKg: Float) {
        storage.setUserAgeYears(ageYears)
        storage.setUserWeightKg(weightKg)
        userAgeYears = ageYears
        userWeightKg = weightKg
        profileDialogVisible = false
    }

    private val _bikeRides = MutableStateFlow<List<BikeRide>>(emptyList())
    val bikeRides: StateFlow<List<BikeRide>> = _bikeRides.asStateFlow()

    private val _bikeStats = MutableStateFlow(BikeStats())
    val bikeStats: StateFlow<BikeStats> = _bikeStats.asStateFlow()

    var bikeLoading by mutableStateOf(false)
        private set

    var bikeRangeDays by mutableIntStateOf(DEFAULT_BIKE_RANGE_DAYS)
        private set

    var bikeFilter by mutableStateOf(BikeFilter.ALL)
        private set

    var bikeDefaultMode by mutableStateOf(BikeModeOverrides.defaultMode(application))
        private set

    var selectedRideId: String? by mutableStateOf(null)
        private set

    private var loadedBikeRangeDays = 0

    fun loadBikeRides(force: Boolean = false) {
        if (bikeLoading) return
        if (!force && loadedBikeRangeDays == bikeRangeDays) return
        bikeLoading = true
        viewModelScope.launch {
            val factor = storage.calorieFactor()
            val autoRides = runCatching {
                BikeRideRepository.ridesForLastDays(
                    getApplication(),
                    bikeRangeDays,
                    factor
                )
            }.getOrDefault(emptyList())
            val recordedRides = runCatching {
                RideBikeAdapter.ridesForLastDays(
                    getApplication(),
                    bikeRangeDays,
                    factor
                )
            }.getOrDefault(emptyList())
            val rides = (
                    recordedRides +
                            RideBikeAdapter.withoutOverlappingAuto(autoRides, recordedRides)
                    ).sortedByDescending { it.startTime }
            _bikeRides.value = rides
            _bikeStats.value = BikeRideRepository.statsFor(rides)
            loadedBikeRangeDays = bikeRangeDays
            bikeLoading = false
        }
    }

    fun changeBikeRange(days: Int) {
        if (days == bikeRangeDays) return
        bikeRangeDays = days
        selectedRideId = null
        loadBikeRides(force = true)
    }

    fun changeBikeFilter(filter: BikeFilter) {
        bikeFilter = filter
    }

    fun openRide(rideId: String) {
        selectedRideId = rideId
    }

    fun closeRide() {
        selectedRideId = null
    }

    fun setRideMode(rideId: String, mode: String?) {
        BikeModeOverrides.setMode(getApplication(), rideId, mode)
        val factor = storage.calorieFactor()
        _bikeRides.value = _bikeRides.value.map { ride ->
            if (ride.rideId != rideId) {
                ride
            } else {
                val resolved = mode
                    ?: if (bikeDefaultMode == BIKE_MODE_AUTO) ride.autoMode else bikeDefaultMode
                ride.copy(
                    mode = resolved,
                    overridden = mode != null,
                    calories = BikeRideRepository.caloriesFor(
                        resolved,
                        ride.distanceKm,
                        ride.durationMs,
                        factor
                    )
                )
            }
        }
        _bikeStats.value = BikeRideRepository.statsFor(_bikeRides.value)
        refreshStats()
    }

    fun changeBikeDefaultMode(mode: String) {
        if (mode == bikeDefaultMode) return
        BikeModeOverrides.setDefaultMode(getApplication(), mode)
        bikeDefaultMode = mode
        loadBikeRides(force = true)
        refreshStats()
    }

    val exercises: List<Exercise> = ExerciseRepository.all()

    var selectedExerciseId: String? by mutableStateOf(null)
        private set

    var exercisesGroupFilter: com.tabslify.tabs.fitnesstab.data.MuscleGroup? by mutableStateOf(null)

    var exercisesDifficultyFilter: com.tabslify.tabs.fitnesstab.data.Difficulty? by mutableStateOf(null)

    var exercisesEquipmentFilter: com.tabslify.tabs.fitnesstab.data.Equipment? by mutableStateOf(null)

    var exercisesSearch: String by mutableStateOf("")

    val selectedExercise: Exercise?
        get() = selectedExerciseId?.let { id -> ExerciseRepository.findById(id) }

    // ---- MANUAL WORKOUT STATE ----
    var workoutMode by mutableStateOf(WorkoutMode.CAMERA_POSE)
        private set

    var workoutName by mutableStateOf("")

    var workoutStartedAtMs by mutableLongStateOf(0L)
        private set

    var workoutElapsedMs by mutableLongStateOf(0L)
        private set

    private var workoutTimerJob: Job? = null

    data class EditableWorkoutEntry(
        val entryId: String,
        val exerciseId: String,
        val sets: SnapshotStateList<ExerciseSet> = mutableStateListOf()
    )

    val editableEntries: List<EditableWorkoutEntry>
        field = mutableStateListOf<EditableWorkoutEntry>()

    var exercisePickerVisible by mutableStateOf(false)
        private set

    var restTimerSeconds by mutableIntStateOf(90)

    var restTimerRunning by mutableStateOf(false)
        private set

    var restTimerRemaining by mutableIntStateOf(90)

    private var restTimerJob: Job? = null

    fun changeWorkoutMode(mode: WorkoutMode) {
        workoutMode = mode
        if (mode == WorkoutMode.MANUAL && workoutStartedAtMs == 0L) {
            startManualWorkoutTimer()
        }
    }

    fun closeExercisePicker() {
        exercisePickerVisible = false
    }

    fun addExerciseToWorkout(exerciseId: String) {
        val existing = editableEntries.find { it.exerciseId == exerciseId }
        if (existing != null) {
            existing.sets.add(
                ExerciseSet(setNumber = existing.sets.size + 1)
            )
            return
        }
        val entryId = "entry_${System.currentTimeMillis()}_${exerciseId}"
        val entry = EditableWorkoutEntry(
            entryId = entryId,
            exerciseId = exerciseId,
            sets = mutableStateListOf(ExerciseSet(setNumber = 1))
        )
        editableEntries.add(entry)
        if (workoutStartedAtMs == 0L) startManualWorkoutTimer()
    }

    fun removeWorkoutEntry(entryId: String) {
        val idx = editableEntries.indexOfFirst { it.entryId == entryId }
        if (idx >= 0) editableEntries.removeAt(idx)
    }

    fun addSetToEntry(entryId: String) {
        val entry = editableEntries.find { it.entryId == entryId } ?: return
        entry.sets.add(ExerciseSet(setNumber = entry.sets.size + 1))
    }

    fun removeSetFromEntry(entryId: String, setIndex: Int) {
        val entry = editableEntries.find { it.entryId == entryId } ?: return
        if (setIndex in entry.sets.indices) {
            entry.sets.removeAt(setIndex)
            entry.sets.forEachIndexed { i, s ->
                entry.sets[i] = s.copy(setNumber = i + 1)
            }
        }
    }

    fun updateSetInEntry(
        entryId: String,
        setIndex: Int,
        reps: Int? = null,
        weightKg: Float? = null,
        durationSeconds: Int? = null,
        completed: Boolean? = null
    ) {
        val entry = editableEntries.find { it.entryId == entryId } ?: return
        if (setIndex !in entry.sets.indices) return
        val old = entry.sets[setIndex]
        entry.sets[setIndex] = old.copy(
            reps = reps ?: old.reps,
            weightKg = weightKg ?: old.weightKg,
            durationSeconds = durationSeconds ?: old.durationSeconds,
            completed = completed ?: old.completed
        )
    }

    private fun startWorkoutTicker() {
        workoutTimerJob?.cancel()
        workoutTimerJob = viewModelScope.launch {
            while (workoutStartedAtMs > 0L) {
                workoutElapsedMs = System.currentTimeMillis() - workoutStartedAtMs
                delay(500L.milliseconds)
            }
        }
    }

    private fun startManualWorkoutTimer() {
        workoutStartedAtMs = System.currentTimeMillis()
        startWorkoutTicker()
    }

    fun startRestTimer() {
        restTimerRemaining = restTimerSeconds
        restTimerRunning = true
        restTimerJob?.cancel()
        restTimerJob = viewModelScope.launch {
            while (restTimerRemaining > 0) {
                delay(1000L.milliseconds)
                restTimerRemaining--
            }
            restTimerRunning = false
        }
    }

    fun resetRestTimer() {
        restTimerJob?.cancel()
        restTimerRunning = false
        restTimerRemaining = restTimerSeconds
    }

    fun saveWorkoutSession() {
        if (editableEntries.isEmpty()) return
        val now = System.currentTimeMillis()
        val start = if (workoutStartedAtMs > 0L) workoutStartedAtMs else now
        val entries = editableEntries.map { e ->
            WorkoutEntry(
                entryId = e.entryId,
                exerciseId = e.exerciseId,
                sets = e.sets.toList(),
                startedAtMs = start,
                finishedAtMs = now
            )
        }

        var calories = 0f
        val minutes = ((now - start) / 60_000f).coerceAtLeast(1f)
        val exIds = editableEntries.map { it.exerciseId }.distinct()
        val factor = storage.calorieFactor()
        for (exId in exIds) {
            val ex = ExerciseRepository.findById(exId)
            val perMinute = ex?.caloriesPerMinute ?: 5f
            calories += perMinute * factor * (minutes / exIds.size.coerceAtLeast(1))
        }

        val session = WorkoutSession(
            sessionId = "manual_${now}",
            dateStartMs = start,
            dateEndMs = now,
            entries = entries,
            totalCalories = calories,
            name = workoutName.trim()
        )
        storage.saveSession(session)
        discardWorkoutInternal()
        refreshStats()
        currentScreen = FitnessScreen.DASHBOARD
    }

    fun discardWorkout() {
        discardWorkoutInternal()
    }

    private fun discardWorkoutInternal() {
        editableEntries.clear()
        workoutName = ""
        workoutStartedAtMs = 0L
        workoutElapsedMs = 0L
        workoutTimerJob?.cancel()
        workoutTimerJob = null
        restTimerJob?.cancel()
        restTimerJob = null
        restTimerRunning = false
        restTimerRemaining = restTimerSeconds
    }

    fun deleteSession(sessionId: String) {
        if (ExploreActivityBridge.isExploreSession(sessionId)) return
        if (RideSessionAdapter.isRideSession(sessionId)) {
            viewModelScope.launch {
                val rideId = sessionId.removePrefix(RideSessionAdapter.RIDE_SESSION_PREFIX)
                runCatching { RideRepository(getApplication<Application>()).deleteRideCascade(rideId) }
                refreshStats()
                loadBikeRides(force = true)
            }
            return
        }
        storage.deleteSession(sessionId)
        refreshStats()
    }

    // ---- END MANUAL WORKOUT ----

    val repsPerMinute: Float
        get() = if (elapsedMs < 3_000L || reps == 0) 0f else reps * 60_000f / elapsedMs

    init {
        RideRecovery.recover(application)
        refreshStats()
    }

    fun switchTo(screen: FitnessScreen) {
        val wasWorkoutScreen = currentScreen == FitnessScreen.WORKOUT
        currentScreen = screen
        selectedExerciseId = null
        selectedRideId = null
        if (screen == FitnessScreen.BIKE) loadBikeRides()
        if (wasWorkoutScreen && screen != FitnessScreen.WORKOUT) {
            timerJob?.cancel()
            timerJob = null
            workoutTimerJob?.cancel()
            workoutTimerJob = null
            if (captureActive) {
                captureRecorder?.event("screen_exit", "reps=$reps")
                captureRecorder?.stop()
                captureActive = false
            }
        } else if (screen == FitnessScreen.WORKOUT) {
            if (isRunning && timerJob == null) {
                timerJob = viewModelScope.launch {
                    while (isRunning) {
                        elapsedMs = SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs
                        delay(250L.milliseconds)
                    }
                }
            }
            if (workoutStartedAtMs > 0L && workoutTimerJob == null) {
                startWorkoutTicker()
            }
        }
    }

    fun openManualWorkout(startExerciseId: String? = null) {
        changeWorkoutMode(WorkoutMode.MANUAL)
        if (startExerciseId != null) addExerciseToWorkout(startExerciseId)
        currentScreen = FitnessScreen.WORKOUT
    }

    fun openCameraWorkout() {
        changeWorkoutMode(WorkoutMode.CAMERA_POSE)
        currentScreen = FitnessScreen.WORKOUT
    }

    fun openExerciseDetail(exerciseId: String) {
        selectedExerciseId = exerciseId
    }

    fun navigateBackFromDetail() {
        selectedExerciseId = null
    }

    fun refreshStats() {
        viewModelScope.launch {
            val factor = storage.calorieFactor()
            val exploreSessions = runCatching {
                ExploreActivityBridge.sessionsForLastDays(
                    getApplication(),
                    EXPLORE_HISTORY_DAYS,
                    factor
                )
            }.getOrDefault(emptyList())
            val rideSessions = runCatching {
                RideSessionAdapter.sessionsForLastDays(
                    getApplication(),
                    EXPLORE_HISTORY_DAYS,
                    factor
                )
            }.getOrDefault(emptyList())
            val passiveSessions =
                RideSessionAdapter.withoutOverlappingPassiveSessions(exploreSessions, rideSessions)
            val sessions = passiveSessions + rideSessions

            _stats.value = storage.getFitnessStats(sessions)
            _recentDays.value = storage.loadLastNDays(7, sessions)
            _sessions.value = storage.mergeWithStored(sessions)
                .sortedByDescending { it.dateStartMs }
                .take(50)
        }
    }

    fun saveSessionFromPushUps(): WorkoutSession? {
        if (reps <= 0 && elapsedMs < 10_000L) return null
        val now = System.currentTimeMillis()
        val startMs = sessionStartedAtMs.takeIf { it > 0L } ?: (now - elapsedMs)
        val factor = storage.calorieFactor()
        val kcal = ((reps * 0.35f + (elapsedMs / 60_000f) * 6f) * factor).coerceAtLeast(0f)

        val entry = if (reps > 0) {
            listOf(
                WorkoutEntry(
                    entryId = "pushup_entry_${now}",
                    exerciseId = "chest_pushups",
                    sets = listOf(
                        ExerciseSet(
                            setNumber = 1,
                            reps = reps,
                            durationSeconds = (elapsedMs / 1000L).toInt(),
                            completed = true
                        )
                    ),
                    startedAtMs = startMs,
                    finishedAtMs = now
                )
            )
        } else {
            emptyList()
        }

        val session = WorkoutSession(
            sessionId = "pushup_${now}",
            dateStartMs = startMs,
            dateEndMs = now,
            entries = entry,
            totalCalories = kcal,
            name = "",
            repTimesMs = detectedRepTimes.toList()
        )
        storage.saveSession(session)
        refreshStats()
        return session
    }

    fun decrementReps() {
        if (reps <= 0) return
        reps--
        if (detectedRepTimes.isNotEmpty()) {
            detectedRepTimes.removeAt(detectedRepTimes.lastIndex)
        }
    }

    fun openPushUpEditorForSession(sessionId: String) {
        if (!sessionId.startsWith("pushup_")) return
        val session = storage.loadAllSessions().firstOrNull { it.sessionId == sessionId } ?: return
        pushUpBestBeforeSession = personalBest
        openPushUpEditor(session, fromHistory = true)
    }

    private fun openPushUpEditor(session: WorkoutSession, fromHistory: Boolean) {
        editingPushUpSession = session
        val sessionReps = session.entries.sumOf { entry -> entry.sets.sumOf { it.reps } }
        pushUpEditor = PushUpEditorState(
            sessionId = session.sessionId,
            durationMs = (session.dateEndMs - session.dateStartMs).coerceAtLeast(0L),
            originalReps = sessionReps,
            repTimesMs = session.repTimesMs,
            fromHistory = fromHistory
        )
    }

    fun applyPushUpEdit(reps: Int, repTimesMs: List<Long>) {
        val session = editingPushUpSession ?: return
        val editor = pushUpEditor ?: return
        val corrected = reps.coerceIn(0, 9999)
        val durationMs = (session.dateEndMs - session.dateStartMs).coerceAtLeast(0L)
        val existing = session.entries.firstOrNull()
        val entry = WorkoutEntry(
            entryId = existing?.entryId ?: "pushup_entry_${session.dateEndMs}",
            exerciseId = "chest_pushups",
            sets = listOf(
                ExerciseSet(
                    setNumber = 1,
                    reps = corrected,
                    durationSeconds = (durationMs / 1000L).toInt(),
                    completed = true
                )
            ),
            notes = existing?.notes.orEmpty(),
            startedAtMs = existing?.startedAtMs ?: session.dateStartMs,
            finishedAtMs = existing?.finishedAtMs ?: session.dateEndMs
        )
        val factor = storage.calorieFactor()
        val kcal = (((corrected * 0.35f) + (durationMs / 60_000f) * 6f) * factor)
            .coerceAtLeast(0f)
        val updated = session.copy(
            entries = listOf(entry),
            totalCalories = kcal,
            repTimesMs = repTimesMs
        )
        storage.saveSession(updated)
        if (!editor.fromHistory) {
            this.reps = corrected
            detectedRepTimes.clear()
            detectedRepTimes.addAll(repTimesMs)
        }
        applyPersonalBest(corrected, editor.fromHistory)
        refreshStats()
        pushUpEditor = null
        editingPushUpSession = null
    }

    fun dismissPushUpEditor() {
        val editor = pushUpEditor ?: return
        if (!editor.fromHistory) {
            applyPersonalBest(editor.originalReps, fromHistory = false)
        }
        pushUpEditor = null
        editingPushUpSession = null
    }

    private fun applyPersonalBest(reps: Int, fromHistory: Boolean) {
        if (fromHistory) {
            if (reps > personalBest) {
                personalBest = reps
                prefs.edit { putInt(KEY_BEST, reps) }
                isNewRecord = true
            }
            return
        }
        if (reps > pushUpBestBeforeSession) {
            personalBest = reps
            prefs.edit { putInt(KEY_BEST, reps) }
            isNewRecord = true
        } else {
            personalBest = pushUpBestBeforeSession
            isNewRecord = false
        }
    }

    fun dismissHint() {
        showHint = false
        prefs.edit { putBoolean(KEY_SEEN_HINT, true) }
    }

    fun toggleSession() {
        if (isRunning) stopSession() else startSession()
    }

    fun startSession() {
        counter.reset()
        extractor.reset()
        counterV3.reset()
        extractorV3.reset()
        gravityBumpDetector.reset()
        debugSignalLine = ""
        lostFrames = 0
        lastRepAtMs = 0L
        reps = 0
        detectedRepTimes.clear()
        trackingIssue = TrackingIssue.CALIBRATING
        phase = PushUpPhase.UNKNOWN
        formHintRes = null
        isNewRecord = false
        elapsedMs = 0L
        sessionStartedAtMs = System.currentTimeMillis()
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        isRunning = true
        if (captureActive) {
            captureRecorder?.event("session_start", "engine=${if (useV3) "v3" else "v2"}")
        }
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isRunning) {
                elapsedMs = SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs
                delay(250L.milliseconds)
            }
        }
    }

    fun stopSession() {
        val wasRunning = isRunning
        isRunning = false
        timerJob?.cancel()
        timerJob = null
        phase = PushUpPhase.UNKNOWN
        formHintRes = null
        pushUpBestBeforeSession = personalBest
        isNewRecord = false
        if (captureActive) captureRecorder?.event("session_end", "reps=$reps")
        if (wasRunning) {
            val session = saveSessionFromPushUps()
            if (session != null) openPushUpEditor(session, fromHistory = false)
        }
    }

    fun resetSession() {
        isRunning = false
        timerJob?.cancel()
        timerJob = null
        phase = PushUpPhase.UNKNOWN
        formHintRes = null
        counter.reset()
        extractor.reset()
        lostFrames = 0
        lastRepAtMs = 0L
        reps = 0
        detectedRepTimes.clear()
        elapsedMs = 0L
        isNewRecord = false
        formHintRes = null
        pushUpEditor = null
        editingPushUpSession = null
        counterV3.reset()
        extractorV3.reset()
        gravityBumpDetector.reset()
        debugSignalLine = ""
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        counter.reset()
        extractor.reset()
        counterV3.reset()
        extractorV3.reset()
        gravityBumpDetector.reset()
    }

    fun toggleV3() {
        if (isRunning) return
        useV3 = !useV3
        resetDetectors(preserveCount = false)
    }

    fun toggleCapture() {
        if (!BuildConfig.IS_DEV) return
        if (captureActive) {
            captureRecorder?.event("session_stop", "reps=$reps")
            captureRecorder?.stop()
            captureActive = false
            captureError = null
        } else {
            val source = gravitySource ?: PushUpGravitySource(getApplication()).also {
                gravitySource = it
            }
            val recorder = captureRecorder ?: PushUpCaptureRecorder(getApplication(), source).also {
                captureRecorder = it
            }
            val started = recorder.start(capturePreset.label)
            captureId = started
            captureActive = started != null
            captureError = if (started == null) "Aufnahme konnte nicht gestartet werden" else null
        }
    }

    fun cycleCapturePreset() {
        if (!BuildConfig.IS_DEV || captureActive) return
        capturePresetIndex = (capturePresetIndex + 1) % PUSH_UP_CAPTURE_PRESETS.size
    }

    private fun resetDetectors(preserveCount: Boolean) {
        if (!preserveCount) {
            counter.reset()
            counterV3.reset()
        } else {
            counterV3.onPoseLost()
        }
        extractor.reset()
        extractorV3.reset()
        gravityBumpDetector.reset()
        debugSignalLine = ""
        lostFrames = 0
    }

    fun onPoseFrame(newFrame: PoseFrame) {
        frame = newFrame
        if (captureActive) {
            val recorder = captureRecorder
            recorder?.record(newFrame)
            if (recorder == null || !recorder.isRecording()) {
                captureActive = false
                captureError = "Aufnahme abgebrochen"
            }
        }
        if (useV3) {
            if (consumeGravityBump()) {
                counterV3.onPoseLost()
                extractorV3.reset()
            }
            onPoseFrameV3(newFrame)
        } else {
            onPoseFrameLegacy(newFrame)
        }
    }

    private fun consumeGravityBump(): Boolean {
        val source = gravitySource ?: PushUpGravitySource(getApplication()).also {
            gravitySource = it
        }
        val sample = source.latest ?: return false
        return gravityBumpDetector.update(sample.x, sample.y, sample.z)
    }

    private fun onPoseFrameLegacy(newFrame: PoseFrame) {
        val now = newFrame.timestampMs
        val (imageLandmarks, worldLandmarks) = newFrame.toLandmark3Lists()
        val signals = extractor.analyze(
            image = imageLandmarks,
            world = worldLandmarks,
            imageWidth = newFrame.imageWidth,
            imageHeight = newFrame.imageHeight,
            timestampMs = now
        )
        poseDetected = signals.hasBody

        if (!signals.hasBody) {
            lostFrames++
            if (lostFrames >= LOST_FRAME_GRACE) {
                counter.onPoseLost()
                phase = PushUpPhase.UNKNOWN
                trackingIssue = TrackingIssue.NO_BODY
            }
            return
        }
        lostFrames = 0

        if (!isRunning) {
            if (!signals.elbowAngle.isNaN()) {
                elbowAngle = signals.elbowAngle.roundToInt()
            }
            trackingIssue = when {
                !signals.armsVisible -> TrackingIssue.ARMS_HIDDEN
                !signals.fullyInFrame -> TrackingIssue.NO_BODY
                !signals.postureHorizontal -> TrackingIssue.NOT_IN_POSITION
                else -> TrackingIssue.NONE
            }
            return
        }

        val sample = PushUpSample(
            timestampMs = now,
            armExtension = signals.armExtension,
            elbowAngle = signals.elbowAngle,
            elbowConfident = signals.elbowConfident,
            wristPlantedTop = signals.wristPlantedTop,
            wristPlantedWide = signals.wristPlantedWide,
            postureHorizontal = signals.postureHorizontal,
            valid = signals.valid
        )
        val rep = counter.submit(sample)
        phase = counter.phase
        if (counter.smoothedAngle < PushUpCounter.MAX_ANGLE) {
            elbowAngle = counter.smoothedAngle.roundToInt()
        }

        if (rep != null) {
            reps++
            detectedRepTimes.add(now - sessionStartedAtElapsedMs)
            lastRepAtMs = now
            if (captureActive) {
                captureRecorder?.event("rep", "index=${rep.index} angle=${rep.depthAngle}")
            }
        }

        trackingIssue = when {
            !signals.armsVisible -> TrackingIssue.ARMS_HIDDEN
            !signals.fullyInFrame -> TrackingIssue.NO_BODY
            !signals.postureHorizontal -> TrackingIssue.NOT_IN_POSITION
            !counter.calibrated -> TrackingIssue.CALIBRATING
            else -> TrackingIssue.NONE
        }

        formHintRes = resolveHint(signals)
    }

    private fun onPoseFrameV3(newFrame: PoseFrame) {
        val now = newFrame.timestampMs
        val (imageLandmarks, worldLandmarks) = newFrame.toLandmark3Lists()
        val signals = extractorV3.analyze(
            image = imageLandmarks,
            world = worldLandmarks,
            imageWidth = newFrame.imageWidth,
            imageHeight = newFrame.imageHeight,
            timestampMs = now
        )
        poseDetected = signals.hasBody
        debugSignalLine = buildDebugLine(signals)

        if (!signals.hasBody) {
            lostFrames++
            if (lostFrames >= LOST_FRAME_GRACE) {
                counterV3.onPoseLost()
                phase = PushUpPhase.UNKNOWN
                trackingIssue = TrackingIssue.NO_BODY
            }
            return
        }
        lostFrames = 0

        if (!isRunning) {
            if (signals.armAngle.isFinite()) elbowAngle = signals.armAngle.roundToInt()
            trackingIssue = mapIssue(signals.issue, counterV3.calibrated)
            return
        }

        val sample = PushUpSampleV3(
            timestampMs = now,
            armAngle = signals.armAngle,
            angleQuality = signals.armAngleQuality,
            angleSource = signals.armAngleSource,
            heightRatio = signals.heightRatio,
            heightQuality = signals.heightQuality,
            torsoInclination = signals.torsoInclination,
            torsoQuality = signals.torsoQuality,
            supportValid = signals.supportValid,
            supportConfidence = signals.supportConfidence,
            wristStable = signals.wristStable,
            viewUsable = signals.viewUsable,
            postureSupported = signals.postureSupported,
            sceneChanged = signals.sceneChanged,
            sideChanged = signals.sideChanged,
            issue = signals.issue
        )
        val rep = counterV3.submit(sample)
        phase = counterV3.phase
        if (counterV3.smoothedAngle.isFinite()) elbowAngle = counterV3.smoothedAngle.roundToInt()

        if (rep != null) {
            reps++
            detectedRepTimes.add(now - sessionStartedAtElapsedMs)
            lastRepAtMs = now
            if (captureActive) {
                captureRecorder?.event("rep_v3", "index=${rep.index} angle=${rep.depthAngle} state=${counterV3.stateName}")
            }
        }

        val effectiveIssue = when {
            counterV3.issue != PushUpBlockReason.NONE -> counterV3.issue
            signals.issue != PushUpBlockReason.NONE -> signals.issue
            else -> PushUpBlockReason.NONE
        }
        trackingIssue = mapIssue(effectiveIssue, counterV3.calibrated)
        formHintRes = resolveHintV3(signals)
    }

    private fun buildDebugLine(signals: PushUpFrameSignalsV3): String {
        val angle = if (signals.armAngle.isFinite()) signals.armAngle.roundToInt().toString() else "-"
        val torso = if (signals.torsoInclination.isFinite()) {
            signals.torsoInclination.roundToInt().toString()
        } else {
            "-"
        }
        val height = if (signals.heightRatio.isFinite()) {
            "${(signals.heightRatio * 100f).roundToInt()}%"
        } else {
            "-"
        }
        val quality = (signals.armAngleQuality * 100f).roundToInt()
        val hip = if (signals.hipSupportRatio.isFinite()) {
            "${(signals.hipSupportRatio * 100f).roundToInt()}%"
        } else {
            "-"
        }
        return "a=$angle i=$torso h=$height hip=$hip q=$quality ${signals.viewClass.name} ${counterV3.stateName}"
    }

    private fun mapIssue(issue: PushUpBlockReason, calibrated: Boolean): TrackingIssue = when (issue) {
        PushUpBlockReason.NONE -> if (calibrated) TrackingIssue.NONE else TrackingIssue.CALIBRATING
        PushUpBlockReason.NO_BODY -> TrackingIssue.NO_BODY
        PushUpBlockReason.ARMS_HIDDEN -> TrackingIssue.ARMS_HIDDEN
        PushUpBlockReason.NOT_IN_POSITION -> TrackingIssue.NOT_IN_POSITION
        PushUpBlockReason.SUPPORT_HIDDEN -> TrackingIssue.SUPPORT_HIDDEN
        PushUpBlockReason.HANDS_NOT_PLANTED -> TrackingIssue.HANDS_NOT_PLANTED
        PushUpBlockReason.EXTEND_ARMS -> TrackingIssue.EXTEND_ARMS
        PushUpBlockReason.CAMERA_ANGLE -> TrackingIssue.CAMERA_ANGLE
        PushUpBlockReason.LOW_SIGNAL_QUALITY -> TrackingIssue.LOW_SIGNAL_QUALITY
        PushUpBlockReason.TRACKING_UNSTABLE -> TrackingIssue.TRACKING_UNSTABLE
        PushUpBlockReason.CAMERA_MOVED -> TrackingIssue.CAMERA_MOVED
    }

    private fun resolveHint(signals: PushUpSignals): Int? {
        if (!signals.hipAngle.isNaN() && signals.hipAngle < 152f) {
            return R.string.fitness_form_huefte
        }
        if (counter.needsTopHint) return R.string.fitness_strecken
        if (counter.feedback == PushUpFeedback.TOO_SHALLOW) return R.string.fitness_form_tiefer
        return null
    }

    private fun resolveHintV3(signals: PushUpFrameSignalsV3): Int? {
        if (signals.hipAngle.isFinite() && signals.hipAngle < 152f) {
            return R.string.fitness_form_huefte
        }
        if (counterV3.needsTopHint) return R.string.fitness_strecken
        if (counterV3.feedback == PushUpFeedback.TOO_SHALLOW) return R.string.fitness_form_tiefer
        return null
    }

    override fun onCleared() {
        timerJob?.cancel()
        workoutTimerJob?.cancel()
        restTimerJob?.cancel()
        if (captureActive) {
            captureRecorder?.stop()
            captureActive = false
        }
        gravitySource?.close()
    }

    companion object {
        private const val LOST_FRAME_GRACE = 8
        private const val EXPLORE_HISTORY_DAYS = 30
        private const val DEFAULT_BIKE_RANGE_DAYS = 30
    }
}
