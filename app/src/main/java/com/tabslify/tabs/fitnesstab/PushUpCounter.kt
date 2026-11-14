package com.tabslify.tabs.fitnesstab

enum class PushUpPhase { UNKNOWN, UP, DOWN }

enum class PushUpFeedback { NONE, TOO_SHALLOW }

data class PushUpRep(
    val index: Int,
    val depthAngle: Float,
    val durationMs: Long
)

data class PushUpSample(
    val timestampMs: Long,
    val armExtension: Float,
    val elbowAngle: Float,
    val elbowConfident: Boolean,
    val wristPlantedTop: Boolean,
    val wristPlantedWide: Boolean,
    val postureHorizontal: Boolean,
    val valid: Boolean
)

private enum class CounterState { SEARCHING, READY, DESCENDING, ASCENDING }

class PushUpCounter(
    private val armTopExtension: Float = 0.82f,
    private val descendMargin: Float = 0.20f,
    private val descendFloor: Float = 0.62f,
    private val ascendDelta: Float = 0.08f,
    private val returnFloor: Float = 0.75f,
    private val returnMargin: Float = 0.15f,
    private val minAmplitude: Float = 0.20f,
    private val shallowAmplitude: Float = 0.15f,
    private val maxDepthElbow: Float = 130f,
    private val elbowConfidentShare: Float = 0.8f,
    private val minRepDurationMs: Long = 450L,
    private val maxRepDurationMs: Long = 8_000L,
    private val refractoryMs: Long = 400L,
    private val armHoldMs: Long = 150L,
    private val descendHoldMs: Long = 80L,
    private val ascendHoldMs: Long = 100L,
    private val returnHoldMs: Long = 120L,
    private val minHoldSamples: Int = 2,
    private val minRepFrames: Int = 6,
    private val freezeMs: Long = 700L,
    private val postureLossAbortMs: Long = 300L,
    private val warmUpHintMs: Long = 10_000L,
    private val windowMs: Long = 150L
) {
    private val extensionWindow = ArrayDeque<Pair<Long, Float>>()
    private val elbowWindow = ArrayDeque<Pair<Long, Float>>()
    private val armingValues = ArrayList<Float>()

    private var state = CounterState.SEARCHING
    private var topBaseline = Float.NaN
    private var lastTopExtension = Float.NaN
    private var lastCountAtMs = 0L
    private var lastValidAtMs = 0L
    private var invalidSinceMs = 0L
    private var postureLostSinceMs = 0L
    private var readySinceMs = 0L
    private var armingSinceMs = 0L
    private var armingFrames = 0
    private var descendSinceMs = 0L
    private var descendSamples = 0
    private var ascendSinceMs = 0L
    private var ascendSamples = 0
    private var returnSinceMs = 0L
    private var returnSamples = 0
    private var repStartedAtMs = 0L
    private var repTopExtension = Float.NaN
    private var minExtension = Float.MAX_VALUE
    private var depthElbow = Float.NaN
    private var repFrames = 0
    private var repElbowFrames = 0
    private var repConfidentElbowFrames = 0

    var phase = PushUpPhase.UNKNOWN
        private set

    var count = 0
        private set

    var smoothedAngle = MAX_ANGLE
        private set

    var smoothedExtension = Float.NaN
        private set

    var feedback = PushUpFeedback.NONE
        private set

    var calibrated = false
        private set

    var needsTopHint = false
        private set

    var calibrationProgress = 0f
        private set

    val calibrationRemainingMs: Long = 0L

    @Synchronized
    fun reset() {
        extensionWindow.clear()
        elbowWindow.clear()
        armingValues.clear()
        state = CounterState.SEARCHING
        topBaseline = Float.NaN
        lastCountAtMs = 0L
        lastValidAtMs = 0L
        invalidSinceMs = 0L
        postureLostSinceMs = 0L
        readySinceMs = 0L
        armingSinceMs = 0L
        armingFrames = 0
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
        clearRep()
        calibrated = false
        needsTopHint = false
        calibrationProgress = 0f
        phase = PushUpPhase.UNKNOWN
        count = 0
        smoothedAngle = MAX_ANGLE
        smoothedExtension = Float.NaN
        feedback = PushUpFeedback.NONE
    }

    @Synchronized
    fun onPoseLost() {
        extensionWindow.clear()
        elbowWindow.clear()
        clearRep()
        state = CounterState.SEARCHING
        phase = PushUpPhase.UNKNOWN
        feedback = PushUpFeedback.NONE
        lastValidAtMs = 0L
        invalidSinceMs = 0L
        postureLostSinceMs = 0L
        readySinceMs = 0L
        armingSinceMs = 0L
        armingFrames = 0
        armingValues.clear()
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
    }

    @Synchronized
    fun submit(sample: PushUpSample): PushUpRep? {
        val now = sample.timestampMs

        if (!sample.valid) {
            if (invalidSinceMs == 0L) invalidSinceMs = now
            if (now - invalidSinceMs >= freezeMs) abortToSearching()
            updatePhase()
            return null
        }
        invalidSinceMs = 0L
        val gap = if (lastValidAtMs > 0L) now - lastValidAtMs else 0L
        if (gap > freezeMs) abortToSearching()
        if (gap > SMOOTH_GAP_MS) {
            extensionWindow.clear()
            elbowWindow.clear()
        }
        lastValidAtMs = now

        val extension = smooth(extensionWindow, now, sample.armExtension)
        smoothedExtension = extension
        if (!sample.elbowAngle.isNaN()) {
            smoothedAngle = smooth(elbowWindow, now, sample.elbowAngle)
        }

        if (state == CounterState.SEARCHING) {
            readySinceMs = if (sample.postureHorizontal) {
                if (readySinceMs == 0L) now else readySinceMs
            } else {
                0L
            }
            if (!calibrated && readySinceMs > 0L && now - readySinceMs >= warmUpHintMs) {
                needsTopHint = true
            }
            val armOk = sample.postureHorizontal &&
                sample.wristPlantedTop &&
                extension >= armTopExtension
            if (armOk) {
                if (armingSinceMs == 0L) {
                    armingSinceMs = now
                    armingFrames = 0
                    armingValues.clear()
                }
                armingFrames++
                armingValues.add(extension)
                if (now - armingSinceMs >= armHoldMs && armingFrames >= minHoldSamples) {
                    arm(now)
                }
            } else {
                armingSinceMs = 0L
                armingFrames = 0
                armingValues.clear()
            }
            updatePhase()
            return null
        }

        if (!sample.postureHorizontal) {
            if (postureLostSinceMs == 0L) postureLostSinceMs = now
            if (inRep() && now - postureLostSinceMs >= postureLossAbortMs) {
                abortToSearching()
            }
            updatePhase()
            return null
        }
        postureLostSinceMs = 0L

        if (inRep() && now - repStartedAtMs > maxRepDurationMs) abortToSearching()

        var rep: PushUpRep? = null
        when (state) {
            CounterState.READY -> {
                if (lastTopExtension.isNaN() || extension > lastTopExtension) {
                    lastTopExtension = extension
                }
                val threshold = maxOf(descendFloor, topBaseline - descendMargin)
                if (extension <= threshold) {
                    if (descendSinceMs == 0L) descendSinceMs = now
                    descendSamples++
                    if (now - descendSinceMs >= descendHoldMs &&
                        descendSamples >= minHoldSamples
                    ) {
                        startRep(now, extension)
                    }
                } else {
                    descendSinceMs = 0L
                    descendSamples = 0
                }
            }

            CounterState.DESCENDING, CounterState.ASCENDING -> {
                trackRepFrame(sample)
                val newLow = extension < minExtension
                if (newLow) {
                    minExtension = extension
                    if (!sample.elbowAngle.isNaN()) depthElbow = sample.elbowAngle
                }
                if (state == CounterState.DESCENDING) {
                    if (extension >= minExtension + ascendDelta) {
                        if (ascendSinceMs == 0L) ascendSinceMs = now
                        ascendSamples++
                        if (now - ascendSinceMs >= ascendHoldMs &&
                            ascendSamples >= minHoldSamples
                        ) {
                            state = CounterState.ASCENDING
                            ascendSinceMs = 0L
                            ascendSamples = 0
                            returnSinceMs = 0L
                            returnSamples = 0
                        }
                    } else {
                        ascendSinceMs = 0L
                        ascendSamples = 0
                    }
                } else {
                    if (newLow) {
                        state = CounterState.DESCENDING
                        ascendSinceMs = 0L
                        ascendSamples = 0
                        returnSinceMs = 0L
                        returnSamples = 0
                    } else {
                        val returnThreshold = maxOf(returnFloor, topBaseline - returnMargin)
                        if (extension >= returnThreshold) {
                            if (returnSinceMs == 0L) returnSinceMs = now
                            returnSamples++
                            if (now - returnSinceMs >= returnHoldMs &&
                                returnSamples >= minHoldSamples
                            ) {
                                rep = completeRep(now)
                            }
                        } else {
                            returnSinceMs = 0L
                            returnSamples = 0
                        }
                    }
                }
            }

            else -> Unit
        }

        updatePhase()
        return rep
    }

    private fun arm(now: Long) {
        topBaseline = medianOfValues(armingValues).coerceIn(MIN_BASELINE, MAX_BASELINE)
        calibrated = true
        needsTopHint = false
        state = CounterState.READY
        lastCountAtMs = now
        lastValidAtMs = now
        feedback = PushUpFeedback.NONE
        calibrationProgress = 1f
        armingSinceMs = 0L
        armingFrames = 0
        armingValues.clear()
        lastTopExtension = topBaseline
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
        clearRep()
    }

    private fun startRep(now: Long, extension: Float) {
        state = CounterState.DESCENDING
        repStartedAtMs = now
        repTopExtension = if (lastTopExtension.isNaN()) topBaseline else lastTopExtension
        lastTopExtension = Float.NaN
        minExtension = extension
        depthElbow = Float.NaN
        repFrames = 1
        repElbowFrames = 0
        repConfidentElbowFrames = 0
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
        feedback = PushUpFeedback.NONE
    }

    private fun completeRep(now: Long): PushUpRep? {
        val duration = now - repStartedAtMs
        val amplitude = topBaseline - minExtension
        val elbowShare = if (repElbowFrames > 0) {
            repConfidentElbowFrames.toFloat() / repElbowFrames.toFloat()
        } else {
            0f
        }
        val elbowVerdictAvailable = elbowShare >= elbowConfidentShare && !depthElbow.isNaN()
        val elbowOk = !elbowVerdictAvailable || depthElbow <= maxDepthElbow
        val counted = amplitude >= minAmplitude &&
            elbowOk &&
            duration in minRepDurationMs..maxRepDurationMs &&
            repFrames >= minRepFrames &&
            now - lastCountAtMs >= refractoryMs

        feedback = if (!counted && amplitude >= shallowAmplitude) {
            PushUpFeedback.TOO_SHALLOW
        } else {
            PushUpFeedback.NONE
        }

        var rep: PushUpRep? = null
        if (counted) {
            count++
            lastCountAtMs = now
            feedback = PushUpFeedback.NONE
            rep = PushUpRep(
                index = count,
                depthAngle = if (depthElbow.isNaN()) smoothedAngle else depthElbow,
                durationMs = duration
            )
        }

        if (!repTopExtension.isNaN()) {
            topBaseline = (topBaseline * 0.75f + repTopExtension * 0.25f)
                .coerceIn(MIN_BASELINE, MAX_BASELINE)
        }

        clearRep()
        state = CounterState.READY
        return rep
    }

    private fun abortToSearching() {
        clearRep()
        state = CounterState.SEARCHING
        feedback = PushUpFeedback.NONE
        readySinceMs = 0L
        armingSinceMs = 0L
        armingFrames = 0
        armingValues.clear()
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
        invalidSinceMs = 0L
        postureLostSinceMs = 0L
    }

    private fun clearRep() {
        repStartedAtMs = 0L
        repTopExtension = Float.NaN
        lastTopExtension = Float.NaN
        minExtension = Float.MAX_VALUE
        depthElbow = Float.NaN
        repFrames = 0
        repElbowFrames = 0
        repConfidentElbowFrames = 0
        descendSinceMs = 0L
        descendSamples = 0
        ascendSinceMs = 0L
        ascendSamples = 0
        returnSinceMs = 0L
        returnSamples = 0
    }

    private fun trackRepFrame(sample: PushUpSample) {
        repFrames++
        if (!sample.elbowAngle.isNaN()) repElbowFrames++
        if (sample.elbowConfident) repConfidentElbowFrames++
    }

    private fun inRep(): Boolean =
        state == CounterState.DESCENDING || state == CounterState.ASCENDING

    private fun updatePhase() {
        phase = when (state) {
            CounterState.READY -> PushUpPhase.UP
            CounterState.DESCENDING, CounterState.ASCENDING -> PushUpPhase.DOWN
            else -> PushUpPhase.UNKNOWN
        }
    }

    private fun smooth(window: ArrayDeque<Pair<Long, Float>>, now: Long, value: Float): Float {
        window.addLast(now to value)
        while (window.isNotEmpty() && now - window.first().first > windowMs) {
            window.removeFirst()
        }
        val sorted = window.map { it.second }.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianOfValues(values: Collection<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        const val MAX_ANGLE = 180f
        private const val MIN_BASELINE = 0.80f
        private const val MAX_BASELINE = 1.25f
        private const val SMOOTH_GAP_MS = 150L
    }
}
