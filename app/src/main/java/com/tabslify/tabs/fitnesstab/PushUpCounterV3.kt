package com.tabslify.tabs.fitnesstab

import kotlin.math.abs
import kotlin.math.max

enum class PushUpRejectionReason {
    NONE,
    TOO_SHALLOW,
    HEIGHT_NOT_CONFIRMED,
    INSUFFICIENT_COVERAGE,
    TIMING,
    UNSTABLE_TRACKING
}

data class PushUpSampleV3(
    val timestampMs: Long,
    val armAngle: Float,
    val angleQuality: Float,
    val angleSource: PushUpAngleSource,
    val heightRatio: Float,
    val heightQuality: Float,
    val torsoInclination: Float,
    val torsoQuality: Float,
    val supportValid: Boolean,
    val supportConfidence: Float,
    val wristStable: Boolean,
    val viewUsable: Boolean,
    val postureSupported: Boolean,
    val sceneChanged: Boolean,
    val sideChanged: Boolean,
    val issue: PushUpBlockReason
)

data class PushUpCounterConfig(
    val minTopAngle: Float = 145f,
    val bottomReferenceAngle: Float = 90f,
    val minDescendHysteresis: Float = 10f,
    val descendRomFraction: Float = 0.12f,
    val minDepth: Float = 25f,
    val depthRomFraction: Float = 0.45f,
    val minReturnHysteresis: Float = 10f,
    val returnRomFraction: Float = 0.12f,
    val minReversal: Float = 8f,
    val armDurationMs: Long = 250L,
    val descendHoldMs: Long = 90L,
    val bottomHoldMs: Long = 100L,
    val returnHoldMs: Long = 120L,
    val minCycleMs: Long = 450L,
    val maxCycleMs: Long = 8_000L,
    val refractoryMs: Long = 300L,
    val maxWindowGapMs: Long = 150L,
    val maxInvalidMs: Long = 700L,
    val minAngleCoverage: Float = 0.60f,
    val minCycleFrames: Int = 5,
    val minHeightDrop: Float = 0.05f,
    val minTopHeight: Float = 0.18f,
    val minHeightQuality: Float = 0.25f,
    val minSupportQuality: Float = 0.20f,
    val minAngleQuality: Float = 0.30f,
    val topWindowMs: Long = 400L,
    val smoothWindowMs: Long = 150L,
    val bottomWindowMs: Long = 700L,
    val minTopSamples: Int = 2
)

class PushUpCounterV3(
    private val config: PushUpCounterConfig = PushUpCounterConfig()
) {
    private enum class State { SEARCHING, READY, DESCENDING, ASCENDING }

    private val angleWindow = ArrayDeque<Pair<Long, Float>>()
    private val heightWindow = ArrayDeque<Pair<Long, Float>>()
    private val topWindow = ArrayDeque<Pair<Long, Float>>()
    private val topHeightWindow = ArrayDeque<Pair<Long, Float>>()
    private val bottomAngles = ArrayDeque<Pair<Long, Float>>()
    private val bottomHeights = ArrayDeque<Pair<Long, Float>>()
    private val armingValues = ArrayList<Float>()

    private var state = State.SEARCHING
    private var topBaseline = Float.NaN
    private var currentTop = Float.NaN
    private var cycleTop = Float.NaN
    private var cycleTopHeight = Float.NaN
    private var cycleStartMs = 0L
    private var cycleFrames = 0
    private var cycleAngleFrames = 0
    private var minAngle = Float.MAX_VALUE
    private var lockedBottomAngle = Float.NaN
    private var lockedBottomHeight = Float.NaN
    private var lastCountAtMs = 0L
    private var lastTimestampMs = 0L
    private var invalidSinceMs = 0L
    private var supportedSinceMs = 0L
    private var armingSinceMs = 0L
    private var descendSinceMs = 0L
    private var ascendSinceMs = 0L
    private var returnSinceMs = 0L
    private var ascendPeak = Float.NEGATIVE_INFINITY
    private var ascendPeakHeight = Float.NaN
    private var lastTopHintAtMs = 0L

    var count = 0
        private set

    var phase = PushUpPhase.UNKNOWN
        private set

    var calibrated = false
        private set

    var needsTopHint = false
        private set

    var smoothedAngle = Float.NaN
        private set

    var smoothedHeight = Float.NaN
        private set

    var feedback = PushUpFeedback.NONE
        private set

    var issue = PushUpBlockReason.NONE
        private set

    var rejection = PushUpRejectionReason.NONE
        private set

    var stateName: String = State.SEARCHING.name
        private set

    @Synchronized
    fun reset() {
        clearStreams()
        state = State.SEARCHING
        topBaseline = Float.NaN
        currentTop = Float.NaN
        calibrated = false
        needsTopHint = false
        count = 0
        phase = PushUpPhase.UNKNOWN
        feedback = PushUpFeedback.NONE
        issue = PushUpBlockReason.NONE
        rejection = PushUpRejectionReason.NONE
        lastCountAtMs = 0L
        lastTimestampMs = 0L
        invalidSinceMs = 0L
        supportedSinceMs = 0L
        lastTopHintAtMs = 0L
        clearCycle()
        stateName = state.name
        smoothedAngle = Float.NaN
        smoothedHeight = Float.NaN
    }

    @Synchronized
    fun onPoseLost() {
        clearStreams()
        clearCycle()
        state = State.SEARCHING
        calibrated = false
        phase = PushUpPhase.UNKNOWN
        feedback = PushUpFeedback.NONE
        rejection = PushUpRejectionReason.NONE
        issue = PushUpBlockReason.TRACKING_UNSTABLE
        lastTimestampMs = 0L
        invalidSinceMs = 0L
        supportedSinceMs = 0L
        stateName = state.name
        smoothedAngle = Float.NaN
        smoothedHeight = Float.NaN
    }

    @Synchronized
    fun submit(sample: PushUpSampleV3): PushUpRep? {
        if (sample.timestampMs <= lastTimestampMs) return null
        val gap = if (lastTimestampMs == 0L) 0L else sample.timestampMs - lastTimestampMs
        lastTimestampMs = sample.timestampMs
        if (gap > config.maxWindowGapMs) {
            angleWindow.clear()
            heightWindow.clear()
            topWindow.clear()
            topHeightWindow.clear()
        }
        if (gap > config.maxInvalidMs) {
            resetTracking(keepCount = true)
            issue = PushUpBlockReason.TRACKING_UNSTABLE
        }

        if (sample.sceneChanged) {
            resetTracking(keepCount = true)
            issue = PushUpBlockReason.CAMERA_MOVED
            phase = PushUpPhase.UNKNOWN
            updateStateName()
            return null
        }
        if (sample.sideChanged) {
            angleWindow.clear()
            heightWindow.clear()
            descendSinceMs = 0L
            ascendSinceMs = 0L
            returnSinceMs = 0L
        }

        val angle = sample.armAngle
        val angleTrusted = angle.isFinite() &&
            sample.angleQuality >= config.minAngleQuality &&
            sample.angleSource != PushUpAngleSource.NONE
        val heightTrusted = sample.heightRatio.isFinite() &&
            sample.heightQuality >= config.minHeightQuality
        val supported = angleTrusted &&
            heightTrusted &&
            sample.supportValid &&
            sample.supportConfidence >= config.minSupportQuality &&
            sample.postureSupported &&
            sample.viewUsable &&
            sample.torsoQuality >= MIN_TORSO_QUALITY &&
            sample.wristStable
        val issueFromFrame = when {
            !angleTrusted -> PushUpBlockReason.ARMS_HIDDEN
            !sample.supportValid -> PushUpBlockReason.SUPPORT_HIDDEN
            sample.supportConfidence < config.minSupportQuality -> PushUpBlockReason.LOW_SIGNAL_QUALITY
            !sample.postureSupported -> PushUpBlockReason.NOT_IN_POSITION
            !sample.viewUsable -> PushUpBlockReason.CAMERA_ANGLE
            !heightTrusted -> PushUpBlockReason.HANDS_NOT_PLANTED
            !sample.wristStable -> PushUpBlockReason.HANDS_NOT_PLANTED
            else -> sample.issue
        }

        if (!supported) {
            if (inCycle()) cycleFrames++
            if (invalidSinceMs == 0L) invalidSinceMs = sample.timestampMs
            issue = issueFromFrame
            if (inCycle() && sample.timestampMs - invalidSinceMs >= config.maxInvalidMs) {
                resetTracking(keepCount = true)
                issue = issueFromFrame
            }
            updatePhase()
            updateStateName()
            return null
        }

        invalidSinceMs = 0L
        val smoothAngle = smooth(angleWindow, sample.timestampMs, angle, config.smoothWindowMs)
        val smoothHeight = smooth(heightWindow, sample.timestampMs, sample.heightRatio, config.smoothWindowMs)
        smoothedAngle = smoothAngle
        smoothedHeight = smoothHeight
        if (!smoothAngle.isFinite()) {
            issue = PushUpBlockReason.TRACKING_UNSTABLE
            updatePhase()
            updateStateName()
            return null
        }

        if (state == State.SEARCHING) {
            supportedSinceMs = if (supportedSinceMs == 0L) sample.timestampMs else supportedSinceMs
            if (sample.timestampMs - supportedSinceMs >= 10_000L && !calibrated) {
                needsTopHint = true
            }
            if (smoothHeight < config.minTopHeight) {
                armingSinceMs = 0L
                armingValues.clear()
                issue = PushUpBlockReason.HANDS_NOT_PLANTED
                updatePhase()
                updateStateName()
                return null
            }
            if (smoothAngle < config.minTopAngle) {
                armingSinceMs = 0L
                armingValues.clear()
                issue = PushUpBlockReason.EXTEND_ARMS
                updatePhase()
                updateStateName()
                return null
            }
            if (armingSinceMs == 0L) armingSinceMs = sample.timestampMs
            armingValues.add(smoothAngle)
            topHeightWindow.addLast(sample.timestampMs to sample.heightRatio)
            trimWindow(topHeightWindow, sample.timestampMs, config.topWindowMs)
            if (sample.timestampMs - armingSinceMs >= config.armDurationMs &&
                armingValues.size >= config.minTopSamples
            ) {
                val baseline = median(armingValues)
                topBaseline = baseline.coerceIn(MIN_TOP, MAX_TOP)
                currentTop = topBaseline
                calibrated = true
                needsTopHint = false
                state = State.READY
                topWindow.clear()
                topWindow.addLast(sample.timestampMs to topBaseline)
                issue = PushUpBlockReason.NONE
            } else {
                issue = PushUpBlockReason.NONE
            }
            updatePhase()
            updateStateName()
            return null
        }

        issue = PushUpBlockReason.NONE
        var emitted: PushUpRep? = null
        when (state) {
            State.READY -> emitted = handleReady(sample, smoothAngle, smoothHeight)
            State.DESCENDING -> handleDescending(sample, smoothAngle, smoothHeight)
            State.ASCENDING -> emitted = handleAscending(sample, smoothAngle, smoothHeight)
            State.SEARCHING -> Unit
        }
        updatePhase()
        updateStateName()
        return emitted
    }

    private fun handleReady(
        sample: PushUpSampleV3,
        smoothAngle: Float,
        smoothHeight: Float
    ): PushUpRep? {
        val referenceTop = if (topBaseline.isFinite()) topBaseline else currentTop
        val rom = max(1f, referenceTop - config.bottomReferenceAngle)
        val topFloor = referenceTop - max(12f, 0.20f * rom)
        if (smoothAngle >= topFloor) {
            topWindow.addLast(sample.timestampMs to smoothAngle)
            trimWindow(topWindow, sample.timestampMs, config.topWindowMs)
            topHeightWindow.addLast(sample.timestampMs to smoothHeight)
            trimWindow(topHeightWindow, sample.timestampMs, config.topWindowMs)
            val robustTop = median(topWindow.map { it.second })
            if (robustTop.isFinite()) currentTop = robustTop.coerceIn(MIN_TOP, MAX_TOP)
            descendSinceMs = 0L
            return null
        }
        val descendThreshold = descendThreshold(currentTop)
        if (smoothAngle <= descendThreshold) {
            if (descendSinceMs == 0L) {
                descendSinceMs = sample.timestampMs
            }
            if (sample.timestampMs - descendSinceMs >= config.descendHoldMs) {
                val measuredTopHeight = median(topHeightWindow.map { it.second })
                    .takeIf { it.isFinite() } ?: smoothHeight
                startCycle(sample.timestampMs, currentTop, smoothAngle, measuredTopHeight)
            } else {
                issue = PushUpBlockReason.NONE
            }
        } else {
            descendSinceMs = 0L
        }
        return null
    }

    private fun handleDescending(
        sample: PushUpSampleV3,
        smoothAngle: Float,
        smoothHeight: Float
    ) {
        trackCycle(sample, smoothAngle, smoothHeight)
        if (state != State.DESCENDING) return
        val reversal = reversalDelta()
        if (smoothAngle >= minAngle + reversal) {
            if (ascendSinceMs == 0L) {
                ascendSinceMs = sample.timestampMs
            }
            if (sample.timestampMs - ascendSinceMs >= config.bottomHoldMs) {
                state = State.ASCENDING
                lockedBottomAngle = robustMinimum(bottomAngles)
                lockedBottomHeight = robustMinimum(bottomHeights)
                ascendPeak = smoothAngle
                ascendPeakHeight = smoothHeight
                returnSinceMs = 0L
                ascendSinceMs = 0L
            }
        } else {
            ascendSinceMs = 0L
        }
    }

    private fun handleAscending(
        sample: PushUpSampleV3,
        smoothAngle: Float,
        smoothHeight: Float
    ): PushUpRep? {
        trackCycle(sample, smoothAngle, smoothHeight)
        if (state != State.ASCENDING) return null
        ascendPeak = max(ascendPeak, smoothAngle)
        if (smoothHeight.isFinite() && (!ascendPeakHeight.isFinite() || smoothAngle >= ascendPeak)) {
            ascendPeakHeight = smoothHeight
        }
        val returnThreshold = returnThreshold(cycleTop)
        if (smoothAngle >= returnThreshold) {
            if (returnSinceMs == 0L) returnSinceMs = sample.timestampMs
            if (sample.timestampMs - returnSinceMs >= config.returnHoldMs) {
                return completeCycle(sample.timestampMs)
            }
        } else {
            returnSinceMs = 0L
        }

        val reversal = reversalDelta()
        if (smoothAngle <= ascendPeak - reversal) {
            val newTop = ascendPeak
            val newTopHeight = ascendPeakHeight
            val completedAtTop = newTop.isFinite() && newTop >= returnThreshold(cycleTop)
            val rep = if (completedAtTop) {
                completeCycle(sample.timestampMs)
            } else {
                abortCycleWithRejection(PushUpRejectionReason.UNSTABLE_TRACKING)
                null
            }
            if (newTop.isFinite() && newTop >= MIN_TOP && newTopHeight.isFinite()) {
                startCycle(sample.timestampMs, newTop, smoothAngle, newTopHeight)
            } else {
                resetTracking(keepCount = true)
            }
            return rep
        }
        return null
    }

    private fun startCycle(
        timestampMs: Long,
        repTop: Float,
        angle: Float,
        height: Float
    ) {
        state = State.DESCENDING
        cycleTop = repTop.coerceIn(MIN_TOP, MAX_TOP)
        cycleTopHeight = height
        cycleStartMs = timestampMs
        cycleFrames = 1
        cycleAngleFrames = 1
        minAngle = angle
        bottomAngles.clear()
        bottomHeights.clear()
        bottomAngles.addLast(timestampMs to angle)
        bottomHeights.addLast(timestampMs to height)
        ascendSinceMs = 0L
        ascendPeak = angle
        ascendPeakHeight = height
        returnSinceMs = 0L
        descendSinceMs = 0L
        feedback = PushUpFeedback.NONE
        rejection = PushUpRejectionReason.NONE
    }

    private fun trackCycle(
        sample: PushUpSampleV3,
        smoothAngle: Float,
        smoothHeight: Float
    ) {
        cycleFrames++
        cycleAngleFrames++
        if (smoothAngle < minAngle) minAngle = smoothAngle
        bottomAngles.addLast(sample.timestampMs to smoothAngle)
        trimWindow(bottomAngles, sample.timestampMs, config.bottomWindowMs)
        if (smoothHeight.isFinite()) {
            bottomHeights.addLast(sample.timestampMs to smoothHeight)
            trimWindow(bottomHeights, sample.timestampMs, config.bottomWindowMs)
        }
        if (sample.timestampMs - cycleStartMs > config.maxCycleMs) {
            abortCycleWithRejection(PushUpRejectionReason.TIMING)
            resetTracking(keepCount = true)
        }
    }

    private fun completeCycle(timestampMs: Long): PushUpRep? {
        val duration = timestampMs - cycleStartMs
        val bottomAngle = if (lockedBottomAngle.isFinite()) lockedBottomAngle else robustMinimum(bottomAngles)
        val bottomHeight = if (lockedBottomHeight.isFinite()) lockedBottomHeight else robustMinimum(bottomHeights)
        val effectiveMin = if (bottomAngle.isFinite()) bottomAngle else minAngle
        val depthDrop = cycleTop - effectiveMin
        val requiredDepth = requiredDepth(cycleTop)
        val requiredHeightDrop = requiredHeightDrop()
        val heightDrop = if (cycleTopHeight.isFinite() && bottomHeight.isFinite()) {
            cycleTopHeight - bottomHeight
        } else {
            Float.NaN
        }
        val coverage = if (cycleFrames > 0) {
            cycleAngleFrames.toFloat() / cycleFrames.toFloat()
        } else {
            0f
        }

        val completedTop = cycleTop
        val completedTopHeight = cycleTopHeight
        var reason = PushUpRejectionReason.NONE
        var counted = true
        when {
            depthDrop < requiredDepth -> {
                counted = false
                val shallowFloor = max(config.minReversal, 0.20f * max(1f, cycleTop - config.bottomReferenceAngle))
                if (depthDrop >= shallowFloor) reason = PushUpRejectionReason.TOO_SHALLOW
            }

            !heightDrop.isFinite() || heightDrop < requiredHeightDrop -> {
                counted = false
                reason = PushUpRejectionReason.HEIGHT_NOT_CONFIRMED
            }

            coverage < config.minAngleCoverage || cycleFrames < config.minCycleFrames -> {
                counted = false
                reason = PushUpRejectionReason.INSUFFICIENT_COVERAGE
            }

            duration < config.minCycleMs || duration > config.maxCycleMs -> {
                counted = false
                reason = PushUpRejectionReason.TIMING
            }

            timestampMs - lastCountAtMs < config.refractoryMs -> {
                counted = false
                reason = PushUpRejectionReason.TIMING
            }
        }

        var rep: PushUpRep? = null
        if (counted) {
            count++
            lastCountAtMs = timestampMs
            topBaseline = if (topBaseline.isFinite()) {
                (topBaseline * 0.9f + cycleTop * 0.1f).coerceIn(MIN_TOP, MAX_TOP)
            } else {
                cycleTop
            }
            currentTop = topBaseline
            feedback = PushUpFeedback.NONE
            rejection = PushUpRejectionReason.NONE
            rep = PushUpRep(
                index = count,
                depthAngle = effectiveMin,
                durationMs = duration
            )
        } else {
            rejection = reason
            feedback = if (reason == PushUpRejectionReason.TOO_SHALLOW) {
                PushUpFeedback.TOO_SHALLOW
            } else {
                PushUpFeedback.NONE
            }
            issue = when (reason) {
                PushUpRejectionReason.HEIGHT_NOT_CONFIRMED -> PushUpBlockReason.LOW_SIGNAL_QUALITY
                PushUpRejectionReason.INSUFFICIENT_COVERAGE,
                PushUpRejectionReason.UNSTABLE_TRACKING -> PushUpBlockReason.TRACKING_UNSTABLE

                PushUpRejectionReason.TIMING -> PushUpBlockReason.TRACKING_UNSTABLE
                else -> PushUpBlockReason.NONE
            }
        }

        clearCycle()
        state = State.READY
        topWindow.clear()
        if (completedTop.isFinite()) topWindow.addLast(timestampMs to completedTop)
        topHeightWindow.clear()
        if (completedTopHeight.isFinite()) {
            topHeightWindow.addLast(timestampMs to completedTopHeight)
        }
        return rep
    }

    private fun abortCycleWithRejection(reason: PushUpRejectionReason) {
        rejectCycle(reason)
        clearCycle()
        state = State.READY
    }

    private fun rejectCycle(reason: PushUpRejectionReason) {
        rejection = reason
        feedback = if (reason == PushUpRejectionReason.TOO_SHALLOW) {
            PushUpFeedback.TOO_SHALLOW
        } else {
            PushUpFeedback.NONE
        }
    }

    private fun resetTracking(keepCount: Boolean) {
        state = State.SEARCHING
        calibrated = false
        topBaseline = Float.NaN
        currentTop = Float.NaN
        angleWindow.clear()
        heightWindow.clear()
        topWindow.clear()
        topHeightWindow.clear()
        clearCycle()
        supportedSinceMs = 0L
        armingSinceMs = 0L
        armingValues.clear()
        descendSinceMs = 0L
        invalidSinceMs = 0L
        if (!keepCount) count = 0
        feedback = PushUpFeedback.NONE
        rejection = PushUpRejectionReason.NONE
        phase = PushUpPhase.UNKNOWN
        smoothedAngle = Float.NaN
        smoothedHeight = Float.NaN
        stateName = state.name
    }

    private fun clearCycle() {
        cycleStartMs = 0L
        cycleFrames = 0
        cycleAngleFrames = 0
        cycleTop = Float.NaN
        cycleTopHeight = Float.NaN
        minAngle = Float.MAX_VALUE
        lockedBottomAngle = Float.NaN
        lockedBottomHeight = Float.NaN
        bottomAngles.clear()
        bottomHeights.clear()
        ascendSinceMs = 0L
        ascendPeak = Float.NEGATIVE_INFINITY
        ascendPeakHeight = Float.NaN
        returnSinceMs = 0L
    }

    private fun clearStreams() {
        angleWindow.clear()
        heightWindow.clear()
        topWindow.clear()
        topHeightWindow.clear()
        armingValues.clear()
        clearCycle()
    }

    private fun smooth(
        window: ArrayDeque<Pair<Long, Float>>,
        timestampMs: Long,
        value: Float,
        durationMs: Long
    ): Float {
        if (!value.isFinite()) return Float.NaN
        window.addLast(timestampMs to value)
        trimWindow(window, timestampMs, durationMs)
        return median(window.map { it.second })
    }

    private fun trimWindow(
        window: ArrayDeque<Pair<Long, Float>>,
        timestampMs: Long,
        durationMs: Long
    ) {
        while (window.isNotEmpty() && timestampMs - window.first().first > durationMs) {
            window.removeFirst()
        }
    }

    private fun robustMinimum(window: ArrayDeque<Pair<Long, Float>>): Float {
        val values = window.map { it.second }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) return Float.NaN
        val take = minOf(3, values.size)
        return median(values.take(take))
    }

    private fun requiredDepth(top: Float): Float {
        val rom = max(1f, top - config.bottomReferenceAngle)
        return max(config.minDepth, config.depthRomFraction * rom)
    }

    private fun descendThreshold(top: Float): Float {
        val rom = max(1f, top - config.bottomReferenceAngle)
        return top - max(config.minDescendHysteresis, config.descendRomFraction * rom)
    }

    private fun returnThreshold(top: Float): Float {
        val rom = max(1f, top - config.bottomReferenceAngle)
        return top - max(config.minReturnHysteresis, config.returnRomFraction * rom)
    }

    private fun reversalDelta(): Float {
        val noise = medianAbsoluteDeviation(topWindow.map { it.second })
        return max(config.minReversal, noise * 3f)
    }

    private fun requiredHeightDrop(): Float {
        val noise = medianAbsoluteDeviation(topHeightWindow.map { it.second })
        return max(config.minHeightDrop, noise * 3f)
    }

    private fun inCycle(): Boolean = state == State.DESCENDING || state == State.ASCENDING

    private fun updatePhase() {
        phase = when (state) {
            State.READY -> PushUpPhase.UP
            State.DESCENDING, State.ASCENDING -> PushUpPhase.DOWN
            State.SEARCHING -> PushUpPhase.UNKNOWN
        }
    }

    private fun updateStateName() {
        stateName = state.name
    }

    companion object {
        private const val MIN_TOP = 135f
        private const val MAX_TOP = 180f
        private const val MIN_TORSO_QUALITY = 0.35f
    }
}
