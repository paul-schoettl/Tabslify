package com.tabslify.tabs.fitnesstab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpReplayTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun parsesOnlyFrameLinesAndPreservesOrder() {
        val header = PushUpCaptureLine(
            type = "header",
            header = PushUpCaptureHeader(
                captureId = "test",
                schemaVersion = 1,
                startedAtEpochMs = 1L,
                startedAtElapsedMs = 2L,
                appVersion = "test",
                manufacturer = "test",
                model = "test",
                sdkInt = 35,
                viewAngle = "side",
                distanceMeters = 2f,
                lighting = "normal",
                cameraRollDegrees = 0f,
                tempo = "normal",
                form = "full",
                expectedReps = 10,
                scenario = "positive",
                notes = ""
            )
        )
        val first = frame(sequence = 1, timestamp = 1_000L)
        val second = frame(sequence = 2, timestamp = 1_033L)
        val text = listOf(header, first, second).joinToString("\n") { json.encodeToString(it) }
        val replay = PushUpReplay()
        val frames = replay.parse(text)
        assertEquals(2, frames.size)
        assertEquals(1L, frames[0].sequence)
        assertEquals(2L, frames[1].sequence)
    }

    @Test
    fun replayWithoutLandmarksReturnsZeroWithoutThrowing() {
        val first = frame(sequence = 1, timestamp = 1_000L)
        val second = frame(sequence = 2, timestamp = 1_033L)
        val text = listOf(first, second).joinToString("\n") { json.encodeToString(it) }
        val result = PushUpReplay().replayV3(text)
        assertEquals(2, result.frameCount)
        assertEquals(0, result.count)
        assertFalse(result.calibrated)
    }

    @Test
    fun replayReachesCalibrationFromFullBodyLandmarks() {
        val lines = (0..6).map { index ->
            frame(
                sequence = index.toLong(),
                timestamp = 1_000L + index * 100L,
                landmarks = fullBodyImage(),
                worldLandmarks = fullBodyWorld()
            )
        }
        val text = lines.joinToString("\n") { json.encodeToString(it) }
        val result = PushUpReplay().replayV3(text)
        assertEquals(7, result.frameCount)
        assertTrue(result.calibrated)
        assertEquals(0, result.count)
        val last = result.trace.last()
        assertTrue(last.supportValid)
        assertTrue(last.wristStable)
        assertEquals(PushUpViewClass.SIDE, last.viewClass)
        assertTrue(last.angle > 170f)
    }

    @Test
    fun nonMonotonicFramesAreNotFedToTheCounter() {
        val first = frame(sequence = 1, timestamp = 2_000L)
        val second = frame(sequence = 2, timestamp = 1_000L)
        val text = listOf(first, second).joinToString("\n") { json.encodeToString(it) }
        val result = PushUpReplay().replayV3(text)
        assertEquals(1, result.trace.size)
    }

    private fun frame(
        sequence: Long,
        timestamp: Long,
        landmarks: List<PushUpCaptureLandmark> = emptyList(),
        worldLandmarks: List<PushUpCaptureLandmark> = emptyList()
    ): PushUpCaptureLine =
        PushUpCaptureLine(
            type = "frame",
            frame = PushUpCaptureFrame(
                sequence = sequence,
                timestampMs = timestamp,
                imageWidth = 1280,
                imageHeight = 720,
                frontCamera = true,
                rotationDegrees = 0,
                landmarks = landmarks,
                worldLandmarks = worldLandmarks,
                gravity = null
            )
        )

    private fun fullBodyImage(): List<PushUpCaptureLandmark> {
        val points = mutableMapOf<Int, PushUpCaptureLandmark>()
        fun point(id: Int, x: Float, y: Float, z: Float) {
            points[id] = PushUpCaptureLandmark(
                x = 0.20f + x * 0.20f,
                y = 0.80f + y * 0.30f,
                z = z,
                visibility = 0.9f,
                presence = 0.9f
            )
        }
        point(PoseLandmarks.LEFT_SHOULDER, 0f, -0.30f, 0.20f)
        point(PoseLandmarks.RIGHT_SHOULDER, 0f, -0.30f, -0.20f)
        point(PoseLandmarks.LEFT_ELBOW, 0f, -0.15f, 0.20f)
        point(PoseLandmarks.RIGHT_ELBOW, 0f, -0.15f, -0.20f)
        point(PoseLandmarks.LEFT_WRIST, 0f, 0f, 0.20f)
        point(PoseLandmarks.RIGHT_WRIST, 0f, 0f, -0.20f)
        point(PoseLandmarks.LEFT_HIP, 0.8f, -0.22f, 0.15f)
        point(PoseLandmarks.RIGHT_HIP, 0.8f, -0.22f, -0.15f)
        point(PoseLandmarks.LEFT_KNEE, 1.5f, -0.02f, 0.15f)
        point(PoseLandmarks.RIGHT_KNEE, 1.5f, -0.02f, -0.15f)
        point(PoseLandmarks.LEFT_ANKLE, 2.2f, 0f, 0.15f)
        point(PoseLandmarks.RIGHT_ANKLE, 2.2f, 0f, -0.15f)
        return List(33) {
            points[it] ?: PushUpCaptureLandmark(0f, 0f, 0f, 0f, 0f)
        }
    }

    private fun fullBodyWorld(): List<PushUpCaptureLandmark> {
        val points = mutableMapOf<Int, PushUpCaptureLandmark>()
        fun point(id: Int, x: Float, y: Float, z: Float) {
            points[id] = PushUpCaptureLandmark(x, y, z, 0.9f, 0.9f)
        }
        point(PoseLandmarks.LEFT_SHOULDER, 0f, -0.30f, 0.20f)
        point(PoseLandmarks.RIGHT_SHOULDER, 0f, -0.30f, -0.20f)
        point(PoseLandmarks.LEFT_ELBOW, 0f, -0.15f, 0.20f)
        point(PoseLandmarks.RIGHT_ELBOW, 0f, -0.15f, -0.20f)
        point(PoseLandmarks.LEFT_WRIST, 0f, 0f, 0.20f)
        point(PoseLandmarks.RIGHT_WRIST, 0f, 0f, -0.20f)
        point(PoseLandmarks.LEFT_HIP, 0.8f, -0.22f, 0.15f)
        point(PoseLandmarks.RIGHT_HIP, 0.8f, -0.22f, -0.15f)
        point(PoseLandmarks.LEFT_KNEE, 1.5f, -0.02f, 0.15f)
        point(PoseLandmarks.RIGHT_KNEE, 1.5f, -0.02f, -0.15f)
        point(PoseLandmarks.LEFT_ANKLE, 2.2f, 0f, 0.15f)
        point(PoseLandmarks.RIGHT_ANKLE, 2.2f, 0f, -0.15f)
        return List(33) {
            points[it] ?: PushUpCaptureLandmark(0f, 0f, 0f, 0f, 0f)
        }
    }
}
