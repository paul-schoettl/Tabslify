package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpCounterTest {

    @Test
    fun countsRealRepsAtThirtyFps() {
        val stream = PushUpSim.repStream(reps = 10, frameMs = PushUpSim.FRAME_30_FPS)
        val counter = PushUpSim.run(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsRealRepsAtTwelveFps() {
        val stream = PushUpSim.repStream(reps = 10, frameMs = PushUpSim.FRAME_12_FPS)
        val counter = PushUpSim.run(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun countsImperfectForm() {
        val stream = PushUpSim.repStream(
            reps = 10,
            frameMs = PushUpSim.FRAME_30_FPS,
            topExtension = 0.92f,
            bottomExtension = 0.60f,
            elbowTop = 150f,
            elbowBottom = 100f
        )
        val counter = PushUpSim.run(stream)
        assertEquals(10, counter.count)
    }

    @Test
    fun shallowRepsAreNotCountedButFlagged() {
        val stream = PushUpSim.repStream(
            reps = 5,
            frameMs = PushUpSim.FRAME_30_FPS,
            bottomExtension = 0.70f,
            elbowBottom = 140f
        )
        val counter = PushUpSim.run(stream)
        assertEquals(0, counter.count)
        assertEquals(PushUpFeedback.TOO_SHALLOW, counter.feedback)
    }

    @Test
    fun lyingStillNeverArms() {
        val stream = PushUpSim.steadyStream(
            durationMs = 60_000L,
            frameMs = PushUpSim.FRAME_30_FPS,
            extension = 1.0f,
            wristPlantedTop = false
        )
        val counter = PushUpSim.run(stream)
        assertEquals(0, counter.count)
        assertFalse(counter.calibrated)
        assertEquals(PushUpPhase.UNKNOWN, counter.phase)
    }

    @Test
    fun sittingUpNeverCounts() {
        val stream = PushUpSim.repStream(
            reps = 8,
            frameMs = PushUpSim.FRAME_30_FPS,
            postureHorizontal = false
        )
        val counter = PushUpSim.run(stream)
        assertEquals(0, counter.count)
        assertFalse(counter.calibrated)
    }

    @Test
    fun armsWithinOneSecond() {
        val stream = PushUpSim.repStream(reps = 1, frameMs = PushUpSim.FRAME_30_FPS)
        val counter = PushUpCounter()
        var armedAtMs = -1L
        for (sample in stream) {
            counter.submit(sample)
            if (armedAtMs < 0L && counter.calibrated) armedAtMs = sample.timestampMs
        }
        assertTrue(armedAtMs > 0L)
        assertTrue(armedAtMs - stream.first().timestampMs <= 1_000L)
    }

    @Test
    fun briefOcclusionDoesNotLoseTheRep() {
        val stream = PushUpSim.repStream(reps = 2, frameMs = PushUpSim.FRAME_30_FPS)
        val repTwoStart = stream.first().timestampMs + 1_100L
        val occluded = PushUpSim.injectInvalid(stream, repTwoStart + 400L, 500L)
        val counter = PushUpSim.run(occluded)
        assertEquals(2, counter.count)
    }

    @Test
    fun longOcclusionAbortsOnlyThatRep() {
        val stream = PushUpSim.repStream(reps = 3, frameMs = PushUpSim.FRAME_30_FPS)
        val repOneStart = stream.first().timestampMs
        val occluded = PushUpSim.injectInvalid(stream, repOneStart + 400L, 900L)
        val counter = PushUpSim.run(occluded)
        assertEquals(1, counter.count)
    }

    @Test
    fun topBouncingIsIgnored() {
        val stream = PushUpSim.repStream(
            reps = 12,
            frameMs = PushUpSim.FRAME_30_FPS,
            bottomExtension = 1.00f,
            elbowBottom = 155f,
            descendMs = 150L,
            holdBottomMs = 50L,
            ascendMs = 150L,
            holdTopMs = 50L
        )
        val counter = PushUpSim.run(stream)
        assertEquals(0, counter.count)
    }

    @Test
    fun fastSecondRepIsBlockedByRefractory() {
        val stream = PushUpSim.repStream(reps = 1, frameMs = PushUpSim.FRAME_30_FPS)
        val topEnd = stream.last().timestampMs
        val quick = stream + PushUpSim.repStream(
            reps = 1,
            frameMs = PushUpSim.FRAME_30_FPS,
            startMs = topEnd + 100L,
            holdTopMs = 60L,
            descendMs = 150L,
            holdBottomMs = 50L,
            ascendMs = 150L
        )
        val counter = PushUpSim.run(quick)
        assertEquals(1, counter.count)
    }

    @Test
    fun warmUpHintAppearsAfterTenSecondsWithoutArming() {
        val stream = PushUpSim.steadyStream(
            durationMs = 11_000L,
            frameMs = PushUpSim.FRAME_30_FPS,
            extension = 1.0f,
            wristPlantedTop = false
        )
        val counter = PushUpSim.run(stream)
        assertTrue(counter.needsTopHint)
        assertFalse(counter.calibrated)
    }

    @Test
    fun poseLostKeepsCountAndBaseline() {
        val stream = PushUpSim.repStream(reps = 4, frameMs = PushUpSim.FRAME_30_FPS)
        val counter = PushUpCounter()
        val beforeLoss = stream.take(stream.size / 2)
        for (sample in beforeLoss) counter.submit(sample)
        val countBefore = counter.count
        counter.onPoseLost()
        assertEquals(countBefore, counter.count)
        for (sample in stream.drop(beforeLoss.size)) counter.submit(sample)
        assertTrue(counter.count >= countBefore)
    }

    @Test
    fun resetClearsEverything() {
        val stream = PushUpSim.repStream(reps = 3, frameMs = PushUpSim.FRAME_30_FPS)
        val counter = PushUpSim.run(stream)
        assertTrue(counter.count > 0)
        counter.reset()
        assertEquals(0, counter.count)
        assertFalse(counter.calibrated)
        assertEquals(PushUpPhase.UNKNOWN, counter.phase)
    }
}
