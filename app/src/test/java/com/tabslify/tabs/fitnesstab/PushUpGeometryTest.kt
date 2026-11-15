package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpGeometryTest {

    @Test
    fun threeDimensionalAngleIsRotationAndTranslationInvariant() {
        val a = Vec3(-1f, 0f, 0f)
        val b = Vec3.ZERO
        val c = Vec3(0f, 1f, 0f)
        val angle = angleDegrees3D(a, b, c)
        val rotated = angleDegrees3D(
            Vec3(a.y, -a.x, a.z) + Vec3(5f, -2f, 3f),
            Vec3(5f, -2f, 3f),
            Vec3(c.y, -c.x, c.z) + Vec3(5f, -2f, 3f)
        )
        assertEquals(90f, angle, 0.01f)
        assertEquals(angle, rotated, 0.01f)
    }

    @Test
    fun straightAndRightAnglesAreCorrect() {
        assertEquals(
            180f,
            angleDegrees3D(Vec3(-1f, 0f, 0f), Vec3.ZERO, Vec3(1f, 0f, 0f)),
            0.01f
        )
        assertEquals(
            90f,
            angleDegrees3D(Vec3(-1f, 0f, 0f), Vec3.ZERO, Vec3(0f, 0f, 1f)),
            0.01f
        )
    }

    @Test
    fun supportPlaneFindsUpFromHandsAndFeet() {
        val image = MutableList(33) { Landmark3(0f, 0f, 0f, 0f, 0f) }
        val world = MutableList(33) { Landmark3(0f, 0f, 0f, 0f, 0f) }
        fun point(index: Int, x: Float, y: Float, z: Float, imageY: Float) {
            image[index] = Landmark3(0.5f, imageY, 0f, 0.9f, 0.9f)
            world[index] = Landmark3(x, y, z, 0.9f, 0.9f)
        }
        point(PoseLandmarks.LEFT_WRIST, 0f, 0f, 0f, 0.90f)
        point(PoseLandmarks.RIGHT_WRIST, 1f, 0f, 0f, 0.90f)
        point(PoseLandmarks.LEFT_ANKLE, 0f, 0f, 1f, 0.88f)
        point(PoseLandmarks.RIGHT_ANKLE, 1f, 0f, 1f, 0.88f)
        point(PoseLandmarks.LEFT_KNEE, 0f, -0.1f, 0.6f, 0.72f)
        point(PoseLandmarks.RIGHT_KNEE, 1f, -0.1f, 0.6f, 0.72f)
        val support = estimateSupportPlaneUp(
            image = image,
            world = world,
            shoulderMid = Vec3(0.5f, -1f, 0.5f)
        )
        assertTrue(support.valid)
        assertEquals(-1f, support.up.y, 0.02f)
        assertEquals(0f, support.normalSpreadDegrees, 0.1f)
    }

    @Test
    fun supportPlaneRequiresEnoughVisiblePoints() {
        val image = MutableList(33) { Landmark3(0f, 0f, 0f, 0f, 0f) }
        val world = MutableList(33) { Landmark3(0f, 0f, 0f, 0f, 0f) }
        image[PoseLandmarks.LEFT_WRIST] = Landmark3(0.5f, 0.9f, 0f, 0.9f, 0.9f)
        world[PoseLandmarks.LEFT_WRIST] = Landmark3(0f, 0f, 0f, 0.9f, 0.9f)
        val support = estimateSupportPlaneUp(image, world, Vec3(0.5f, -1f, 0f))
        assertFalse(support.valid)
    }

    @Test
    fun torsoInclinationUsesFloorPlane() {
        val up = Vec3(0f, -1f, 0f)
        assertEquals(0f, inclinationToPlaneDegrees(Vec3(1f, 0f, 0f), up), 0.01f)
        assertEquals(90f, inclinationToPlaneDegrees(Vec3(0f, -1f, 0f), up), 0.01f)
    }

    @Test
    fun viewClassificationSeparatesSideObliqueAndFront() {
        val image = MutableList(33) { Landmark3(0.5f, 0.5f, 0f, 0.9f, 0.9f) }
        val world = MutableList(33) { Landmark3(0f, 0f, 0f, 0.9f, 0.9f) }
        world[PoseLandmarks.LEFT_SHOULDER] = Landmark3(-0.5f, 0f, 0f, 0.9f, 0.9f)
        world[PoseLandmarks.RIGHT_SHOULDER] = Landmark3(0.5f, 0f, 0f, 0.9f, 0.9f)
        val up = Vec3(0f, -1f, 0f)
        val side = estimateView(image, world, Vec3(0f, 0f, 0f), Vec3(-1f, 0f, 0f), up)
        val oblique = estimateView(
            image,
            world,
            Vec3(0f, 0f, 0f),
            Vec3(-0.7f, 0f, -0.7f),
            up
        )
        val nearFront = estimateView(
            image,
            world,
            Vec3(0f, 0f, 0f),
            Vec3(-0.4f, 0f, -0.916f),
            up
        )
        val front = estimateView(image, world, Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f), up)
        assertEquals(PushUpViewClass.SIDE, side.viewClass)
        assertEquals(PushUpViewClass.OBLIQUE, oblique.viewClass)
        assertEquals(PushUpViewClass.OBLIQUE, nearFront.viewClass)
        assertEquals(PushUpViewClass.FRONTAL, front.viewClass)
    }

    @Test
    fun gravityBumpIgnoresSensorNoiseAndDetectsOrientationChange() {
        val detector = GravityBumpDetector()
        assertFalse(detector.update(0f, -9.81f, 0f))
        assertFalse(detector.update(0.05f, -9.80f, 0.02f))
        assertTrue(detector.update(6.94f, -6.94f, 0f))
        assertFalse(detector.update(6.95f, -6.93f, 0.01f))
    }

    @Test
    fun verticalTorsoDoesNotProduceAViewEstimate() {
        val image = MutableList(33) { Landmark3(0.5f, 0.5f, 0f, 0.9f, 0.9f) }
        val world = MutableList(33) { Landmark3(0f, 0f, 0f, 0.9f, 0.9f) }
        val view = estimateView(
            image,
            world,
            Vec3(0f, -1f, 0f),
            Vec3(0f, -0.5f, 0f),
            Vec3(0f, -1f, 0f)
        )
        assertEquals(PushUpViewClass.UNKNOWN, view.viewClass)
        assertFalse(view.confident)
    }
}
