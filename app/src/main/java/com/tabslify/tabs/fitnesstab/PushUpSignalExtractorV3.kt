package com.tabslify.tabs.fitnesstab

import kotlin.math.abs

enum class PushUpAngleSource { NONE, WORLD, IMAGE }

enum class PushUpBlockReason {
    NONE,
    NO_BODY,
    ARMS_HIDDEN,
    NOT_IN_POSITION,
    SUPPORT_HIDDEN,
    HANDS_NOT_PLANTED,
    EXTEND_ARMS,
    CAMERA_ANGLE,
    LOW_SIGNAL_QUALITY,
    TRACKING_UNSTABLE,
    CAMERA_MOVED
}

data class PushUpFrameSignalsV3(
    val timestampMs: Long,
    val hasBody: Boolean,
    val armAngle: Float,
    val armAngleQuality: Float,
    val armAngleSource: PushUpAngleSource,
    val angleAgreement: Float,
    val heightRatio: Float,
    val heightQuality: Float,
    val torsoInclination: Float,
    val torsoQuality: Float,
    val supportValid: Boolean,
    val supportConfidence: Float,
    val supportSpread: Float,
    val wristStable: Boolean,
    val wristSpread: Float,
    val hipSupportRatio: Float,
    val viewScore: Float,
    val viewClass: PushUpViewClass,
    val viewUsable: Boolean,
    val postureSupported: Boolean,
    val hipAngle: Float,
    val fullyInFrame: Boolean,
    val sceneChanged: Boolean,
    val sideChanged: Boolean,
    val issue: PushUpBlockReason
)

class PushUpSignalExtractorV3 {
    private enum class Side { LEFT, RIGHT }

    private var stickySide: Side? = null
    private var lastShoulderX = Float.NaN
    private var lastShoulderY = Float.NaN
    private var lastTorsoLength = 0f
    private var sceneCandidateSinceMs = 0L
    private val armLengths = HashMap<Side, ArrayDeque<Float>>()
    private val wristSamples = HashMap<Side, ArrayDeque<WristSample>>()
    private var lastRight2DAngle = Float.NaN
    private var lastLeft2DAngle = Float.NaN

    @Synchronized
    fun reset() {
        stickySide = null
        lastShoulderX = Float.NaN
        lastShoulderY = Float.NaN
        lastTorsoLength = 0f
        sceneCandidateSinceMs = 0L
        armLengths.clear()
        wristSamples.clear()
        lastRight2DAngle = Float.NaN
        lastLeft2DAngle = Float.NaN
    }

    @Synchronized
    fun analyze(
        image: List<Landmark3>,
        world: List<Landmark3>,
        imageWidth: Int,
        imageHeight: Int,
        timestampMs: Long
    ): PushUpFrameSignalsV3 {
        if (image.size < 33) return empty(timestampMs, PushUpBlockReason.NO_BODY)

        val aspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f
        val shoulderMidImage = strictMidpoint(image, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER)
        val hipMidImage = strictMidpoint(image, PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)
        val shoulderMidWorld = strictMidpointWorld(world, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER)
        val hipMidWorld = strictMidpointWorld(world, PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)

        val torsoLength = if (shoulderMidImage != null && hipMidImage != null) {
            normalizedDistance2D(shoulderMidImage, hipMidImage, aspect)
        } else {
            0f
        }
        val shoulderJump = if (shoulderMidImage != null &&
            lastShoulderX.isFinite() && lastShoulderY.isFinite()
        ) {
            val dx = (shoulderMidImage.x - lastShoulderX) * aspect
            val dy = shoulderMidImage.y - lastShoulderY
            kotlin.math.sqrt(dx * dx + dy * dy)
        } else {
            0f
        }
        var candidateScene = false
        if (lastTorsoLength > 1e-4f && torsoLength > 1e-4f) {
            val ratio = torsoLength / lastTorsoLength
            if (ratio < SCENE_RATIO_MIN || ratio > SCENE_RATIO_MAX) candidateScene = true
        }
        if (shoulderJump > SCENE_JUMP) candidateScene = true
        if (candidateScene) {
            if (sceneCandidateSinceMs == 0L) sceneCandidateSinceMs = timestampMs
        } else {
            sceneCandidateSinceMs = 0L
        }
        val sceneChanged = sceneCandidateSinceMs > 0L &&
            timestampMs - sceneCandidateSinceMs >= SCENE_CONFIRM_MS
        if (torsoLength > 1e-4f) lastTorsoLength = torsoLength
        if (shoulderMidImage != null) {
            lastShoulderX = shoulderMidImage.x
            lastShoulderY = shoulderMidImage.y
        }

        val shoulderForSupport = shoulderMidWorld ?: singleJointWorld(world, PoseLandmarks.LEFT_SHOULDER)
        ?: singleJointWorld(world, PoseLandmarks.RIGHT_SHOULDER)
        val support = if (shoulderForSupport != null) {
            estimateSupportPlaneUp(image, world, shoulderForSupport)
        } else {
            SupportPlane(Vec3.ZERO, 0f, 0, Float.MAX_VALUE, false)
        }
        val up = support.up
        val view = if (shoulderMidWorld != null && hipMidWorld != null && support.valid) {
            estimateView(image, world, shoulderMidWorld, hipMidWorld, up)
        } else {
            ViewEstimate(Float.NaN, PushUpViewClass.UNKNOWN, false)
        }
        val leftMetrics = sideMetrics(image, world, Side.LEFT, aspect, timestampMs, up)
        val rightMetrics = sideMetrics(image, world, Side.RIGHT, aspect, timestampMs, up)
        val previousSide = stickySide
        val selectedSide = selectSide(leftMetrics, rightMetrics)
        stickySide = selectedSide
        val metrics = when (selectedSide) {
            Side.LEFT -> leftMetrics
            Side.RIGHT -> rightMetrics
            null -> null
        }

        val torsoInclinations = listOfNotNull(
            perSideTorso(image, world, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.LEFT_HIP, up),
            perSideTorso(image, world, PoseLandmarks.RIGHT_SHOULDER, PoseLandmarks.RIGHT_HIP, up)
        )
        val torsoInclination = medianOf(torsoInclinations)
        val torsoQuality = when (torsoInclinations.size) {
            2 -> 1f
            1 -> 0.85f
            else -> 0f
        }

        val height = if (metrics != null && support.valid && metrics.armLength > 1e-4f) {
            metrics.shoulderHeight / metrics.armLength
        } else {
            Float.NaN
        }
        val hipSupportRatio = hipSupportRatio(
            world = world,
            selectedSide = selectedSide,
            shoulderMid = shoulderMidWorld,
            up = up
        )
        val wristStable = when (selectedSide) {
            Side.LEFT -> wristIsStable(wristSamples[Side.LEFT], aspect)
            Side.RIGHT -> wristIsStable(wristSamples[Side.RIGHT], aspect)
            null -> false
        }
        val wristSpread = when (selectedSide) {
            Side.LEFT -> wristSpread(wristSamples[Side.LEFT], aspect)
            Side.RIGHT -> wristSpread(wristSamples[Side.RIGHT], aspect)
            null -> Float.NaN
        }

        val angleAgreement = if (leftMetrics.angleSource == PushUpAngleSource.WORLD &&
            rightMetrics.angleSource == PushUpAngleSource.WORLD
        ) {
            abs(leftMetrics.angle - rightMetrics.angle)
        } else {
            Float.NaN
        }
        val viewUsable = when (view.viewClass) {
            PushUpViewClass.SIDE, PushUpViewClass.OBLIQUE -> view.confident
            PushUpViewClass.FRONTAL -> view.confident &&
                metrics?.angleSource == PushUpAngleSource.WORLD &&
                metrics.armAngleQuality >= FRONTAL_ARM_QUALITY &&
                leftMetrics.armAngleQuality >= FRONTAL_ARM_QUALITY &&
                rightMetrics.armAngleQuality >= FRONTAL_ARM_QUALITY &&
                angleAgreement.isFinite() &&
                angleAgreement <= FRONTAL_AGREEMENT_LIMIT
            PushUpViewClass.UNKNOWN -> false
        }

        val fullyInFrame = BODY_INDICES.all { index ->
            val landmark = image.getOrNull(index) ?: return@all false
            landmark.x in -0.01f..1.01f && landmark.y in -0.01f..1.01f
        }
        val postureSupported = support.valid &&
            torsoInclination.isFinite() &&
            torsoInclination <= MAX_TORSO_INCLINATION &&
            torsoQuality >= MIN_TORSO_QUALITY &&
            hipSupportRatio.isFinite() &&
            hipSupportRatio >= MIN_HIP_SUPPORT_RATIO
        val hipAngle = averageOf(
            sideHipAngle(image, world, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.LEFT_HIP, PoseLandmarks.LEFT_KNEE),
            sideHipAngle(image, world, PoseLandmarks.RIGHT_SHOULDER, PoseLandmarks.RIGHT_HIP, PoseLandmarks.RIGHT_KNEE)
        )

        val sideChanged = previousSide != null && previousSide != selectedSide
        val issue = when {
            sceneChanged -> PushUpBlockReason.CAMERA_MOVED
            metrics == null || metrics.angleSource == PushUpAngleSource.NONE -> PushUpBlockReason.ARMS_HIDDEN
            !support.valid -> PushUpBlockReason.SUPPORT_HIDDEN
            support.confidence < MIN_SUPPORT_QUALITY -> PushUpBlockReason.LOW_SIGNAL_QUALITY
            !view.confident -> PushUpBlockReason.LOW_SIGNAL_QUALITY
            !viewUsable -> PushUpBlockReason.CAMERA_ANGLE
            !postureSupported -> PushUpBlockReason.NOT_IN_POSITION
            !height.isFinite() || height < MIN_SUPPORT_HEIGHT -> PushUpBlockReason.HANDS_NOT_PLANTED
            !wristStable -> PushUpBlockReason.HANDS_NOT_PLANTED
            metrics.angle < MIN_TOP_ANGLE -> PushUpBlockReason.EXTEND_ARMS
            else -> PushUpBlockReason.NONE
        }

        return PushUpFrameSignalsV3(
            timestampMs = timestampMs,
            hasBody = true,
            armAngle = metrics?.angle ?: Float.NaN,
            armAngleQuality = metrics?.armAngleQuality ?: 0f,
            armAngleSource = metrics?.angleSource ?: PushUpAngleSource.NONE,
            angleAgreement = angleAgreement,
            heightRatio = height,
            heightQuality = metrics?.heightQuality ?: 0f,
            torsoInclination = torsoInclination,
            torsoQuality = torsoQuality,
            supportValid = support.valid,
            supportConfidence = support.confidence,
            supportSpread = support.normalSpreadDegrees,
            wristStable = wristStable,
            wristSpread = wristSpread,
            hipSupportRatio = hipSupportRatio,
            viewScore = view.frontScore,
            viewClass = view.viewClass,
            viewUsable = viewUsable,
            postureSupported = postureSupported,
            hipAngle = hipAngle,
            fullyInFrame = fullyInFrame,
            sceneChanged = sceneChanged,
            sideChanged = sideChanged,
            issue = issue
        )
    }

    private fun sideMetrics(
        image: List<Landmark3>,
        world: List<Landmark3>,
        side: Side,
        aspect: Float,
        timestampMs: Long,
        up: Vec3
    ): SideMetrics {
        val shoulderId = shoulderIndex(side)
        val elbowId = elbowIndex(side)
        val wristId = wristIndex(side)
        val hipId = hipIndex(side)
        val shoulder = image.getOrNull(shoulderId)
        val elbow = image.getOrNull(elbowId)
        val wrist = image.getOrNull(wristId)
        val hip = image.getOrNull(hipId)
        val shoulderWorld = world.getOrNull(shoulderId)
        val elbowWorld = world.getOrNull(elbowId)
        val wristWorld = world.getOrNull(wristId)
        val imageQ = minOf(
            shoulder?.confidence ?: 0f,
            elbow?.confidence ?: 0f,
            wrist?.confidence ?: 0f
        )
        val worldQ = minOf(shoulderWorld?.confidence ?: 0f, elbowWorld?.confidence ?: 0f, wristWorld?.confidence ?: 0f)
        if (shoulder == null || elbow == null || wrist == null || shoulderWorld == null ||
            elbowWorld == null || wristWorld == null
        ) {
            updateLast2DAngle(side, Float.NaN)
            return SideMetrics(
                angle = Float.NaN,
                angle3D = Float.NaN,
                angle2D = Float.NaN,
                angleSource = PushUpAngleSource.NONE,
                armAngleQuality = 0f,
                heightQuality = 0f,
                armLength = 0f,
                shoulderHeight = 0f
            )
        }
        val armLength = (shoulderWorld.toVec3() - elbowWorld.toVec3()).length() +
            (elbowWorld.toVec3() - wristWorld.toVec3()).length()
        val lengthHistory = armLengths.getOrPut(side) { ArrayDeque() }
        if (worldQ >= MIN_WORLD_ARM_QUALITY && armLength > 1e-4f) {
            lengthHistory.addLast(armLength)
            while (lengthHistory.size > ARM_HISTORY_SIZE) lengthHistory.removeFirst()
        }
        val referenceArmLength = medianOf(lengthHistory.toList())
        val lengthRatio = if (referenceArmLength > 1e-4f) armLength / referenceArmLength else 1f
        val stableLength = armLength > 1e-4f && lengthRatio in ARM_LENGTH_MIN_RATIO..ARM_LENGTH_MAX_RATIO
        val angle3D = angleDegrees3D(
            shoulderWorld.toVec3(),
            elbowWorld.toVec3(),
            wristWorld.toVec3()
        )
        val angle2D = angleDegrees2D(shoulder, elbow, wrist, aspect)
        val previous2D = last2DAngle(side)
        val angleSource: PushUpAngleSource
        val angle: Float
        val quality: Float
        if (worldQ >= MIN_WORLD_ARM_QUALITY && imageQ >= MIN_ARM_QUALITY &&
            angle3D.isFinite() && stableLength
        ) {
            angleSource = PushUpAngleSource.WORLD
            angle = angle3D
            quality = minOf(worldQ, imageQ)
        } else if (imageQ >= MIN_2D_ARM_QUALITY &&
            angle2D.isFinite() &&
            (!previous2D.isFinite() || abs(angle2D - previous2D) <= MAX_2D_ANGLE_JUMP)
        ) {
            angleSource = PushUpAngleSource.IMAGE
            angle = angle2D
            quality = imageQ * 0.8f
        } else {
            angleSource = PushUpAngleSource.NONE
            angle = Float.NaN
            quality = 0f
        }
        updateLast2DAngle(side, angle2D)

        val wristSample = wristSamples.getOrPut(side) { ArrayDeque() }
        wristSample.addLast(WristSample(timestampMs, wrist.x, wrist.y))
        while (wristSample.isNotEmpty() && timestampMs - wristSample.first().timestampMs > WRIST_WINDOW_MS) {
            wristSample.removeFirst()
        }

        val shoulderHeight = if (up.isValid()) {
            (shoulderWorld.toVec3() - wristWorld.toVec3()).dot(up)
        } else {
            Float.NaN
        }

        return SideMetrics(
            angle = angle,
            angle3D = angle3D,
            angle2D = angle2D,
            angleSource = angleSource,
            armAngleQuality = quality,
            heightQuality = minOf(
                shoulder.confidence,
                wrist.confidence,
                shoulderWorld.confidence,
                wristWorld.confidence
            ),
            armLength = armLength,
            shoulderHeight = if (shoulderHeight.isFinite()) shoulderHeight else 0f
        )
    }

    private fun selectSide(left: SideMetrics, right: SideMetrics): Side? {
        val leftUsable = left.armAngleQuality >= MIN_SIDE_QUALITY
        val rightUsable = right.armAngleQuality >= MIN_SIDE_QUALITY
        val current = stickySide
        if (current != null) {
            val currentMetrics = if (current == Side.LEFT) left else right
            val otherMetrics = if (current == Side.LEFT) right else left
            if (currentMetrics.armAngleQuality >= MIN_SIDE_QUALITY &&
                currentMetrics.armAngleQuality >= otherMetrics.armAngleQuality - SIDE_SWITCH_MARGIN
            ) {
                return current
            }
        }
        return when {
            leftUsable && rightUsable -> {
                if (right.armAngleQuality > left.armAngleQuality) Side.RIGHT else Side.LEFT
            }

            leftUsable -> Side.LEFT
            rightUsable -> Side.RIGHT
            else -> null
        }
    }

    private fun perSideTorso(
        image: List<Landmark3>,
        world: List<Landmark3>,
        shoulderId: Int,
        hipId: Int,
        up: Vec3
    ): Float? {
        val shoulderConfidence = image.getOrNull(shoulderId)?.confidence ?: 0f
        val hipConfidence = image.getOrNull(hipId)?.confidence ?: 0f
        if (minOf(shoulderConfidence, hipConfidence) < MIN_TORSO_JOINT_QUALITY) return null
        val shoulder = world.getOrNull(shoulderId) ?: return null
        val hip = world.getOrNull(hipId) ?: return null
        if (minOf(shoulder.confidence, hip.confidence) < MIN_WORLD_TORSO_QUALITY) return null
        if (!up.isValid()) return null
        val inclination = inclinationToPlaneDegrees(shoulder.toVec3() - hip.toVec3(), up)
        return inclination.takeIf { it.isFinite() }
    }

    private fun sideHipAngle(
        image: List<Landmark3>,
        world: List<Landmark3>,
        shoulderId: Int,
        hipId: Int,
        kneeId: Int
    ): Float {
        val confidence = listOf(shoulderId, hipId, kneeId).minOf {
            image.getOrNull(it)?.confidence ?: 0f
        }
        if (confidence < MIN_JOINT_CONFIDENCE) return Float.NaN
        val shoulder = world.getOrNull(shoulderId) ?: return Float.NaN
        val hip = world.getOrNull(hipId) ?: return Float.NaN
        val knee = world.getOrNull(kneeId) ?: return Float.NaN
        if (minOf(shoulder.confidence, hip.confidence, knee.confidence) < MIN_WORLD_TORSO_QUALITY) {
            return Float.NaN
        }
        return angleDegrees3D(shoulder.toVec3(), hip.toVec3(), knee.toVec3())
    }

    private fun strictMidpoint(
        landmarks: List<Landmark3>,
        left: Int,
        right: Int
    ): Landmark3? {
        val a = landmarks.getOrNull(left) ?: return null
        val b = landmarks.getOrNull(right) ?: return null
        if (minOf(a.confidence, b.confidence) < MIN_BODY_MID_QUALITY) return null
        return Landmark3(
            x = (a.x + b.x) / 2f,
            y = (a.y + b.y) / 2f,
            z = (a.z + b.z) / 2f,
            visibility = minOf(a.visibility, b.visibility),
            presence = minOf(a.presence, b.presence)
        )
    }

    private fun strictMidpointWorld(world: List<Landmark3>, left: Int, right: Int): Vec3? {
        val a = world.getOrNull(left) ?: return null
        val b = world.getOrNull(right) ?: return null
        if (minOf(a.confidence, b.confidence) < MIN_BODY_MID_QUALITY) return null
        return (a.toVec3() + b.toVec3()) / 2f
    }

    private fun singleJointWorld(world: List<Landmark3>, index: Int): Vec3? {
        val landmark = world.getOrNull(index) ?: return null
        if (landmark.confidence < MIN_WORLD_TORSO_QUALITY) return null
        return landmark.toVec3()
    }

    private fun hipSupportRatio(
        world: List<Landmark3>,
        selectedSide: Side?,
        shoulderMid: Vec3?,
        up: Vec3
    ): Float {
        if (!up.isValid() || shoulderMid == null) return Float.NaN
        val hips = listOf(PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)
            .mapNotNull { id -> world.getOrNull(id)?.takeIf { it.confidence >= MIN_SUPPORT_JOINT_QUALITY } }
        if (hips.isEmpty()) return Float.NaN
        val hip = hips.fold(Vec3.ZERO) { sum, landmark -> sum + landmark.toVec3() } /
            hips.size.toFloat()
        val wristIds = when (selectedSide) {
            Side.LEFT -> listOf(PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST)
            Side.RIGHT -> listOf(PoseLandmarks.RIGHT_WRIST, PoseLandmarks.LEFT_WRIST)
            null -> listOf(PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST)
        }
        val wrist = wristIds.asSequence()
            .mapNotNull { world.getOrNull(it) }
            .firstOrNull { it.confidence >= MIN_SUPPORT_JOINT_QUALITY }
            ?.toVec3() ?: return Float.NaN
        val torsoLength = (shoulderMid - hip).length()
        if (torsoLength < 1e-4f) return Float.NaN
        return (hip - wrist).dot(up) / torsoLength
    }

    private fun wristIsStable(samples: ArrayDeque<WristSample>?, aspect: Float): Boolean {
        if (samples == null || samples.size < 2) return false
        val duration = samples.last().timestampMs - samples.first().timestampMs
        if (duration < WRIST_MIN_DURATION_MS) return false
        val spread = wristSpread(samples, aspect)
        return spread.isFinite() && spread <= WRIST_MAX_SPREAD
    }

    private fun wristSpread(samples: ArrayDeque<WristSample>?, aspect: Float): Float {
        if (samples == null || samples.size < 2) return Float.NaN
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        samples.forEach {
            minX = minOf(minX, it.x)
            maxX = maxOf(maxX, it.x)
            minY = minOf(minY, it.y)
            maxY = maxOf(maxY, it.y)
        }
        val dx = (maxX - minX) * aspect
        val dy = maxY - minY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun last2DAngle(side: Side): Float {
        if (side == Side.LEFT) return lastLeft2DAngle
        return lastRight2DAngle
    }

    private fun updateLast2DAngle(side: Side, value: Float) {
        if (side == Side.LEFT) {
            lastLeft2DAngle = value
        } else {
            lastRight2DAngle = value
        }
    }

    private fun averageOf(first: Float, second: Float): Float = when {
        !first.isFinite() && !second.isFinite() -> Float.NaN
        !first.isFinite() -> second
        !second.isFinite() -> first
        else -> (first + second) / 2f
    }

    private fun medianOf(values: List<Float>): Float = median(values)

    private fun shoulderIndex(side: Side) =
        if (side == Side.LEFT) PoseLandmarks.LEFT_SHOULDER else PoseLandmarks.RIGHT_SHOULDER

    private fun elbowIndex(side: Side) =
        if (side == Side.LEFT) PoseLandmarks.LEFT_ELBOW else PoseLandmarks.RIGHT_ELBOW

    private fun wristIndex(side: Side) =
        if (side == Side.LEFT) PoseLandmarks.LEFT_WRIST else PoseLandmarks.RIGHT_WRIST

    private fun hipIndex(side: Side) =
        if (side == Side.LEFT) PoseLandmarks.LEFT_HIP else PoseLandmarks.RIGHT_HIP

    private fun empty(timestampMs: Long, issue: PushUpBlockReason): PushUpFrameSignalsV3 =
        PushUpFrameSignalsV3(
            timestampMs = timestampMs,
            hasBody = false,
            armAngle = Float.NaN,
            armAngleQuality = 0f,
            armAngleSource = PushUpAngleSource.NONE,
            angleAgreement = Float.NaN,
            heightRatio = Float.NaN,
            heightQuality = 0f,
            torsoInclination = Float.NaN,
            torsoQuality = 0f,
            supportValid = false,
            supportConfidence = 0f,
            supportSpread = Float.MAX_VALUE,
            wristStable = false,
            wristSpread = Float.NaN,
            hipSupportRatio = Float.NaN,
            viewScore = Float.NaN,
            viewClass = PushUpViewClass.UNKNOWN,
            viewUsable = false,
            postureSupported = false,
            hipAngle = Float.NaN,
            fullyInFrame = false,
            sceneChanged = false,
            sideChanged = false,
            issue = issue
        )

    private data class SideMetrics(
        val angle: Float,
        val angle3D: Float,
        val angle2D: Float,
        val angleSource: PushUpAngleSource,
        val armAngleQuality: Float,
        val heightQuality: Float,
        val armLength: Float,
        val shoulderHeight: Float
    )

    private data class WristSample(
        val timestampMs: Long,
        val x: Float,
        val y: Float
    )

    companion object {
        private val BODY_INDICES = intArrayOf(
            PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
            PoseLandmarks.LEFT_ELBOW, PoseLandmarks.RIGHT_ELBOW,
            PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST,
            PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP,
            PoseLandmarks.LEFT_KNEE, PoseLandmarks.RIGHT_KNEE,
            PoseLandmarks.LEFT_ANKLE, PoseLandmarks.RIGHT_ANKLE
        )
        private const val MIN_ARM_QUALITY = 0.35f
        private const val MIN_WORLD_ARM_QUALITY = 0.25f
        private const val MIN_2D_ARM_QUALITY = 0.45f
        private const val MIN_WORLD_TORSO_QUALITY = 0.25f
        private const val MIN_SIDE_QUALITY = 0.30f
        private const val MIN_TORSO_JOINT_QUALITY = 0.35f
        private const val MIN_TORSO_QUALITY = 0.35f
        private const val MIN_JOINT_CONFIDENCE = 0.35f
        private const val MIN_BODY_MID_QUALITY = 0.35f
        private const val FRONTAL_ARM_QUALITY = 0.45f
        private const val FRONTAL_AGREEMENT_LIMIT = 20f
        private const val MAX_TORSO_INCLINATION = 55f
        private const val MIN_HIP_SUPPORT_RATIO = 0.10f
        private const val MIN_TOP_ANGLE = 140f
        private const val MIN_SUPPORT_HEIGHT = 0.18f
        private const val MIN_SUPPORT_QUALITY = 0.20f
        private const val MIN_SUPPORT_JOINT_QUALITY = 0.20f
        private const val ARM_HISTORY_SIZE = 24
        private const val ARM_LENGTH_MIN_RATIO = 0.50f
        private const val ARM_LENGTH_MAX_RATIO = 2.00f
        private const val MAX_2D_ANGLE_JUMP = 35f
        private const val SIDE_SWITCH_MARGIN = 0.10f
        private const val WRIST_WINDOW_MS = 500L
        private const val WRIST_MIN_DURATION_MS = 300L
        private const val WRIST_MAX_SPREAD = 0.07f
        private const val SCENE_RATIO_MIN = 0.50f
        private const val SCENE_RATIO_MAX = 2.00f
        private const val SCENE_JUMP = 0.40f
        private const val SCENE_CONFIRM_MS = 300L
    }
}

private fun Vec3.isValid(): Boolean = length() > 0.5f
