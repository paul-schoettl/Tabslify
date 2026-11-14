package com.tabslify.tabs.fitnesstab

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

data class Landmark3(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float
) {
    val confidence: Float get() = min(visibility, presence)
}

object PoseLandmarks {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    val CONNECTIONS = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW,
        LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW,
        RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_SHOULDER to LEFT_HIP,
        RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE,
        LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE,
        RIGHT_KNEE to RIGHT_ANKLE
    )

    val DRAWN_POINTS = listOf(
        LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST,
        LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
    )
}

data class PushUpSignals(
    val timestampMs: Long,
    val hasBody: Boolean,
    val armExtension: Float,
    val elbowAngle: Float,
    val elbowConfident: Boolean,
    val hipAngle: Float,
    val bodyAngle: Float,
    val torsoInclination: Float,
    val wristPlantedTop: Boolean,
    val wristPlantedWide: Boolean,
    val postureHorizontal: Boolean,
    val poseConfidence: Float,
    val fullyInFrame: Boolean,
    val armsVisible: Boolean,
    val valid: Boolean
)

class PushUpSignalExtractor {
    private enum class Side { LEFT, RIGHT }

    private var stickySide: Side? = null
    private var candidateSide: Side? = null
    private var candidateSinceMs = 0L
    private var lastTorsoLength = 0f
    private var lastShoulderX = Float.NaN
    private var lastShoulderY = Float.NaN

    @Synchronized
    fun reset() {
        stickySide = null
        candidateSide = null
        candidateSinceMs = 0L
        lastTorsoLength = 0f
        lastShoulderX = Float.NaN
        lastShoulderY = Float.NaN
    }

    @Synchronized
    fun analyze(
        image: List<Landmark3>,
        world: List<Landmark3>,
        imageWidth: Int,
        imageHeight: Int,
        timestampMs: Long
    ): PushUpSignals {
        if (image.size < 33) return emptySignals(timestampMs)

        val aspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
        val side = selectSide(image, timestampMs)

        val extension = side?.let { armExtension(image, it, aspect) } ?: Float.NaN
        val leftElbow = sideAngle(world, image, LEFT_ARM, MIN_ELBOW_CONFIDENCE)
        val rightElbow = sideAngle(world, image, RIGHT_ARM, MIN_ELBOW_CONFIDENCE)
        val elbowAngle = when {
            leftElbow.isNaN() && rightElbow.isNaN() -> Float.NaN
            leftElbow.isNaN() -> rightElbow
            rightElbow.isNaN() -> leftElbow
            abs(leftElbow - rightElbow) <= 25f -> (leftElbow + rightElbow) / 2f
            side == Side.RIGHT -> rightElbow
            else -> leftElbow
        }

        val bodyAngle = averageOf(
            sideWorldAngle(world, image, intArrayOf(11, 23, 27)),
            sideWorldAngle(world, image, intArrayOf(12, 24, 28))
        )
        val torsoInclination = averageOf(
            sideTorsoInclination(world, image, 11, 23),
            sideTorsoInclination(world, image, 12, 24)
        )
        val hipAngle = averageOf(
            sideWorldAngle(world, image, intArrayOf(11, 23, 25), HIP_MIN_CONFIDENCE),
            sideWorldAngle(world, image, intArrayOf(12, 24, 26), HIP_MIN_CONFIDENCE)
        )

        val torsoLength = torsoLength(image, aspect)
        val shoulderMid = midpoint(image, 11, 12)
        var sceneChanged = false
        if (torsoLength > 1e-4f && lastTorsoLength > 1e-4f) {
            val ratio = torsoLength / lastTorsoLength
            if (ratio < SCENE_RATIO_MIN || ratio > SCENE_RATIO_MAX) sceneChanged = true
        }
        if (shoulderMid != null && !lastShoulderX.isNaN() && !lastShoulderY.isNaN()) {
            if (distance2D(shoulderMid, Pair(lastShoulderX, lastShoulderY), aspect) > SCENE_JUMP) {
                sceneChanged = true
            }
        }
        if (torsoLength > 1e-4f) lastTorsoLength = torsoLength
        if (shoulderMid != null) {
            lastShoulderX = shoulderMid.first
            lastShoulderY = shoulderMid.second
        } else {
            lastShoulderX = Float.NaN
            lastShoulderY = Float.NaN
        }

        val plantedTop: Boolean
        val plantedWide: Boolean
        if (side != null && torsoLength > 1e-4f) {
            val shoulder = image[shoulderIndex(side)]
            val wrist = image[wristIndex(side)]
            val below = wrist.y >= shoulder.y + WRIST_BELOW_SHOULDER_MARGIN
            val horizontal = abs(wrist.x - shoulder.x) * aspect
            plantedTop = below && horizontal <= PLANTED_TOP_RATIO * torsoLength
            plantedWide = below && horizontal <= PLANTED_WIDE_RATIO * torsoLength
        } else {
            plantedTop = false
            plantedWide = false
        }

        val fullyInFrame = BODY_INDICES.all { index ->
            val landmark = image.getOrNull(index) ?: return@all false
            landmark.x in -0.01f..1.01f && landmark.y in -0.01f..1.01f
        }
        val poseConfidence = BODY_INDICES.minOf { index ->
            image.getOrNull(index)?.confidence ?: 0f
        }
        val postureHorizontal = !torsoInclination.isNaN() &&
            torsoInclination <= MAX_TORSO_INCLINATION

        return PushUpSignals(
            timestampMs = timestampMs,
            hasBody = true,
            armExtension = extension,
            elbowAngle = elbowAngle,
            elbowConfident = !elbowAngle.isNaN(),
            hipAngle = hipAngle,
            bodyAngle = bodyAngle,
            torsoInclination = torsoInclination,
            wristPlantedTop = plantedTop,
            wristPlantedWide = plantedWide,
            postureHorizontal = postureHorizontal,
            poseConfidence = poseConfidence,
            fullyInFrame = fullyInFrame,
            armsVisible = !extension.isNaN(),
            valid = !extension.isNaN() && !sceneChanged
        )
    }

    private fun emptySignals(timestampMs: Long) = PushUpSignals(
        timestampMs = timestampMs,
        hasBody = false,
        armExtension = Float.NaN,
        elbowAngle = Float.NaN,
        elbowConfident = false,
        hipAngle = Float.NaN,
        bodyAngle = Float.NaN,
        torsoInclination = Float.NaN,
        wristPlantedTop = false,
        wristPlantedWide = false,
        postureHorizontal = false,
        poseConfidence = 0f,
        fullyInFrame = false,
        armsVisible = false,
        valid = false
    )

    private fun selectSide(image: List<Landmark3>, now: Long): Side? {
        val leftUsable = chainConfidence(image, Side.LEFT) >= MIN_CHAIN_CONFIDENCE
        val rightUsable = chainConfidence(image, Side.RIGHT) >= MIN_CHAIN_CONFIDENCE
        val current = stickySide

        if (current == null) {
            candidateSide = null
            stickySide = when {
                leftUsable && rightUsable ->
                    if (chainConfidence(image, Side.RIGHT) > chainConfidence(image, Side.LEFT)) {
                        Side.RIGHT
                    } else {
                        Side.LEFT
                    }

                leftUsable -> Side.LEFT
                rightUsable -> Side.RIGHT
                else -> null
            }
            return stickySide
        }

        val currentUsable =
            if (current == Side.LEFT) leftUsable else rightUsable
        if (!currentUsable) {
            candidateSide = null
            val other = opposite(current)
            val otherUsable = if (other == Side.LEFT) leftUsable else rightUsable
            stickySide = if (otherUsable) other else null
            return stickySide
        }

        val other = opposite(current)
        val otherUsable = if (other == Side.LEFT) leftUsable else rightUsable
        if (otherUsable &&
            chainConfidence(image, other) >= chainConfidence(image, current) + STICKY_SWITCH_MARGIN
        ) {
            if (candidateSide != other) {
                candidateSide = other
                candidateSinceMs = now
            } else if (now - candidateSinceMs >= STICKY_SWITCH_MS) {
                stickySide = other
                candidateSide = null
            }
        } else {
            candidateSide = null
        }
        return stickySide
    }

    private fun opposite(side: Side): Side =
        if (side == Side.LEFT) Side.RIGHT else Side.LEFT

    private fun chainConfidence(image: List<Landmark3>, side: Side): Float {
        val shoulder = image.getOrNull(shoulderIndex(side))?.confidence ?: 0f
        val wrist = image.getOrNull(wristIndex(side))?.confidence ?: 0f
        val hip = image.getOrNull(hipIndex(side))?.confidence ?: 0f
        return minOf(shoulder, wrist, hip)
    }

    private fun armExtension(image: List<Landmark3>, side: Side, aspect: Float): Float {
        val shoulder = image.getOrNull(shoulderIndex(side)) ?: return Float.NaN
        val wrist = image.getOrNull(wristIndex(side)) ?: return Float.NaN
        val hip = image.getOrNull(hipIndex(side)) ?: return Float.NaN
        val shoulderWrist = distance2D(shoulder, wrist, aspect)
        val shoulderHip = distance2D(shoulder, hip, aspect)
        if (shoulderHip < 1e-4f) return Float.NaN
        return shoulderWrist / shoulderHip
    }

    private fun sideAngle(
        world: List<Landmark3>,
        image: List<Landmark3>,
        indices: IntArray,
        minConfidence: Float
    ): Float {
        if (indices.any { (image.getOrNull(it)?.confidence ?: 0f) < minConfidence }) {
            return Float.NaN
        }
        val a = world.getOrNull(indices[0]) ?: return Float.NaN
        val b = world.getOrNull(indices[1]) ?: return Float.NaN
        val c = world.getOrNull(indices[2]) ?: return Float.NaN
        return angleAt(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z)
    }

    private fun sideWorldAngle(
        world: List<Landmark3>,
        image: List<Landmark3>,
        indices: IntArray,
        minConfidence: Float = MIN_JOINT_CONFIDENCE
    ): Float = sideAngle(world, image, indices, minConfidence)

    private fun sideTorsoInclination(
        world: List<Landmark3>,
        image: List<Landmark3>,
        shoulderIndex: Int,
        hipIndex: Int
    ): Float {
        if ((image.getOrNull(shoulderIndex)?.confidence ?: 0f) < MIN_JOINT_CONFIDENCE ||
            (image.getOrNull(hipIndex)?.confidence ?: 0f) < MIN_JOINT_CONFIDENCE
        ) {
            return Float.NaN
        }
        val shoulder = world.getOrNull(shoulderIndex) ?: return Float.NaN
        val hip = world.getOrNull(hipIndex) ?: return Float.NaN
        val dy = abs(shoulder.y - hip.y)
        val dx = shoulder.x - hip.x
        val dz = shoulder.z - hip.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal < 1e-5f && dy < 1e-5f) return Float.NaN
        return Math.toDegrees(atan2(dy, horizontal).toDouble()).toFloat()
    }

    private fun torsoLength(image: List<Landmark3>, aspect: Float): Float {
        val shoulder = midpoint(image, 11, 12) ?: return 0f
        val hip = midpoint(image, 23, 24) ?: return 0f
        return distance2D(shoulder, hip, aspect)
    }

    private fun midpoint(image: List<Landmark3>, left: Int, right: Int): Pair<Float, Float>? {
        val a = image.getOrNull(left)
        val b = image.getOrNull(right)
        val aOk = (a?.confidence ?: 0f) >= MIN_JOINT_CONFIDENCE
        val bOk = (b?.confidence ?: 0f) >= MIN_JOINT_CONFIDENCE
        return when {
            aOk && bOk && a != null && b != null ->
                (a.x + b.x) / 2f to (a.y + b.y) / 2f

            aOk && a != null -> a.x to a.y
            bOk && b != null -> b.x to b.y
            else -> null
        }
    }

    private fun averageOf(left: Float, right: Float): Float = when {
        left.isNaN() && right.isNaN() -> Float.NaN
        left.isNaN() -> right
        right.isNaN() -> left
        else -> (left + right) / 2f
    }

    private fun shoulderIndex(side: Side) = when (side) {
        Side.LEFT -> PoseLandmarks.LEFT_SHOULDER
        Side.RIGHT -> PoseLandmarks.RIGHT_SHOULDER
    }

    private fun wristIndex(side: Side) = when (side) {
        Side.LEFT -> PoseLandmarks.LEFT_WRIST
        Side.RIGHT -> PoseLandmarks.RIGHT_WRIST
    }

    private fun hipIndex(side: Side) = when (side) {
        Side.LEFT -> PoseLandmarks.LEFT_HIP
        Side.RIGHT -> PoseLandmarks.RIGHT_HIP
    }

    companion object {
        private val BODY_INDICES = intArrayOf(
            PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
            PoseLandmarks.LEFT_ELBOW, PoseLandmarks.RIGHT_ELBOW,
            PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST,
            PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP,
            PoseLandmarks.LEFT_KNEE, PoseLandmarks.RIGHT_KNEE,
            PoseLandmarks.LEFT_ANKLE, PoseLandmarks.RIGHT_ANKLE
        )
        private val LEFT_ARM = intArrayOf(
            PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.LEFT_ELBOW, PoseLandmarks.LEFT_WRIST
        )
        private val RIGHT_ARM = intArrayOf(
            PoseLandmarks.RIGHT_SHOULDER, PoseLandmarks.RIGHT_ELBOW, PoseLandmarks.RIGHT_WRIST
        )

        private const val MIN_CHAIN_CONFIDENCE = 0.35f
        private const val MIN_JOINT_CONFIDENCE = 0.35f
        private const val MIN_ELBOW_CONFIDENCE = 0.50f
        private const val HIP_MIN_CONFIDENCE = 0.45f
        private const val MAX_TORSO_INCLINATION = 40f
        private const val PLANTED_TOP_RATIO = 0.45f
        private const val PLANTED_WIDE_RATIO = 0.60f
        private const val WRIST_BELOW_SHOULDER_MARGIN = 0.01f
        private const val STICKY_SWITCH_MARGIN = 0.15f
        private const val STICKY_SWITCH_MS = 500L
        private const val SCENE_RATIO_MIN = 0.6f
        private const val SCENE_RATIO_MAX = 1.6f
        private const val SCENE_JUMP = 0.30f
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

private fun distance2D(a: Landmark3, b: Landmark3, aspect: Float): Float {
    val dx = (a.x - b.x) * aspect
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun distance2D(a: Pair<Float, Float>, b: Pair<Float, Float>, aspect: Float): Float {
    val dx = (a.first - b.first) * aspect
    val dy = a.second - b.second
    return sqrt(dx * dx + dy * dy)
}
