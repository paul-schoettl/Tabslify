package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpCounterV3Test {

    @Test
    fun countsTenRepsAtThirtyFps() {
        val stream = v3Stream(reps = 10, frameMs = 33L)
        val counter = runV3(stream)
        assertEquals(10, counter.count)
        assertTrue(counter.calibrated)
    }

    @Test
    fun countsTenRepsAtTwelveFps() {
        val stream = v3Stream(reps = 10, frameMs = 83L)
        val counter = runV3(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsTenRepsAtTenFps() {
        val stream = v3Stream(reps = 10, frameMs = 100L)
        val counter = runV3(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsTenRepsAtEightFps() {
        val stream = v3Stream(reps = 10, frameMs = 125L)
        val counter = runV3(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsModerateDepthWithRealisticHeight() {
        val stream = v3Stream(
            reps = 5,
            frameMs = 33L,
            topAngle = 170f,
            bottomAngle = 124f,
            topHeight = 0.97f,
            bottomHeight = 0.88f
        )
        val counter = runV3(stream)
        assertEquals(5, counter.count)
    }

    @Test
    fun countsReducedTopWithModerateDepth() {
        val stream = v3Stream(
            reps = 5,
            frameMs = 33L,
            topAngle = 145f,
            bottomAngle = 100f,
            topHeight = 0.96f,
            bottomHeight = 0.82f
        )
        val counter = runV3(stream)
        assertEquals(5, counter.count)
    }

    @Test
    fun doesNotMergeRepsWithoutTopDwell() {
        val stream = v3Stream(reps = 10, frameMs = 33L, topHoldMs = 0L)
        val counter = runV3(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsFastSeriesWithoutTopDwellAfterDownwardReversal() {
        val stream = v3Stream(
            reps = 10,
            frameMs = 33L,
            topHoldMs = 0L,
            halfCycleMs = 320L,
            bottomHoldMs = 80L
        )
        val counter = runV3(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsFullAndKneePostureEqually() {
        val full = v3Stream(reps = 5, frameMs = 33L, torsoInclination = 4f)
        val knee = v3Stream(reps = 5, frameMs = 33L, torsoInclination = 32f)
        assertEquals(5, runV3(full).count)
        assertEquals(5, runV3(knee).count)
    }

    @Test
    fun shallowCyclesAreRejectedOnlyAsTooShallow() {
        val stream = v3Stream(reps = 5, frameMs = 33L, bottomAngle = 145f)
        val counter = runV3(stream)
        assertEquals(0, counter.count)
        assertEquals(PushUpFeedback.TOO_SHALLOW, counter.feedback)
        assertEquals(PushUpRejectionReason.TOO_SHALLOW, counter.rejection)
    }

    @Test
    fun staticPlankNeverCounts() {
        val stream = ArrayList<PushUpSampleV3>()
        var time = 1_000L
        repeat(1_800) {
            stream.add(sample(time, 170f, 0.92f))
            time += 33L
        }
        val counter = runV3(stream)
        assertEquals(0, counter.count)
    }

    @Test
    fun lyingWithHandsNearTheBodyNeverArms() {
        val stream = v3Stream(
            reps = 1,
            frameMs = 33L,
            topAngle = 170f,
            bottomAngle = 150f,
            topHeight = 0.05f,
            bottomHeight = 0.02f
        )
        val counter = runV3(stream)
        assertEquals(0, counter.count)
        assertFalse(counter.calibrated)
    }

    @Test
    fun lowSupportConfidenceNeverCounts() {
        val stream = v3Stream(reps = 3, frameMs = 33L).map {
            it.copy(
                supportConfidence = 0.1f,
                issue = PushUpBlockReason.LOW_SIGNAL_QUALITY
            )
        }
        val counter = runV3(stream)
        assertEquals(0, counter.count)
        assertEquals(PushUpBlockReason.LOW_SIGNAL_QUALITY, counter.issue)
    }

    @Test
    fun nonPronePostureNeverCounts() {
        val stream = v3Stream(reps = 5, frameMs = 33L).map {
            it.copy(postureSupported = false, issue = PushUpBlockReason.NOT_IN_POSITION)
        }
        val counter = runV3(stream)
        assertEquals(0, counter.count)
        assertEquals(PushUpBlockReason.NOT_IN_POSITION, counter.issue)
    }

    @Test
    fun unreliableAngleNeverCounts() {
        val stream = v3Stream(reps = 3, frameMs = 33L).map { sample ->
            sample.copy(
                armAngle = Float.NaN,
                angleQuality = 0f,
                angleSource = PushUpAngleSource.NONE
            )
        }
        val counter = runV3(stream)
        assertEquals(0, counter.count)
    }

    @Test
    fun missingDepthCoverageAbortsRep() {
        val stream = v3Stream(reps = 2, frameMs = 33L).map { sample ->
            if (sample.armAngle < 140f) {
                sample.copy(
                    armAngle = Float.NaN,
                    angleQuality = 0f,
                    angleSource = PushUpAngleSource.NONE
                )
            } else {
                sample
            }
        }
        val counter = runV3(stream)
        assertEquals(0, counter.count)
    }

    @Test
    fun topOutlierDoesNotRaiseReturnOutOfReach() {
        val stream = v3Stream(reps = 5, frameMs = 33L)
        val withOutlier = stream.mapIndexed { index, sample ->
            if (index == 20) sample.copy(armAngle = 179f, heightRatio = 1.1f) else sample
        }
        val counter = runV3(withOutlier)
        assertEquals(5, counter.count)
    }

    @Test
    fun sideSwitchAbortsOnlyActiveCycle() {
        val stream = v3Stream(reps = 4, frameMs = 33L).mapIndexed { index, sample ->
            if (index in 70..72) sample.copy(sideChanged = true) else sample
        }
        val counter = runV3(stream)
        assertEquals(4, counter.count)
    }

    @Test
    fun resetClearsCountAndState() {
        val counter = runV3(v3Stream(reps = 3, frameMs = 33L))
        assertTrue(counter.count > 0)
        counter.reset()
        assertEquals(0, counter.count)
        assertFalse(counter.calibrated)
        assertEquals(PushUpPhase.UNKNOWN, counter.phase)
    }

    private fun runV3(stream: List<PushUpSampleV3>): PushUpCounterV3 {
        val counter = PushUpCounterV3()
        for (sample in stream) counter.submit(sample)
        return counter
    }

    private fun sample(
        timestampMs: Long,
        angle: Float,
        height: Float,
        torsoInclination: Float = 6f,
        angleQuality: Float = 0.9f
    ) = PushUpSampleV3(
        timestampMs = timestampMs,
        armAngle = angle,
        angleQuality = angleQuality,
        angleSource = if (angleQuality > 0f) PushUpAngleSource.WORLD else PushUpAngleSource.NONE,
        heightRatio = height,
        heightQuality = 0.9f,
        torsoInclination = torsoInclination,
        torsoQuality = 0.9f,
        supportValid = true,
        supportConfidence = 0.9f,
        wristStable = true,
        viewUsable = true,
        postureSupported = true,
        sceneChanged = false,
        sideChanged = false,
        issue = PushUpBlockReason.NONE
    )

    private fun v3Stream(
        reps: Int,
        frameMs: Long,
        topAngle: Float = 170f,
        bottomAngle: Float = 100f,
        topHeight: Float = 0.97f,
        bottomHeight: Float = 0.82f,
        torsoInclination: Float = 6f,
        topHoldMs: Long = 260L,
        halfCycleMs: Long = 480L,
        bottomHoldMs: Long = 120L
    ): List<PushUpSampleV3> {
        val stream = ArrayList<PushUpSampleV3>()
        var time = 1_000L

        fun emit(angle: Float, height: Float) {
            stream.add(sample(time, angle, height, torsoInclination))
            time += frameMs
        }

        fun ramp(
            durationMs: Long,
            fromAngle: Float,
            toAngle: Float,
            fromHeight: Float,
            toHeight: Float
        ) {
            val steps = (durationMs / frameMs).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val fraction = step.toFloat() / steps.toFloat()
                emit(
                    fromAngle + (toAngle - fromAngle) * fraction,
                    fromHeight + (toHeight - fromHeight) * fraction
                )
            }
        }

        ramp(320L, topAngle, topAngle, topHeight, topHeight)
        repeat(reps) {
            ramp(halfCycleMs, topAngle, bottomAngle, topHeight, bottomHeight)
            ramp(bottomHoldMs, bottomAngle, bottomAngle, bottomHeight, bottomHeight)
            ramp(halfCycleMs, bottomAngle, topAngle, bottomHeight, topHeight)
            if (topHoldMs > 0L) {
                ramp(topHoldMs, topAngle, topAngle, topHeight, topHeight)
            }
        }
        ramp(320L, topAngle, topAngle, topHeight, topHeight)
        return stream
    }
}
