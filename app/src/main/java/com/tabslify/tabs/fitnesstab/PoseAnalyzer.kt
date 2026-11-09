package com.tabslify.tabs.fitnesstab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

data class PoseFrame(
    val landmarks: List<NormalizedLandmark>,
    val worldLandmarks: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int
)

class PoseAnalyzer(
    context: Context,
    private val onFrame: (PoseFrame) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var isFrontCamera: Boolean = true

    private var landmarker: PoseLandmarker? = null
    private var lastTimestampMs = 0L
    private var closed = false

    private val inFlightBitmaps = java.util.ArrayDeque<Bitmap>()

    init {
        landmarker = createLandmarker(context, Delegate.GPU)
            ?: createLandmarker(context, Delegate.CPU)
        if (landmarker == null) onFailure(IllegalStateException("PoseLandmarker konnte nicht erstellt werden"))
    }

    private fun createLandmarker(context: Context, delegate: Delegate): PoseLandmarker? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input -> publish(result, input) }
            .setErrorListener { error -> onFailure(error) }
            .build()

        PoseLandmarker.createFromOptions(context, options)
    }.getOrNull()

    private fun publish(result: PoseLandmarkerResult, input: MPImage) {
        val bmp = synchronized(inFlightBitmaps) { inFlightBitmaps.pollFirst() }
        if (closed) {
            runCatching { input.close() }
            bmp?.let { if (!it.isRecycled) it.recycle() }
            return
        }
        onFrame(
            PoseFrame(
                landmarks = result.landmarks().firstOrNull().orEmpty(),
                worldLandmarks = result.worldLandmarks().firstOrNull().orEmpty(),
                imageWidth = input.width,
                imageHeight = input.height
            )
        )
        runCatching { input.close() }
        bmp?.let { if (!it.isRecycled) it.recycle() }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val detector = landmarker
        if (detector == null || closed) {
            imageProxy.close()
            return
        }

        var bitmap: Bitmap? = null
        var raw: Bitmap? = null
        try {
            raw = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f, raw.width / 2f, raw.height / 2f)
            }
            bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } catch (t: Throwable) {
            onFailure(t)
        } finally {
            imageProxy.close()
            raw?.recycle()
        }

        val rotated = bitmap ?: return

        val timestamp = SystemClock.uptimeMillis().coerceAtLeast(lastTimestampMs + 1)
        lastTimestampMs = timestamp

        synchronized(inFlightBitmaps) { inFlightBitmaps.addLast(rotated) }
        runCatching { detector.detectAsync(BitmapImageBuilder(rotated).build(), timestamp) }
            .onFailure {
                val failed = synchronized(inFlightBitmaps) { inFlightBitmaps.remove(rotated) }
                if (failed) rotated.recycle()
                onFailure(it)
            }
    }

    fun close() {
        closed = true
        runCatching { landmarker?.close() }
        landmarker = null
        synchronized(inFlightBitmaps) {
            while (inFlightBitmaps.isNotEmpty()) {
                inFlightBitmaps.pollFirst()?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
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
    val elbowAngle: Float,
    val bodyAngle: Float,
    val torsoInclination: Float,
    val armConfidence: Float,
    val poseConfidence: Float,
    val armsVisible: Boolean,
    val fullyInFrame: Boolean,
    val wristsPlanted: Boolean,
    val valid: Boolean
)

object PoseMetrics {
    const val MIN_VISIBILITY = 0.45f
    const val MIN_PRESENCE = 0.45f
    private const val MIN_CONFIDENCE = 0.45f
    private const val MAX_ELBOW_SIDE_DIFF = 35f
    private const val WRIST_UNDER_SHOULDER_RATIO = 0.45f
    private const val WRIST_BELOW_SHOULDER_MARGIN = 0.01f

    private val LEFT_ARM = intArrayOf(
        PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.LEFT_ELBOW, PoseLandmarks.LEFT_WRIST
    )
    private val RIGHT_ARM = intArrayOf(
        PoseLandmarks.RIGHT_SHOULDER, PoseLandmarks.RIGHT_ELBOW, PoseLandmarks.RIGHT_WRIST
    )
    private val BODY_INDICES = intArrayOf(
        PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER,
        PoseLandmarks.LEFT_ELBOW, PoseLandmarks.RIGHT_ELBOW,
        PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST,
        PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP,
        PoseLandmarks.LEFT_KNEE, PoseLandmarks.RIGHT_KNEE,
        PoseLandmarks.LEFT_ANKLE, PoseLandmarks.RIGHT_ANKLE
    )

    fun visibility(frame: PoseFrame, index: Int): Float {
        val landmark = frame.landmarks.getOrNull(index) ?: return 0f
        return landmark.visibility().orElse(0f)
    }

    private fun confidence(frame: PoseFrame, index: Int): Float {
        val landmark = frame.landmarks.getOrNull(index) ?: return 0f
        val visibility = landmark.visibility().orElse(0f)
        val presence = landmark.presence().orElse(0f)
        return minOf(visibility, presence)
    }

    private fun jointOk(frame: PoseFrame, index: Int): Boolean =
        confidence(frame, index) >= MIN_CONFIDENCE

    private fun sideArmVisible(frame: PoseFrame, indices: IntArray): Boolean =
        indices.all { jointOk(frame, it) }

    private fun sideArmConfidence(frame: PoseFrame, indices: IntArray): Float =
        indices.minOf { confidence(frame, it) }

    private fun armsVisible(frame: PoseFrame): Boolean =
        sideArmVisible(frame, LEFT_ARM) && sideArmVisible(frame, RIGHT_ARM)

    fun isTrackable(frame: PoseFrame): Boolean {
        if (frame.landmarks.size < 33 || frame.worldLandmarks.size < 33) return false
        return sideArmVisible(frame, LEFT_ARM) || sideArmVisible(frame, RIGHT_ARM)
    }

    fun pushUpSignals(frame: PoseFrame): PushUpSignals {
        if (frame.landmarks.size < 33 || frame.worldLandmarks.size < 33) {
            return PushUpSignals(
                elbowAngle = Float.NaN,
                bodyAngle = Float.NaN,
                torsoInclination = Float.NaN,
                armConfidence = 0f,
                poseConfidence = 0f,
                armsVisible = false,
                fullyInFrame = false,
                wristsPlanted = false,
                valid = false
            )
        }

        val leftArmVisible = sideArmVisible(frame, LEFT_ARM)
        val rightArmVisible = sideArmVisible(frame, RIGHT_ARM)
        val bothArmsVisible = leftArmVisible && rightArmVisible
        val armConfidence = minOf(
            sideArmConfidence(frame, LEFT_ARM),
            sideArmConfidence(frame, RIGHT_ARM)
        )
        val poseConfidence = BODY_INDICES.minOf { confidence(frame, it) }

        val leftElbow = if (leftArmVisible) {
            sideAngle(
                frame,
                PoseLandmarks.LEFT_SHOULDER,
                PoseLandmarks.LEFT_ELBOW,
                PoseLandmarks.LEFT_WRIST
            )
        } else {
            Float.NaN
        }
        val rightElbow = if (rightArmVisible) {
            sideAngle(
                frame,
                PoseLandmarks.RIGHT_SHOULDER,
                PoseLandmarks.RIGHT_ELBOW,
                PoseLandmarks.RIGHT_WRIST
            )
        } else {
            Float.NaN
        }

        val elbowAngle = when {
            leftElbow.isNaN() || rightElbow.isNaN() -> Float.NaN
            kotlin.math.abs(leftElbow - rightElbow) > MAX_ELBOW_SIDE_DIFF -> Float.NaN
            kotlin.math.abs(leftElbow - rightElbow) <= 20f -> (leftElbow + rightElbow) / 2f
            sideArmConfidence(frame, LEFT_ARM) >= sideArmConfidence(frame, RIGHT_ARM) -> leftElbow
            else -> rightElbow
        }

        val bodyAngle = averageOf(
            sideBodyAngle(
                frame,
                PoseLandmarks.LEFT_SHOULDER,
                PoseLandmarks.LEFT_HIP,
                PoseLandmarks.LEFT_ANKLE
            ),
            sideBodyAngle(
                frame,
                PoseLandmarks.RIGHT_SHOULDER,
                PoseLandmarks.RIGHT_HIP,
                PoseLandmarks.RIGHT_ANKLE
            )
        )
        val torsoInclination = averageOf(
            sideTorsoInclination(frame, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.LEFT_HIP),
            sideTorsoInclination(frame, PoseLandmarks.RIGHT_SHOULDER, PoseLandmarks.RIGHT_HIP)
        )

        val fullyInFrame = BODY_INDICES.all { index ->
            val landmark = frame.landmarks.getOrNull(index) ?: return@all false
            landmark.x() in -0.01f..1.01f && landmark.y() in -0.01f..1.01f
        }

        val aspect = if (frame.imageHeight > 0) {
            frame.imageWidth.toFloat() / frame.imageHeight.toFloat()
        } else {
            1f
        }
        val shoulderMid = midpoint(frame, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER)
        val hipMid = midpoint(frame, PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)
        val torsoLength = if (shoulderMid != null && hipMid != null) {
            distance(shoulderMid, hipMid, aspect)
        } else {
            0f
        }
        val wristsPlanted = torsoLength > 1e-4f && (
            wristPlanted(
                frame,
                PoseLandmarks.LEFT_SHOULDER,
                PoseLandmarks.LEFT_WRIST,
                torsoLength,
                aspect
            ) || wristPlanted(
                frame,
                PoseLandmarks.RIGHT_SHOULDER,
                PoseLandmarks.RIGHT_WRIST,
                torsoLength,
                aspect
            )
        )

        val valid = bothArmsVisible &&
            fullyInFrame &&
            wristsPlanted &&
            poseConfidence >= MIN_CONFIDENCE &&
            !elbowAngle.isNaN() &&
            !bodyAngle.isNaN() &&
            !torsoInclination.isNaN()

        return PushUpSignals(
            elbowAngle = elbowAngle,
            bodyAngle = bodyAngle,
            torsoInclination = torsoInclination,
            armConfidence = armConfidence,
            poseConfidence = poseConfidence,
            armsVisible = bothArmsVisible,
            fullyInFrame = fullyInFrame,
            wristsPlanted = wristsPlanted,
            valid = valid
        )
    }

    private fun wristPlanted(
        frame: PoseFrame,
        shoulderIndex: Int,
        wristIndex: Int,
        torsoLength: Float,
        aspect: Float
    ): Boolean {
        if (!jointOk(frame, shoulderIndex) || !jointOk(frame, wristIndex)) return false
        val shoulder = frame.landmarks.getOrNull(shoulderIndex) ?: return false
        val wrist = frame.landmarks.getOrNull(wristIndex) ?: return false
        val horizontal = kotlin.math.abs(wrist.x() - shoulder.x()) * aspect
        val below = wrist.y() >= shoulder.y() + WRIST_BELOW_SHOULDER_MARGIN
        return below && horizontal <= WRIST_UNDER_SHOULDER_RATIO * torsoLength
    }

    private fun sideBodyAngle(frame: PoseFrame, shoulder: Int, hip: Int, ankle: Int): Float {
        if (!jointOk(frame, shoulder) || !jointOk(frame, hip) || !jointOk(frame, ankle)) {
            return Float.NaN
        }
        val s = frame.worldLandmarks.getOrNull(shoulder) ?: return Float.NaN
        val h = frame.worldLandmarks.getOrNull(hip) ?: return Float.NaN
        val a = frame.worldLandmarks.getOrNull(ankle) ?: return Float.NaN
        return angleAt(
            s.x(), s.y(), s.z(),
            h.x(), h.y(), h.z(),
            a.x(), a.y(), a.z()
        )
    }

    private fun sideTorsoInclination(frame: PoseFrame, shoulder: Int, hip: Int): Float {
        if (!jointOk(frame, shoulder) || !jointOk(frame, hip)) return Float.NaN
        val s = frame.worldLandmarks.getOrNull(shoulder) ?: return Float.NaN
        val h = frame.worldLandmarks.getOrNull(hip) ?: return Float.NaN
        val dy = kotlin.math.abs(s.y() - h.y())
        val dx = s.x() - h.x()
        val dz = s.z() - h.z()
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal < 1e-5f && dy < 1e-5f) return Float.NaN
        return Math.toDegrees(kotlin.math.atan2(dy, horizontal).toDouble()).toFloat()
    }

    private fun averageOf(left: Float, right: Float): Float = when {
        left.isNaN() && right.isNaN() -> Float.NaN
        left.isNaN() -> right
        right.isNaN() -> left
        else -> (left + right) / 2f
    }

    fun hipReliable(frame: PoseFrame): Boolean {
        val indices = listOf(
            PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP,
            PoseLandmarks.LEFT_KNEE, PoseLandmarks.RIGHT_KNEE
        )
        return indices.all { visibility(frame, it) >= MIN_VISIBILITY }
    }

    fun armExtension(frame: PoseFrame): Float {
        val shoulder = midpoint(frame, PoseLandmarks.LEFT_SHOULDER, PoseLandmarks.RIGHT_SHOULDER)
            ?: return Float.NaN
        val wrist = midpoint(frame, PoseLandmarks.LEFT_WRIST, PoseLandmarks.RIGHT_WRIST)
            ?: return Float.NaN
        val hip = midpoint(frame, PoseLandmarks.LEFT_HIP, PoseLandmarks.RIGHT_HIP)
            ?: return Float.NaN

        if (frame.imageHeight <= 0) return Float.NaN
        val aspect = frame.imageWidth.toFloat() / frame.imageHeight.toFloat()

        val torso = distance(shoulder, hip, aspect)
        if (torso < 1e-4f) return Float.NaN

        return distance(shoulder, wrist, aspect) / torso
    }

    private fun midpoint(frame: PoseFrame, left: Int, right: Int): Pair<Float, Float>? {
        val leftVisible = visibility(frame, left) >= MIN_VISIBILITY
        val rightVisible = visibility(frame, right) >= MIN_VISIBILITY
        val a = frame.landmarks.getOrNull(left)
        val b = frame.landmarks.getOrNull(right)

        return when {
            leftVisible && rightVisible && a != null && b != null ->
                (a.x() + b.x()) / 2f to (a.y() + b.y()) / 2f

            leftVisible && a != null -> a.x() to a.y()
            rightVisible && b != null -> b.x() to b.y()
            else -> null
        }
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>, aspect: Float): Float {
        val dx = (a.first - b.first) * aspect
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    fun elbowAngle(frame: PoseFrame): Float {
        val left = sideAngle(
            frame,
            PoseLandmarks.LEFT_SHOULDER,
            PoseLandmarks.LEFT_ELBOW,
            PoseLandmarks.LEFT_WRIST
        )
        val right = sideAngle(
            frame,
            PoseLandmarks.RIGHT_SHOULDER,
            PoseLandmarks.RIGHT_ELBOW,
            PoseLandmarks.RIGHT_WRIST
        )
        return when {
            left.isNaN() && right.isNaN() -> Float.NaN
            left.isNaN() -> right
            right.isNaN() -> left
            else -> (left + right) / 2f
        }
    }

    fun hipAngle(frame: PoseFrame): Float {
        val left = sideAngle(
            frame,
            PoseLandmarks.LEFT_SHOULDER,
            PoseLandmarks.LEFT_HIP,
            PoseLandmarks.LEFT_KNEE
        )
        val right = sideAngle(
            frame,
            PoseLandmarks.RIGHT_SHOULDER,
            PoseLandmarks.RIGHT_HIP,
            PoseLandmarks.RIGHT_KNEE
        )
        return when {
            left.isNaN() && right.isNaN() -> Float.NaN
            left.isNaN() -> right
            right.isNaN() -> left
            else -> (left + right) / 2f
        }
    }

    private fun sideAngle(frame: PoseFrame, a: Int, b: Int, c: Int): Float {
        if (visibility(frame, a) < MIN_VISIBILITY ||
            visibility(frame, b) < MIN_VISIBILITY ||
            visibility(frame, c) < MIN_VISIBILITY
        ) return Float.NaN

        val pa = frame.worldLandmarks.getOrNull(a) ?: return Float.NaN
        val pb = frame.worldLandmarks.getOrNull(b) ?: return Float.NaN
        val pc = frame.worldLandmarks.getOrNull(c) ?: return Float.NaN

        return angleAt(
            pa.x(), pa.y(), pa.z(),
            pb.x(), pb.y(), pb.z(),
            pc.x(), pc.y(), pc.z()
        )
    }
}
