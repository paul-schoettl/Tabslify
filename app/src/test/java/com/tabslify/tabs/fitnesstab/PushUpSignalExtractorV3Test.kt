package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpSignalExtractorV3Test {

    @Test
    fun fullPlankAndKneePositionBothProduceStableSupport() {
        val extractor = PushUpSignalExtractorV3()
        var signals = emptySignals()
        for (index in 0..4) {
            signals = extractor.analyze(
                image = frameImage(shoulderY = -0.30f, hipY = -0.22f),
                world = frameWorld(shoulderY = -0.30f, hipY = -0.22f),
                imageWidth = 1280,
                imageHeight = 720,
                timestampMs = 1_000L + index * 100L
            )
        }
        assertTrue(signals.hasBody)
        assertTrue(signals.supportValid)
        assertTrue(signals.postureSupported)
        assertTrue(signals.viewUsable)
        assertTrue(signals.wristStable)
        assertEquals(PushUpViewClass.SIDE, signals.viewClass)
        assertTrue(signals.armAngle >= 170f)
        assertTrue(signals.heightRatio > 0.18f)
        assertTrue(signals.torsoInclination < 10f)

        val kneeExtractor = PushUpSignalExtractorV3()
        var kneeSignals = emptySignals()
        for (index in 0..4) {
            kneeSignals = kneeExtractor.analyze(
                image = frameImage(shoulderY = -0.58f, hipY = -0.22f),
                world = frameWorld(shoulderY = -0.58f, hipY = -0.22f),
                imageWidth = 1280,
                imageHeight = 720,
                timestampMs = 1_000L + index * 100L
            )
        }
        assertTrue(kneeSignals.supportValid)
        assertTrue(kneeSignals.postureSupported)
        assertTrue(kneeSignals.viewUsable)
        assertTrue(kneeSignals.wristStable)
        assertEquals(PushUpViewClass.SIDE, kneeSignals.viewClass)
        assertTrue(kneeSignals.torsoInclination in 15f..40f)
        assertTrue(kneeSignals.hipAngle.isFinite())
    }

    @Test
    fun hipsRemainingOnTheFloorDoNotCountAsPushUpPosture() {
        val extractor = PushUpSignalExtractorV3()
        val signals = extractor.analyze(
            image = frameImage(shoulderY = -0.30f, hipY = 0f),
            world = frameWorld(shoulderY = -0.30f, hipY = 0f),
            imageWidth = 1280,
            imageHeight = 720,
            timestampMs = 1_000L
        )
        assertTrue(signals.supportValid)
        assertTrue(signals.hipSupportRatio < 0.10f)
        assertTrue(!signals.postureSupported)
        assertEquals(PushUpBlockReason.NOT_IN_POSITION, signals.issue)
    }

    @Test
    fun missingFeetCannotFakeASupportPlane() {
        val extractor = PushUpSignalExtractorV3()
        val image = frameImage(shoulderY = -0.30f, hipY = -0.22f).toMutableList()
        val world = frameWorld(shoulderY = -0.30f, hipY = -0.22f).toMutableList()
        listOf(PoseLandmarks.LEFT_ANKLE, PoseLandmarks.RIGHT_ANKLE).forEach {
            image[it] = image[it].copy(visibility = 0.1f, presence = 0.1f)
            world[it] = world[it].copy(visibility = 0.1f, presence = 0.1f)
        }
        listOf(PoseLandmarks.LEFT_KNEE, PoseLandmarks.RIGHT_KNEE).forEach {
            image[it] = image[it].copy(visibility = 0.1f, presence = 0.1f)
            world[it] = world[it].copy(visibility = 0.1f, presence = 0.1f)
        }
        val signals = extractor.analyze(
            image = image,
            world = world,
            imageWidth = 1280,
            imageHeight = 720,
            timestampMs = 1_000L
        )
        assertTrue(!signals.supportValid)
        assertEquals(PushUpBlockReason.SUPPORT_HIDDEN, signals.issue)
    }

    private fun emptySignals() = PushUpSignalExtractorV3().analyze(
        image = emptyList(),
        world = emptyList(),
        imageWidth = 1280,
        imageHeight = 720,
        timestampMs = 0L
    )

    private fun frameImage(shoulderY: Float, hipY: Float): List<Landmark3> {
        val points = mutableMapOf<Int, Landmark3>()
        fun imagePoint(id: Int, x: Float, y: Float, z: Float, q: Float = 0.9f) {
            val normalizedX = 0.20f + x * 0.20f
            val normalizedY = 0.80f + y * 0.30f
            points[id] = Landmark3(normalizedX, normalizedY, z, q, q)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_SHOULDER, -0.5f, 0.20f),
            Triple(PoseLandmarks.RIGHT_SHOULDER, 0.5f, -0.20f)
        ).forEach { (id, z, _) ->
            imagePoint(id, 0f, shoulderY, z)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_ELBOW, 0f, 0.20f),
            Triple(PoseLandmarks.RIGHT_ELBOW, 0f, -0.20f)
        ).forEach { (id, x, z) ->
            imagePoint(id, x, (shoulderY / 2f), z)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_WRIST, 0f, 0.20f),
            Triple(PoseLandmarks.RIGHT_WRIST, 0f, -0.20f)
        ).forEach { (id, x, z) ->
            imagePoint(id, x, 0f, z)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_HIP, 0.8f, 0.15f),
            Triple(PoseLandmarks.RIGHT_HIP, 0.8f, -0.15f)
        ).forEach { (id, x, z) ->
            imagePoint(id, x, hipY, z)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_KNEE, 1.5f, 0.15f),
            Triple(PoseLandmarks.RIGHT_KNEE, 1.5f, -0.15f)
        ).forEach { (id, x, z) ->
            imagePoint(id, x, -0.02f, z)
        }
        listOf(
            Triple(PoseLandmarks.LEFT_ANKLE, 2.2f, 0.15f),
            Triple(PoseLandmarks.RIGHT_ANKLE, 2.2f, -0.15f)
        ).forEach { (id, x, z) ->
            imagePoint(id, x, 0f, z)
        }
        return MutableList(33) { points[it] ?: Landmark3(0f, 0f, 0f, 0f, 0f) }
    }

    private fun frameWorld(shoulderY: Float, hipY: Float): List<Landmark3> {
        val points = mutableMapOf<Int, Landmark3>()
        fun worldPoint(id: Int, x: Float, y: Float, z: Float, q: Float = 0.9f) {
            points[id] = Landmark3(x, y, z, q, q)
        }
        worldPoint(PoseLandmarks.LEFT_SHOULDER, 0f, shoulderY, 0.20f)
        worldPoint(PoseLandmarks.RIGHT_SHOULDER, 0f, shoulderY, -0.20f)
        worldPoint(PoseLandmarks.LEFT_ELBOW, 0f, shoulderY / 2f, 0.20f)
        worldPoint(PoseLandmarks.RIGHT_ELBOW, 0f, shoulderY / 2f, -0.20f)
        worldPoint(PoseLandmarks.LEFT_WRIST, 0f, 0f, 0.20f)
        worldPoint(PoseLandmarks.RIGHT_WRIST, 0f, 0f, -0.20f)
        worldPoint(PoseLandmarks.LEFT_HIP, 0.8f, hipY, 0.15f)
        worldPoint(PoseLandmarks.RIGHT_HIP, 0.8f, hipY, -0.15f)
        worldPoint(PoseLandmarks.LEFT_KNEE, 1.5f, -0.02f, 0.15f)
        worldPoint(PoseLandmarks.RIGHT_KNEE, 1.5f, -0.02f, -0.15f)
        worldPoint(PoseLandmarks.LEFT_ANKLE, 2.2f, 0f, 0.15f)
        worldPoint(PoseLandmarks.RIGHT_ANKLE, 2.2f, 0f, -0.15f)
        return MutableList(33) { points[it] ?: Landmark3(0f, 0f, 0f, 0f, 0f) }
    }
}
