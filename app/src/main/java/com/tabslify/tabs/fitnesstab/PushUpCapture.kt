package com.tabslify.tabs.fitnesstab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.tabslify.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class PushUpCaptureLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float
)

@Serializable
data class PushUpCaptureGravity(
    val timestampNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

@Serializable
data class PushUpCaptureHeader(
    val captureId: String,
    val schemaVersion: Int,
    val startedAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val viewAngle: String,
    val distanceMeters: Float,
    val lighting: String,
    val cameraRollDegrees: Float,
    val tempo: String,
    val form: String,
    val expectedReps: Int,
    val scenario: String,
    val notes: String
)

@Serializable
data class PushUpCaptureFrame(
    val sequence: Long,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val frontCamera: Boolean,
    val rotationDegrees: Int,
    val landmarks: List<PushUpCaptureLandmark>,
    val worldLandmarks: List<PushUpCaptureLandmark>,
    val gravity: PushUpCaptureGravity?
)

@Serializable
data class PushUpCaptureEvent(
    val timestampMs: Long,
    val name: String,
    val detail: String
)

@Serializable
data class PushUpCaptureLine(
    val type: String,
    val header: PushUpCaptureHeader? = null,
    val frame: PushUpCaptureFrame? = null,
    val event: PushUpCaptureEvent? = null
)

data class PushUpCaptureLabel(
    val viewAngle: String = "unspecified",
    val distanceMeters: Float = 0f,
    val lighting: String = "unspecified",
    val cameraRollDegrees: Float = 0f,
    val tempo: String = "unspecified",
    val form: String = "unspecified",
    val expectedReps: Int = 0,
    val scenario: String = "positive",
    val notes: String = ""
)

data class PushUpCapturePreset(
    val name: String,
    val label: PushUpCaptureLabel
)

val PUSH_UP_CAPTURE_PRESETS = listOf(
    PushUpCapturePreset(
        "SIDE 2M",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "SIDE 4M",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 4f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "SIDE 1.5M",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 1.5f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "45 2M",
        PushUpCaptureLabel(
            viewAngle = "oblique_45",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "NEAR FRONT",
        PushUpCaptureLabel(
            viewAngle = "near_front_25",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "EXACT FRONT",
        PushUpCaptureLabel(
            viewAngle = "front_0",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "KNEE SIDE",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "knee",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "BACKLIGHT",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "backlight",
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "ROLL 10",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            cameraRollDegrees = 10f,
            tempo = "normal",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "FAST",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "fast",
            form = "full",
            expectedReps = 10,
            scenario = "positive"
        )
    ),
    PushUpCapturePreset(
        "NEG SIDE",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "negative",
            expectedReps = 0,
            scenario = "negative_plank_or_floor"
        )
    ),
    PushUpCapturePreset(
        "NEG 45",
        PushUpCaptureLabel(
            viewAngle = "oblique_45",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "negative",
            expectedReps = 0,
            scenario = "negative_other_motion"
        )
    ),
    PushUpCapturePreset(
        "NEG FRONT",
        PushUpCaptureLabel(
            viewAngle = "near_front_25",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "negative",
            expectedReps = 0,
            scenario = "negative_other_motion"
        )
    ),
    PushUpCapturePreset(
        "SHALLOW SIDE",
        PushUpCaptureLabel(
            viewAngle = "side",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "shallow",
            expectedReps = 0,
            scenario = "negative_shallow"
        )
    ),
    PushUpCapturePreset(
        "SHALLOW 45",
        PushUpCaptureLabel(
            viewAngle = "oblique_45",
            distanceMeters = 2f,
            lighting = "normal",
            tempo = "normal",
            form = "shallow",
            expectedReps = 0,
            scenario = "negative_shallow"
        )
    )
)

class PushUpGravitySource(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile
    var latest: PushUpCaptureGravity? = null
        private set

    init {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        latest = PushUpCaptureGravity(
            timestampNanos = event.timestamp,
            x = event.values[0],
            y = event.values[1],
            z = event.values[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun close() {
        sensorManager.unregisterListener(this)
    }
}

class PushUpCaptureRecorder(
    private val context: Context,
    private val gravitySource: PushUpGravitySource
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private var writer: BufferedWriter? = null
    private var id: String? = null
    private var sequence = 0L

    @Synchronized
    fun start(label: PushUpCaptureLabel = PushUpCaptureLabel()): String? {
        if (writer != null) return id
        return runCatching {
            val now = System.currentTimeMillis()
            val captureId = "pushup_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, CAPTURE_DIR).apply { mkdirs() }
            val file = File(dir, "$captureId.jsonl")
            val output = file.bufferedWriter()
            val header = PushUpCaptureLine(
                type = "header",
                header = PushUpCaptureHeader(
                    captureId = captureId,
                    schemaVersion = CAPTURE_SCHEMA,
                    startedAtEpochMs = now,
                    startedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
                    appVersion = BuildConfig.VERSION_NAME,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    sdkInt = Build.VERSION.SDK_INT,
                    viewAngle = label.viewAngle,
                    distanceMeters = label.distanceMeters,
                    lighting = label.lighting,
                    cameraRollDegrees = label.cameraRollDegrees,
                    tempo = label.tempo,
                    form = label.form,
                    expectedReps = label.expectedReps,
                    scenario = label.scenario,
                    notes = label.notes
                )
            )
            output.write(json.encodeToString(header))
            output.newLine()
            output.flush()
            writer = output
            id = captureId
            sequence = 0L
            captureId
        }.getOrNull()
    }

    @Synchronized
    fun isRecording(): Boolean = writer != null

    @Synchronized
    fun record(frame: PoseFrame) {
        val output = writer ?: return
        runCatching {
            val line = PushUpCaptureLine(
                type = "frame",
                frame = PushUpCaptureFrame(
                    sequence = sequence++,
                    timestampMs = frame.timestampMs,
                    imageWidth = frame.imageWidth,
                    imageHeight = frame.imageHeight,
                    frontCamera = frame.frontCamera,
                    rotationDegrees = frame.rotationDegrees,
                    landmarks = frame.landmarks.map {
                        PushUpCaptureLandmark(
                            x = it.x(),
                            y = it.y(),
                            z = it.z(),
                            visibility = it.visibility().orElse(0f),
                            presence = it.presence().orElse(0f)
                        )
                    },
                    worldLandmarks = frame.worldLandmarks.map {
                        PushUpCaptureLandmark(
                            x = it.x(),
                            y = it.y(),
                            z = it.z(),
                            visibility = it.visibility().orElse(0f),
                            presence = it.presence().orElse(0f)
                        )
                    },
                    gravity = gravitySource.latest
                )
            )
            output.write(json.encodeToString(line))
            output.newLine()
            if (sequence % 10L == 0L) output.flush()
        }.onFailure { stopInternal("write_failed") }
    }

    @Synchronized
    fun event(name: String, detail: String = "") {
        val output = writer ?: return
        runCatching {
            val line = PushUpCaptureLine(
                type = "event",
                event = PushUpCaptureEvent(
                    timestampMs = android.os.SystemClock.elapsedRealtime(),
                    name = name,
                    detail = detail
                )
            )
            output.write(json.encodeToString(line))
            output.newLine()
            output.flush()
        }.onFailure { stopInternal("event_failed") }
    }

    @Synchronized
    fun stop(): String? {
        val finishedId = id
        stopInternal("stop")
        return finishedId
    }

    private fun stopInternal(detail: String) {
        val output = writer
        if (output != null) {
            runCatching {
                val line = PushUpCaptureLine(
                    type = "event",
                    event = PushUpCaptureEvent(
                        timestampMs = android.os.SystemClock.elapsedRealtime(),
                        name = detail,
                        detail = ""
                    )
                )
                output.write(json.encodeToString(line))
                output.newLine()
                output.flush()
                output.close()
            }
        }
        writer = null
        id = null
    }

    companion object {
        const val CAPTURE_SCHEMA = 1
        const val CAPTURE_DIR = "pushup_captures"
    }
}
