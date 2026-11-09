package com.tabslify.tabs.fitnesstab

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

enum class PushUpPhase { UNKNOWN, UP, DOWN }

enum class PushUpFeedback { NONE, TOO_SHALLOW }

data class PushUpRep(
    val index: Int,
    val depthAngle: Float,
    val durationMs: Long
)

data class PushUpSample(
    val timestampMs: Long,
    val elbowAngle: Float,
    val bodyAngle: Float,
    val torsoInclination: Float,
    val poseConfidence: Float,
    val armsVisible: Boolean,
    val fullyInFrame: Boolean,
    val wristsPlanted: Boolean,
    val valid: Boolean
)

private enum class CounterState { IDLE, CALIBRATING, TOP, DESCENDING, ASCENDING, UNKNOWN }

class PushUpCounter(
    private val calibrationMs: Long = 3000L,
    private val minRepDurationMs: Long = 650L,
    private val maxRepDurationMs: Long = 12_000L,
    private val refractoryMs: Long = 700L,
    private val minAmplitudeDeg: Float = 30f,
    private val entryTopAngle: Float = 150f,
    private val entryBodyAngle: Float = 155f,
    private val minRepBodyAngle: Float = 145f,
    private val maxTorsoInclination: Float = 45f,
    private val descendHysteresis: Float = 12f,
    private val recoverHysteresis: Float = 8f,
    private val holdMs: Long = 100L,
    private val holdSamples: Int = 3,
    private val frameGapAbortMs: Long = 400L,
    private val minRepFramePresence: Float = 0.8f,
    private val bodyStdLimit: Float = 10f,
    private val calibrationBodyStdLimit: Float = 8f,
    private val torsoStdLimit: Float = 10f,
    private val calibrationTorsoStdLimit: Float = 8f,
    private val baselineBodyTolerance: Float = 20f,
    private val baselineTorsoTolerance: Float = 20f,
    private val resumeMs: Long = 500L,
    private val windowSize: Int = 5
) {
    private val angleWindow = ArrayDeque<Float>()
    private val bodyWindow = ArrayDeque<Float>()
    private val torsoWindow = ArrayDeque<Float>()
    private val calibrationAngles = ArrayList<Float>()
    private val calibrationBodies = ArrayList<Float>()
    private val calibrationTorsos = ArrayList<Float>()

    private var state = CounterState.IDLE
    private var calibrationStartedAtMs = 0L
    private var descendCandidateSinceMs = 0L
    private var recoverCandidateSinceMs = 0L
    private var descendCandidateSamples = 0
    private var recoverCandidateSamples = 0
    private var repStartedAtMs = 0L
    private var minElbow = MAX_ANGLE
    private var peakElbow = MAX_ANGLE
    private var depthReached = false
    private var repValidFrames = 0
    private var repTotalFrames = 0
    private var lastValidAtMs = 0L
    private var lastPostureOkAtMs = 0L
    private var lastCountAtMs = 0L
    private var stableSinceMs = 0L
    private var baselineTopAngle = MAX_ANGLE
    private var baselineBodyAngle = MAX_ANGLE
    private var baselineTorsoInclination = 0f

    var phase = PushUpPhase.UNKNOWN
        private set

    var count = 0
        private set

    var smoothedAngle = MAX_ANGLE
        private set

    var feedback = PushUpFeedback.NONE
        private set

    var calibrated = false
        private set

    var calibrationRemainingMs = calibrationMs
        private set

    var calibrationProgress = 0f
        private set

    fun reset() {
        angleWindow.clear()
        bodyWindow.clear()
        torsoWindow.clear()
        calibrationAngles.clear()
        calibrationBodies.clear()
        calibrationTorsos.clear()
        state = CounterState.IDLE
        calibrationStartedAtMs = 0L
        descendCandidateSinceMs = 0L
        recoverCandidateSinceMs = 0L
        descendCandidateSamples = 0
        recoverCandidateSamples = 0
        clearRep()
        lastValidAtMs = 0L
        lastPostureOkAtMs = 0L
        lastCountAtMs = 0L
        stableSinceMs = 0L
        baselineTopAngle = MAX_ANGLE
        baselineBodyAngle = MAX_ANGLE
        baselineTorsoInclination = 0f
        calibrated = false
        calibrationRemainingMs = calibrationMs
        calibrationProgress = 0f
        phase = PushUpPhase.UNKNOWN
        count = 0
        smoothedAngle = MAX_ANGLE
        feedback = PushUpFeedback.NONE
    }

    fun onPoseLost() {
        angleWindow.clear()
        bodyWindow.clear()
        torsoWindow.clear()
        if (calibrated) {
            state = CounterState.UNKNOWN
        } else {
            resetCalibrationBuffers()
            state = CounterState.IDLE
        }
        clearRep()
        lastValidAtMs = 0L
        lastPostureOkAtMs = 0L
        stableSinceMs = 0L
        phase = PushUpPhase.UNKNOWN
        feedback = PushUpFeedback.NONE
    }

    fun submit(sample: PushUpSample): PushUpRep? {
        val now = sample.timestampMs

        if (!sample.valid) {
            val gap = if (lastValidAtMs > 0L) now - lastValidAtMs else Long.MAX_VALUE
            if (gap > frameGapAbortMs) {
                clearRep()
                if (calibrated) {
                    state = CounterState.UNKNOWN
                } else {
                    resetCalibrationBuffers()
                    state = CounterState.IDLE
                }
            }
            if (!calibrated && calibrationStartedAtMs > 0L) {
                calibrationRemainingMs =
                    (calibrationMs - (now - calibrationStartedAtMs)).coerceAtLeast(0L)
            } else if (!calibrated) {
                calibrationRemainingMs = calibrationMs
            }
            updatePhase()
            return null
        }

        val gap = if (lastValidAtMs > 0L) now - lastValidAtMs else 0L
        lastValidAtMs = now
        if (gap > frameGapAbortMs) {
            clearRep()
            if (calibrated) {
                state = CounterState.UNKNOWN
            } else {
                resetCalibrationBuffers()
            }
        }

        val smoothed = smooth(sample.elbowAngle)
        smoothedAngle = smoothed
        addWindow(bodyWindow, sample.bodyAngle)
        addWindow(torsoWindow, sample.torsoInclination)

        val bodyStable = isStable(
            bodyWindow,
            if (calibrated) bodyStdLimit else calibrationBodyStdLimit
        )
        val torsoStable = isStable(
            torsoWindow,
            if (calibrated) torsoStdLimit else calibrationTorsoStdLimit
        )
        val postureOk = sample.wristsPlanted &&
            sample.fullyInFrame &&
            sample.bodyAngle >= (if (inRep()) minRepBodyAngle else entryBodyAngle) &&
            sample.torsoInclination <= maxTorsoInclination
        val baselineOk = !calibrated || (
            abs(sample.bodyAngle - baselineBodyAngle) <= baselineBodyTolerance &&
                abs(sample.torsoInclination - baselineTorsoInclination) <= baselineTorsoTolerance
            )

        if (!calibrated) {
            return handleCalibration(sample, smoothed, postureOk, bodyStable, torsoStable)
        }

        if (state == CounterState.IDLE || state == CounterState.UNKNOWN) {
            if (postureOk && baselineOk && bodyStable && torsoStable &&
                smoothed >= baselineTopAngle - recoverHysteresis
            ) {
                if (stableSinceMs == 0L) stableSinceMs = now
                if (now - stableSinceMs >= resumeMs) {
                    state = CounterState.TOP
                    descendCandidateSinceMs = 0L
                    descendCandidateSamples = 0
                    feedback = PushUpFeedback.NONE
                }
            } else {
                stableSinceMs = 0L
            }
            updatePhase()
            return null
        }

        if (!postureOk || !baselineOk || !bodyStable || !torsoStable) {
            if (inRep() && now - lastPostureOkAtMs > frameGapAbortMs) {
                abortRep()
            }
            updatePhase()
            return null
        }
        lastPostureOkAtMs = now

        if (now - repStartedAtMs > maxRepDurationMs && inRep()) {
            abortRep()
            updatePhase()
            return null
        }

        when (state) {
            CounterState.TOP -> {
                if (smoothed <= baselineTopAngle - descendHysteresis) {
                    if (descendCandidateSinceMs == 0L) descendCandidateSinceMs = now
                    descendCandidateSamples++
                    if (now - descendCandidateSinceMs >= holdMs &&
                        descendCandidateSamples >= holdSamples
                    ) {
                        startRep(now, smoothed)
                    }
                } else {
                    descendCandidateSinceMs = 0L
                    descendCandidateSamples = 0
                }
            }

            CounterState.DESCENDING -> {
                countRepFrame(sample)
                updateRepDepth(smoothed)
                if (smoothed >= minElbow + 10f) {
                    if (recoverCandidateSinceMs == 0L) recoverCandidateSinceMs = now
                    recoverCandidateSamples++
                    if (now - recoverCandidateSinceMs >= 60L && recoverCandidateSamples >= 2) {
                        state = CounterState.ASCENDING
                        recoverCandidateSinceMs = 0L
                        recoverCandidateSamples = 0
                    }
                } else {
                    recoverCandidateSinceMs = 0L
                    recoverCandidateSamples = 0
                }
            }

            CounterState.ASCENDING -> {
                countRepFrame(sample)
                val newLow = smoothed < minElbow
                if (newLow) {
                    minElbow = smoothed
                    if (smoothed <= baselineTopAngle - minAmplitudeDeg) depthReached = true
                }
                peakElbow = maxOf(peakElbow, smoothed)
                if (newLow) {
                    state = CounterState.DESCENDING
                    recoverCandidateSinceMs = 0L
                    recoverCandidateSamples = 0
                } else if (smoothed >= baselineTopAngle - recoverHysteresis) {
                    if (recoverCandidateSinceMs == 0L) recoverCandidateSinceMs = now
                    recoverCandidateSamples++
                    if (now - recoverCandidateSinceMs >= holdMs &&
                        recoverCandidateSamples >= holdSamples
                    ) {
                        return completeRep(sample, now)
                    }
                } else {
                    recoverCandidateSinceMs = 0L
                    recoverCandidateSamples = 0
                }
            }

            else -> Unit
        }

        updatePhase()
        return null
    }

    private fun handleCalibration(
        sample: PushUpSample,
        smoothed: Float,
        postureOk: Boolean,
        bodyStable: Boolean,
        torsoStable: Boolean
    ): PushUpRep? {
        val now = sample.timestampMs
        val canCalibrate = sample.valid &&
            postureOk &&
            bodyStable &&
            torsoStable &&
            smoothed >= entryTopAngle &&
            sample.bodyAngle >= entryBodyAngle &&
            sample.torsoInclination <= maxTorsoInclination

        if (!canCalibrate) {
            if (calibrationStartedAtMs > 0L && now - calibrationStartedAtMs > 500L) {
                resetCalibrationBuffers()
            }
            state = CounterState.IDLE
            calibrationRemainingMs = calibrationMs
            calibrationProgress = 0f
            updatePhase()
            return null
        }

        if (calibrationStartedAtMs == 0L) {
            calibrationStartedAtMs = now
            calibrationAngles.clear()
            calibrationBodies.clear()
            calibrationTorsos.clear()
            state = CounterState.CALIBRATING
        }

        calibrationAngles.add(smoothed)
        calibrationBodies.add(sample.bodyAngle)
        calibrationTorsos.add(sample.torsoInclination)
        val elapsed = now - calibrationStartedAtMs
        calibrationRemainingMs = (calibrationMs - elapsed).coerceAtLeast(0L)
        calibrationProgress = (elapsed.toFloat() / calibrationMs.toFloat()).coerceIn(0f, 1f)

        if (elapsed >= calibrationMs && calibrationAngles.size >= MIN_CALIBRATION_SAMPLES) {
            val top = median(calibrationAngles)
            val body = median(calibrationBodies)
            val torso = median(calibrationTorsos)
            val spread = percentile(calibrationAngles, 0.9f) -
                percentile(calibrationAngles, 0.1f)
            if (top >= entryTopAngle && body >= entryBodyAngle && spread <= 10f) {
                baselineTopAngle = top
                baselineBodyAngle = body
                baselineTorsoInclination = torso
                calibrated = true
                calibrationRemainingMs = 0L
                calibrationProgress = 1f
                calibrationStartedAtMs = 0L
                state = CounterState.TOP
                lastCountAtMs = now
                lastPostureOkAtMs = now
                stableSinceMs = 0L
                feedback = PushUpFeedback.NONE
            } else {
                resetCalibrationBuffers()
                state = CounterState.IDLE
            }
        }

        updatePhase()
        return null
    }

    private fun completeRep(sample: PushUpSample, now: Long): PushUpRep? {
        val duration = now - repStartedAtMs
        val amplitude = peakElbow - minElbow
        val presence = if (repTotalFrames > 0) {
            repValidFrames.toFloat() / repTotalFrames.toFloat()
        } else {
            0f
        }
        val finalBodyOk = sample.bodyAngle >= minRepBodyAngle &&
            abs(sample.bodyAngle - baselineBodyAngle) <= baselineBodyTolerance &&
            sample.torsoInclination <= baselineTorsoInclination + baselineTorsoTolerance
        val counted = depthReached &&
            amplitude >= minAmplitudeDeg &&
            duration in minRepDurationMs..maxRepDurationMs &&
            now - lastCountAtMs >= refractoryMs &&
            presence >= minRepFramePresence &&
            finalBodyOk

        if (counted) {
            count++
            lastCountAtMs = now
            feedback = PushUpFeedback.NONE
            val rep = PushUpRep(count, minElbow, duration)
            clearRep()
            state = CounterState.TOP
            updatePhase()
            return rep
        }

        feedback = if (!depthReached || amplitude < minAmplitudeDeg) {
            PushUpFeedback.TOO_SHALLOW
        } else {
            PushUpFeedback.NONE
        }
        clearRep()
        state = CounterState.TOP
        updatePhase()
        return null
    }

    private fun startRep(now: Long, angle: Float) {
        state = CounterState.DESCENDING
        repStartedAtMs = now
        minElbow = angle
        peakElbow = baselineTopAngle
        depthReached = false
        repValidFrames = 1
        repTotalFrames = 1
        lastPostureOkAtMs = now
        feedback = PushUpFeedback.NONE
        descendCandidateSinceMs = 0L
        descendCandidateSamples = 0
        recoverCandidateSinceMs = 0L
        recoverCandidateSamples = 0
    }

    private fun countRepFrame(sample: PushUpSample) {
        repTotalFrames++
        if (sample.valid) repValidFrames++
    }

    private fun updateRepDepth(angle: Float) {
        if (angle < minElbow) minElbow = angle
        peakElbow = maxOf(peakElbow, angle)
        if (angle <= baselineTopAngle - minAmplitudeDeg) depthReached = true
    }

    private fun abortRep() {
        clearRep()
        state = if (calibrated) CounterState.UNKNOWN else CounterState.IDLE
        feedback = PushUpFeedback.NONE
        stableSinceMs = 0L
    }

    private fun clearRep() {
        repStartedAtMs = 0L
        minElbow = MAX_ANGLE
        peakElbow = MAX_ANGLE
        depthReached = false
        repValidFrames = 0
        repTotalFrames = 0
        descendCandidateSinceMs = 0L
        recoverCandidateSinceMs = 0L
        descendCandidateSamples = 0
        recoverCandidateSamples = 0
    }

    private fun inRep(): Boolean =
        state == CounterState.DESCENDING || state == CounterState.ASCENDING

    private fun resetCalibrationBuffers() {
        calibrationStartedAtMs = 0L
        calibrationAngles.clear()
        calibrationBodies.clear()
        calibrationTorsos.clear()
        calibrationRemainingMs = calibrationMs
        calibrationProgress = 0f
    }

    private fun updatePhase() {
        phase = when (state) {
            CounterState.TOP -> PushUpPhase.UP
            CounterState.DESCENDING, CounterState.ASCENDING -> PushUpPhase.DOWN
            else -> PushUpPhase.UNKNOWN
        }
    }

    private fun smooth(angle: Float): Float {
        angleWindow.addLast(angle)
        while (angleWindow.size > windowSize) angleWindow.removeFirst()
        val sorted = angleWindow.sorted()
        return sorted[sorted.size / 2]
    }

    private fun addWindow(window: ArrayDeque<Float>, value: Float) {
        if (value.isNaN()) return
        window.addLast(value)
        while (window.size > windowSize) window.removeFirst()
    }

    private fun isStable(window: ArrayDeque<Float>, limit: Float): Boolean {
        if (window.size < 4) return false
        val mean = window.average()
        val variance = window.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / window.size
        return sqrt(variance).toFloat() <= limit
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    companion object {
        const val MAX_ANGLE = 180f
        private const val MIN_CALIBRATION_SAMPLES = 20
    }
}

fun angleAt(
    ax: Float, ay: Float, az: Float,
    bx: Float, by: Float, bz: Float,
    cx: Float, cy: Float, cz: Float
): Float {
    val v1x = ax - bx
    val v1y = ay - by
    val v1z = az - bz
    val v2x = cx - bx
    val v2y = cy - by
    val v2z = cz - bz

    val n1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
    val n2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
    if (n1 < 1e-6f || n2 < 1e-6f) return Float.NaN

    val cos = ((v1x * v2x + v1y * v2y + v1z * v2z) / (n1 * n2)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cos).toDouble()).toFloat()
}
