package com.tabslify.tabs.fitnesstab

import kotlinx.serialization.json.Json

data class PushUpReplayResult(
    val frameCount: Int,
    val count: Int,
    val calibrated: Boolean,
    val lastIssue: PushUpBlockReason,
    val lastState: String,
    val durationsMs: List<Long>,
    val trace: List<PushUpReplayTrace>,
    val sequenceErrors: Int = 0,
    val timestampErrors: Int = 0
)

data class PushUpReplayTrace(
    val timestampMs: Long,
    val angle: Float,
    val torsoInclination: Float,
    val heightRatio: Float,
    val viewClass: PushUpViewClass,
    val issue: PushUpBlockReason,
    val counterState: String,
    val armExtension: Float = Float.NaN,
    val wristPlanted: Boolean = false,
    val postureHorizontal: Boolean = false,
    val valid: Boolean = false,
    val supportValid: Boolean = false,
    val wristStable: Boolean = false
)

class PushUpReplay(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun header(text: String): PushUpCaptureHeader? {
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val decoded = json.decodeFromString<PushUpCaptureLine>(line)
            if (decoded.type == "header" && decoded.header != null) {
                return decoded.header
            }
        }
        return null
    }

    fun parse(text: String): List<PushUpCaptureFrame> {
        val frames = ArrayList<PushUpCaptureFrame>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val decoded = json.decodeFromString<PushUpCaptureLine>(line)
            if (decoded.type == "frame" && decoded.frame != null) {
                frames.add(decoded.frame)
            }
        }
        return frames
    }

    fun replayV3(text: String): PushUpReplayResult {
        val frames = parse(text)
        val extractor = PushUpSignalExtractorV3()
        val counter = PushUpCounterV3()
        val trace = ArrayList<PushUpReplayTrace>()
        val durations = ArrayList<Long>()
        var previousTimestamp = 0L
        var previousSequence = -1L
        var sequenceErrors = 0
        var timestampErrors = 0
        var lostFrames = 0
        frames.sortedBy { it.sequence }.forEach { frame ->
            if (previousSequence >= 0L && frame.sequence != previousSequence + 1L) sequenceErrors++
            previousSequence = frame.sequence
            if (frame.timestampMs <= previousTimestamp) {
                timestampErrors++
                return@forEach
            }
            previousTimestamp = frame.timestampMs
            val image = frame.landmarks.map { it.toLandmark3() }
            val world = frame.worldLandmarks.map { it.toLandmark3() }
            val signals = extractor.analyze(
                image = image,
                world = world,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                timestampMs = frame.timestampMs
            )
            if (!signals.hasBody) {
                lostFrames++
                if (lostFrames >= 8) counter.onPoseLost()
                trace.add(
                    PushUpReplayTrace(
                        timestampMs = frame.timestampMs,
                        angle = Float.NaN,
                        torsoInclination = Float.NaN,
                        heightRatio = Float.NaN,
                        viewClass = PushUpViewClass.UNKNOWN,
                        issue = PushUpBlockReason.NO_BODY,
                        counterState = counter.stateName
                    )
                )
                return@forEach
            }
            lostFrames = 0
            val sample = PushUpSampleV3(
                timestampMs = frame.timestampMs,
                armAngle = signals.armAngle,
                angleQuality = signals.armAngleQuality,
                angleSource = signals.armAngleSource,
                heightRatio = signals.heightRatio,
                heightQuality = signals.heightQuality,
                torsoInclination = signals.torsoInclination,
                torsoQuality = signals.torsoQuality,
                supportValid = signals.supportValid,
                supportConfidence = signals.supportConfidence,
                wristStable = signals.wristStable,
                viewUsable = signals.viewUsable,
                postureSupported = signals.postureSupported,
                sceneChanged = signals.sceneChanged,
                sideChanged = signals.sideChanged,
                issue = signals.issue
            )
            val rep = counter.submit(sample)
            if (rep != null) durations.add(rep.durationMs)
            trace.add(
                PushUpReplayTrace(
                    timestampMs = frame.timestampMs,
                    angle = signals.armAngle,
                    torsoInclination = signals.torsoInclination,
                    heightRatio = signals.heightRatio,
                    viewClass = signals.viewClass,
                    issue = signals.issue,
                    counterState = counter.stateName,
                    supportValid = signals.supportValid,
                    wristStable = signals.wristStable
                )
            )
        }
        return PushUpReplayResult(
            frameCount = frames.size,
            count = counter.count,
            calibrated = counter.calibrated,
            lastIssue = counter.issue,
            lastState = counter.stateName,
            durationsMs = durations,
            trace = trace,
            sequenceErrors = sequenceErrors,
            timestampErrors = timestampErrors
        )
    }

    fun replayLegacy(text: String): PushUpReplayResult {
        val frames = parse(text)
        val extractor = PushUpSignalExtractor()
        val counter = PushUpCounter()
        val trace = ArrayList<PushUpReplayTrace>()
        val durations = ArrayList<Long>()
        var previousTimestamp = 0L
        var previousSequence = -1L
        var sequenceErrors = 0
        var timestampErrors = 0
        frames.sortedBy { it.sequence }.forEach { frame ->
            if (previousSequence >= 0L && frame.sequence != previousSequence + 1L) sequenceErrors++
            previousSequence = frame.sequence
            if (frame.timestampMs <= previousTimestamp) {
                timestampErrors++
                return@forEach
            }
            previousTimestamp = frame.timestampMs
            val image = frame.landmarks.map { it.toLandmark3() }
            val world = frame.worldLandmarks.map { it.toLandmark3() }
            val signals = extractor.analyze(
                image = image,
                world = world,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                timestampMs = frame.timestampMs
            )
            val sample = PushUpSample(
                timestampMs = frame.timestampMs,
                armExtension = signals.armExtension,
                elbowAngle = signals.elbowAngle,
                elbowConfident = signals.elbowConfident,
                wristPlantedTop = signals.wristPlantedTop,
                wristPlantedWide = signals.wristPlantedWide,
                postureHorizontal = signals.postureHorizontal,
                valid = signals.valid
            )
            val rep = counter.submit(sample)
            if (rep != null) durations.add(rep.durationMs)
            val issue = when {
                !signals.valid -> PushUpBlockReason.TRACKING_UNSTABLE
                !signals.postureHorizontal -> PushUpBlockReason.NOT_IN_POSITION
                !signals.wristPlantedTop -> PushUpBlockReason.HANDS_NOT_PLANTED
                else -> PushUpBlockReason.NONE
            }
            trace.add(
                PushUpReplayTrace(
                    timestampMs = frame.timestampMs,
                    angle = signals.elbowAngle,
                    torsoInclination = signals.torsoInclination,
                    heightRatio = Float.NaN,
                    viewClass = PushUpViewClass.UNKNOWN,
                    issue = issue,
                    counterState = counter.phase.name,
                    armExtension = signals.armExtension,
                    wristPlanted = signals.wristPlantedTop,
                    postureHorizontal = signals.postureHorizontal,
                    valid = signals.valid
                )
            )
        }
        return PushUpReplayResult(
            frameCount = frames.size,
            count = counter.count,
            calibrated = counter.calibrated,
            lastIssue = PushUpBlockReason.NONE,
            lastState = counter.phase.name,
            durationsMs = durations,
            trace = trace,
            sequenceErrors = sequenceErrors,
            timestampErrors = timestampErrors
        )
    }

    private fun PushUpCaptureLandmark.toLandmark3() = Landmark3(
        x = x,
        y = y,
        z = z,
        visibility = visibility,
        presence = presence
    )
}
