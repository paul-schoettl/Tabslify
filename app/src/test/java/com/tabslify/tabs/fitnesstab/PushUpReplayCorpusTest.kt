package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class PushUpReplayCorpusTest {

    @Test
    fun replayCorpusFromEnvironment() {
        val directoryPath = System.getenv("PUSHUP_CAPTURE_DIR") ?: return
        val directory = File(directoryPath)
        if (!directory.isDirectory) return
        val captures = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".jsonl")
        }.orEmpty().sortedBy { it.name }
        if (captures.isEmpty()) return

        val replay = PushUpReplay()
        val output = File(
            System.getenv("PUSHUP_REPLAY_OUT") ?: File(directory, "pushup-replay-report.txt").path
        )
        output.bufferedWriter().use { writer ->
            writer.write("capture\tscenario\tview\tform\texpected\tlegacy\tv3\tv3Calibrated\tfps\tv3State\tissue\tavgDurationMs\tsequenceErrors\ttimestampErrors\n")
            captures.forEach { file ->
                val text = file.readText()
                val header = replay.header(text)
                val legacy = replay.replayLegacy(text)
                val v3 = replay.replayV3(text)
                val durationMs = v3.trace.lastOrNull()?.timestampMs?.minus(
                    v3.trace.firstOrNull()?.timestampMs ?: 0L
                ) ?: 0L
                val fps = if (durationMs > 0L) {
                    v3.frameCount * 1_000f / durationMs.toFloat()
                } else {
                    0f
                }
                val averageDuration = if (v3.durationsMs.isEmpty()) {
                    0f
                } else {
                    v3.durationsMs.average().toFloat()
                }
                writer.write(
                    listOf(
                        file.name,
                        header?.scenario.orEmpty(),
                        header?.viewAngle.orEmpty(),
                        header?.form.orEmpty(),
                        header?.expectedReps?.toString().orEmpty(),
                        legacy.count.toString(),
                        v3.count.toString(),
                        v3.calibrated.toString(),
                        String.format(Locale.US, "%.2f", fps),
                        v3.lastState,
                        v3.lastIssue.name,
                        String.format(Locale.US, "%.1f", averageDuration),
                        v3.sequenceErrors.toString(),
                        v3.timestampErrors.toString()
                    ).joinToString("\t")
                )
                writer.newLine()
            }
            writer.newLine()
            writer.write("capture\tlegacyInvalid\tlegacyNotPosture\tlegacyNotPlanted\tlegacyLowExtension\tv3Issues\n")
            captures.forEach { file ->
                val text = file.readText()
                val legacy = replay.replayLegacy(text)
                val v3 = replay.replayV3(text)
                val invalid = legacy.trace.count { !it.valid }
                val notPosture = legacy.trace.count { it.valid && !it.postureHorizontal }
                val notPlanted = legacy.trace.count {
                    it.valid && it.postureHorizontal && !it.wristPlanted
                }
                val lowExtension = legacy.trace.count {
                    it.valid && it.postureHorizontal && it.wristPlanted &&
                        it.armExtension.isFinite() && it.armExtension < 0.82f
                }
                val issues = v3.trace.groupingBy { it.issue.name }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .joinToString(",") { "${it.key}:${it.value}" }
                writer.write(
                    listOf(
                        file.name,
                        invalid.toString(),
                        notPosture.toString(),
                        notPlanted.toString(),
                        lowExtension.toString(),
                        issues
                    ).joinToString("\t")
                )
                writer.newLine()
            }
        }
        assertTrue(output.isFile)

        if (System.getenv("PUSHUP_STRICT") == "1") {
            captures.forEach { file ->
                val text = file.readText()
                val header = replay.header(text) ?: return@forEach
                val result = replay.replayV3(text)
                assertTrue("${file.name}: sequence errors", result.sequenceErrors == 0)
                assertTrue("${file.name}: timestamp errors", result.timestampErrors == 0)
                if (header.scenario.startsWith("negative")) {
                    assertTrue(
                        "${file.name}: false positives ${result.count}",
                        result.count == 0
                    )
                } else if (header.viewAngle != "front_0") {
                    assertTrue(
                        "${file.name}: count ${result.count}, expected ${header.expectedReps}",
                        result.count in (header.expectedReps - 1)..(header.expectedReps + 1)
                    )
                }
            }
        }
    }
}
