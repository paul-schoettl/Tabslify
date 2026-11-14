package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpSignalExtractorTest {

    private val leftShoulder = PoseLandmarks.LEFT_SHOULDER
    private val rightShoulder = PoseLandmarks.RIGHT_SHOULDER
    private val leftElbow = PoseLandmarks.LEFT_ELBOW
    private val rightElbow = PoseLandmarks.RIGHT_ELBOW
    private val leftWrist = PoseLandmarks.LEFT_WRIST
    private val rightWrist = PoseLandmarks.RIGHT_WRIST
    private val leftHip = PoseLandmarks.LEFT_HIP
    private val rightHip = PoseLandmarks.RIGHT_HIP

    private fun joint(index: Int, x: Float, y: Float, confidence: Float = 0.9f): Pair<Int, Landmark3> =
        index to Landmark3(x, y, 0f, confidence, confidence)

    private fun frame(
        shoulderX: Float = 0.40f,
        rightConfidence: Float = 0.9f,
        withWrist: Boolean = true
    ): Pair<List<Landmark3>, List<Landmark3>> {
        val joints = arrayOf(
            joint(leftShoulder, shoulderX, 0.40f),
            joint(rightShoulder, shoulderX + 0.05f, 0.40f, rightConfidence),
            joint(leftElbow, shoulderX, 0.55f),
            joint(rightElbow, shoulderX + 0.05f, 0.55f, rightConfidence),
            joint(rightHip, shoulderX + 0.05f, 0.80f, rightConfidence),
            joint(leftHip, shoulderX, 0.80f)
        )
        val landmarks = MutableList(33) { Landmark3(0f, 0f, 0f, 0f, 0f) }
        joints.forEach { (index, landmark) -> landmarks[index] = landmark }
        if (withWrist) {
            landmarks[leftWrist] = Landmark3(shoulderX, 0.90f, 0f, 0.9f, 0.9f)
            landmarks[rightWrist] = Landmark3(shoulderX + 0.05f, 0.90f, 0f, rightConfidence, rightConfidence)
        }
        return landmarks to landmarks
    }

    @Test
    fun computesArmExtensionRatio() {
        val extractor = PushUpSignalExtractor()
        val (image, world) = frame()
        val signals = extractor.analyze(image, world, 1000, 1000, 1_000L)
        assertTrue(signals.hasBody)
        assertTrue(signals.armsVisible)
        assertEquals(1.25f, signals.armExtension, 0.02f)
        assertTrue(signals.valid)
        assertTrue(signals.wristPlantedTop)
    }

    @Test
    fun oneUsableArmIsEnough() {
        val extractor = PushUpSignalExtractor()
        val (image, world) = frame(rightConfidence = 0.1f)
        val signals = extractor.analyze(image, world, 1000, 1000, 1_000L)
        assertTrue(signals.armsVisible)
        assertTrue(signals.valid)
        assertEquals(1.25f, signals.armExtension, 0.02f)
    }

    @Test
    fun missingWristMakesFrameInvalid() {
        val extractor = PushUpSignalExtractor()
        val (image, world) = frame(withWrist = false)
        val signals = extractor.analyze(image, world, 1000, 1000, 1_000L)
        assertFalse(signals.armsVisible)
        assertFalse(signals.valid)
        assertTrue(signals.armExtension.isNaN())
    }

    @Test
    fun teleportIsFlaggedAsSceneChange() {
        val extractor = PushUpSignalExtractor()
        val (firstImage, firstWorld) = frame(shoulderX = 0.40f)
        assertTrue(extractor.analyze(firstImage, firstWorld, 1000, 1000, 1_000L).valid)
        val (secondImage, secondWorld) = frame(shoulderX = 0.90f)
        val signals = extractor.analyze(secondImage, secondWorld, 1000, 1000, 1_033L)
        assertFalse(signals.valid)
    }

    @Test
    fun noBodyWithoutLandmarks() {
        val extractor = PushUpSignalExtractor()
        val signals = extractor.analyze(emptyList(), emptyList(), 1000, 1000, 1_000L)
        assertFalse(signals.hasBody)
        assertFalse(signals.valid)
    }
}
