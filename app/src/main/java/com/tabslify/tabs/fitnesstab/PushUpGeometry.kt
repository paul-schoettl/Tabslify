package com.tabslify.tabs.fitnesstab

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Float) = Vec3(x / scale, y / scale, z / scale)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x
    )

    fun length(): Float = sqrt(dot(this))

    fun normalized(): Vec3 {
        val length = length()
        return if (length < 1e-6f) Vec3(0f, 0f, 0f) else this / length
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val CAMERA_FORWARD = Vec3(0f, 0f, 1f)
    }
}

fun Landmark3.toVec3() = Vec3(x, y, z)

fun angleDegrees3D(a: Vec3, b: Vec3, c: Vec3): Float {
    val first = a - b
    val second = c - b
    val denominator = first.length() * second.length()
    if (denominator < 1e-6f) return Float.NaN
    val cosine = (first.dot(second) / denominator).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine.toDouble())).toFloat()
}

fun normalizedDistance2D(a: Landmark3, b: Landmark3, aspect: Float): Float {
    val dx = (a.x - b.x) * aspect
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

fun angleDegrees2D(a: Landmark3, b: Landmark3, c: Landmark3, aspect: Float): Float {
    val firstX = (a.x - b.x) * aspect
    val firstY = a.y - b.y
    val secondX = (c.x - b.x) * aspect
    val secondY = c.y - b.y
    val denominator = sqrt(firstX * firstX + firstY * firstY) *
        sqrt(secondX * secondX + secondY * secondY)
    if (denominator < 1e-6f) return Float.NaN
    val cosine = ((firstX * secondX + firstY * secondY) / denominator).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine.toDouble())).toFloat()
}

fun inclinationToPlaneDegrees(vector: Vec3, up: Vec3): Float {
    val length = vector.length()
    val upLength = up.length()
    if (length < 1e-6f || upLength < 1e-6f) return Float.NaN
    val sine = abs(vector.dot(up) / (length * upLength)).coerceIn(0f, 1f)
    return Math.toDegrees(asin(sine.toDouble())).toFloat()
}


data class SupportPlane(
    val up: Vec3,
    val confidence: Float,
    val pointCount: Int,
    val normalSpreadDegrees: Float,
    val valid: Boolean
)

fun estimateSupportPlaneUp(
    image: List<Landmark3>,
    world: List<Landmark3>,
    shoulderMid: Vec3,
    minConfidence: Float = 0.15f
): SupportPlane {
    if (image.size < 33 || world.size < 33) {
        return SupportPlane(Vec3.ZERO, 0f, 0, Float.MAX_VALUE, false)
    }
    fun point(id: Int): Pair<Landmark3, Landmark3>? {
        val imagePoint = image.getOrNull(id) ?: return null
        val worldPoint = world.getOrNull(id) ?: return null
        if (minOf(imagePoint.confidence, worldPoint.confidence) < minConfidence) return null
        return imagePoint to worldPoint
    }

    val leftWrist = point(PoseLandmarks.LEFT_WRIST)
    val rightWrist = point(PoseLandmarks.RIGHT_WRIST)
    val wrists = listOfNotNull(leftWrist, rightWrist)
    if (wrists.isEmpty()) {
        return SupportPlane(Vec3.ZERO, 0f, 0, Float.MAX_VALUE, false)
    }

    val lowerBody = ArrayList<Pair<Landmark3, Landmark3>>()
    listOf(
        PoseLandmarks.LEFT_ANKLE to PoseLandmarks.LEFT_KNEE,
        PoseLandmarks.RIGHT_ANKLE to PoseLandmarks.RIGHT_KNEE
    ).forEach { (ankleId, kneeId) ->
        val ankle = point(ankleId)
        val knee = point(kneeId)
        when {
            ankle != null && knee != null -> {
                val selected = if (knee.first.y > ankle.first.y + SUPPORT_SELECTION_MARGIN) knee else ankle
                lowerBody.add(selected)
            }

            ankle != null -> lowerBody.add(ankle)
            knee != null -> lowerBody.add(knee)
        }
    }
    if (lowerBody.isEmpty()) {
        return SupportPlane(Vec3.ZERO, 0f, wrists.size, Float.MAX_VALUE, false)
    }

    val points = wrists.map { it.second.toVec3() } + lowerBody.map { it.second.toVec3() }
    val confidences = wrists.map { it.first.confidence } + lowerBody.map { it.first.confidence }
    if (points.size < 3) {
        return SupportPlane(Vec3.ZERO, confidences.minOrNull() ?: 0f, points.size, Float.MAX_VALUE, false)
    }

    val normals = ArrayList<Vec3>()
    for (i in 0 until points.lastIndex - 1) {
        for (j in i + 1 until points.lastIndex) {
            for (k in j + 1 until points.size) {
                val first = points[j] - points[i]
                val second = points[k] - points[i]
                val normal = first.cross(second)
                if (normal.length() < MIN_SUPPORT_AREA) continue
                var up = normal.normalized()
                if (up.dot(shoulderMid - points[i]) < 0f) up = up * -1f
                normals.add(up)
            }
        }
    }
    if (normals.isEmpty()) {
        return SupportPlane(Vec3.ZERO, confidences.minOrNull() ?: 0f, points.size, Float.MAX_VALUE, false)
    }

    val reference = normals.first()
    val coherent = normals.filter { it.dot(reference) >= 0.35f }
    val average = coherent.fold(Vec3.ZERO) { sum, normal -> sum + normal }.normalized()
    if (average.length() < 0.5f) {
        return SupportPlane(Vec3.ZERO, confidences.minOrNull() ?: 0f, points.size, Float.MAX_VALUE, false)
    }
    val spread = coherent.maxOf {
        Math.toDegrees(acos(it.dot(average).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
    val confidence = median(confidences.map { it.coerceIn(0f, 1f) })
    return SupportPlane(
        up = average,
        confidence = confidence,
        pointCount = points.size,
        normalSpreadDegrees = spread,
        valid = spread <= MAX_SUPPORT_SPREAD_DEGREES &&
            (points.size >= 4 || confidence >= MIN_THREE_POINT_CONFIDENCE)
    )
}

class GravityBumpDetector(
    private val cosineThreshold: Float = 0.978f
) {
    private var reference: Vec3? = null

    fun reset() {
        reference = null
    }

    fun update(x: Float, y: Float, z: Float): Boolean {
        val vector = Vec3(x, y, z).normalized()
        if (vector.length() < 0.5f) return false
        val previous = reference
        if (previous == null) {
            reference = vector
            return false
        }
        if (previous.dot(vector) < cosineThreshold) {
            reference = vector
            return true
        }
        return false
    }
}

enum class PushUpViewClass { SIDE, OBLIQUE, FRONTAL, UNKNOWN }

data class ViewEstimate(
    val frontScore: Float,
    val viewClass: PushUpViewClass,
    val confident: Boolean
)

fun estimateView(
    image: List<Landmark3>,
    world: List<Landmark3>,
    shoulderMid: Vec3,
    hipMid: Vec3,
    up: Vec3
): ViewEstimate {
    if (image.size < 33 || world.size < 33 || up.length() < 0.5f) {
        return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    }
    val leftShoulder = world.getOrNull(PoseLandmarks.LEFT_SHOULDER) ?: return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    val rightShoulder = world.getOrNull(PoseLandmarks.RIGHT_SHOULDER) ?: return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    val leftShoulderQ = image.getOrNull(PoseLandmarks.LEFT_SHOULDER)?.confidence ?: 0f
    val rightShoulderQ = image.getOrNull(PoseLandmarks.RIGHT_SHOULDER)?.confidence ?: 0f
    if (minOf(leftShoulderQ, rightShoulderQ, leftShoulder.confidence, rightShoulder.confidence) <
        MIN_VIEW_SHOULDER_CONFIDENCE
    ) {
        return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    }
    val lateral = rightShoulder.toVec3() - leftShoulder.toVec3()
    val torso = shoulderMid - hipMid
    if (lateral.length() < 1e-5f || torso.length() < 1e-5f) {
        return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    }
    val unitUp = up.normalized()
    val torsoHorizontal = torso - unitUp * torso.dot(unitUp)
    val cameraHorizontal = Vec3.CAMERA_FORWARD - unitUp * Vec3.CAMERA_FORWARD.dot(unitUp)
    if (torsoHorizontal.length() < MIN_HORIZONTAL_TORSO || cameraHorizontal.length() < 0.2f) {
        return ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
    }
    val score = abs(torsoHorizontal.normalized().dot(cameraHorizontal.normalized())).coerceIn(0f, 1f)
    val viewClass = when {
        score <= SIDE_VIEW_LIMIT -> PushUpViewClass.SIDE
        score < FRONTAL_VIEW_LIMIT -> PushUpViewClass.OBLIQUE
        else -> PushUpViewClass.FRONTAL
    }
    return ViewEstimate(score, viewClass, true)
}

fun median(values: Collection<Float>): Float {
    val finite = values.filter { it.isFinite() }.sorted()
    if (finite.isEmpty()) return Float.NaN
    val middle = finite.size / 2
    return if (finite.size % 2 == 0) {
        (finite[middle - 1] + finite[middle]) / 2f
    } else {
        finite[middle]
    }
}

fun medianAbsoluteDeviation(values: Collection<Float>, center: Float = median(values)): Float {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty() || !center.isFinite()) return 0f
    return median(finite.map { abs(it - center) })
}

private const val MIN_SUPPORT_AREA = 2e-5f
private const val MAX_SUPPORT_SPREAD_DEGREES = 25f
private const val MIN_THREE_POINT_CONFIDENCE = 0.35f
private const val SUPPORT_SELECTION_MARGIN = 0.015f
private const val MIN_VIEW_SHOULDER_CONFIDENCE = 0.15f
private const val MIN_HORIZONTAL_TORSO = 0.05f
private const val SIDE_VIEW_LIMIT = 0.35f
private const val FRONTAL_VIEW_LIMIT = 0.95f
