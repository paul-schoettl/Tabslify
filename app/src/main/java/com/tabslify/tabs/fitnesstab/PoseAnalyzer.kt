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

data class PoseFrame(
    val landmarks: List<NormalizedLandmark>,
    val worldLandmarks: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int
)

fun PoseFrame.toLandmark3Lists(): Pair<List<Landmark3>, List<Landmark3>> {
    val image = landmarks.map {
        Landmark3(
            x = it.x(),
            y = it.y(),
            z = it.z(),
            visibility = it.visibility().orElse(0f),
            presence = it.presence().orElse(0f)
        )
    }
    val world = worldLandmarks.map {
        Landmark3(
            x = it.x(),
            y = it.y(),
            z = it.z(),
            visibility = it.visibility().orElse(0f),
            presence = it.presence().orElse(0f)
        )
    }
    return image to world
}

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
            if (raw != null && raw !== bitmap) raw.recycle()
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
