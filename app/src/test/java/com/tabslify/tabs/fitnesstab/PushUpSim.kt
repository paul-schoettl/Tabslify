package com.tabslify.tabs.fitnesstab

object PushUpSim {
    const val FRAME_30_FPS = 33L
    const val FRAME_12_FPS = 83L

    fun repStream(
        reps: Int,
        frameMs: Long,
        startMs: Long = 1_000L,
        topExtension: Float = 1.08f,
        bottomExtension: Float = 0.45f,
        elbowTop: Float = 170f,
        elbowBottom: Float = 90f,
        postureHorizontal: Boolean = true,
        wristPlantedTop: Boolean = true,
        holdTopMs: Long = 150L,
        descendMs: Long = 400L,
        holdBottomMs: Long = 100L,
        ascendMs: Long = 400L
    ): List<PushUpSample> {
        val stream = ArrayList<PushUpSample>()
        var time = startMs

        fun emit(extension: Float, elbow: Float) {
            stream.add(
                PushUpSample(
                    timestampMs = time,
                    armExtension = extension,
                    elbowAngle = elbow,
                    elbowConfident = true,
                    wristPlantedTop = wristPlantedTop,
                    wristPlantedWide = wristPlantedTop,
                    postureHorizontal = postureHorizontal,
                    valid = true
                )
            )
            time += frameMs
        }

        fun ramp(durationMs: Long, fromExt: Float, toExt: Float, fromElbow: Float, toElbow: Float) {
            if (durationMs <= 0L) return
            val steps = (durationMs / frameMs).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val fraction = step.toFloat() / steps.toFloat()
                emit(
                    fromExt + (toExt - fromExt) * fraction,
                    fromElbow + (toElbow - fromElbow) * fraction
                )
            }
        }

        repeat(reps) {
            ramp(holdTopMs, topExtension, topExtension, elbowTop, elbowTop)
            ramp(descendMs, topExtension, bottomExtension, elbowTop, elbowBottom)
            ramp(holdBottomMs, bottomExtension, bottomExtension, elbowBottom, elbowBottom)
            ramp(ascendMs, bottomExtension, topExtension, elbowBottom, elbowTop)
        }
        ramp((holdTopMs * 2).coerceAtLeast(250L), topExtension, topExtension, elbowTop, elbowTop)
        return stream
    }

    fun steadyStream(
        durationMs: Long,
        frameMs: Long,
        extension: Float,
        elbow: Float = 170f,
        postureHorizontal: Boolean = true,
        wristPlantedTop: Boolean = false,
        startMs: Long = 1_000L
    ): List<PushUpSample> {
        val stream = ArrayList<PushUpSample>()
        var time = startMs
        while (time <= startMs + durationMs) {
            stream.add(
                PushUpSample(
                    timestampMs = time,
                    armExtension = extension,
                    elbowAngle = elbow,
                    elbowConfident = true,
                    wristPlantedTop = wristPlantedTop,
                    wristPlantedWide = wristPlantedTop,
                    postureHorizontal = postureHorizontal,
                    valid = true
                )
            )
            time += frameMs
        }
        return stream
    }

    fun injectInvalid(
        stream: List<PushUpSample>,
        fromMs: Long,
        durationMs: Long
    ): List<PushUpSample> = stream.map { sample ->
        if (sample.timestampMs in fromMs until (fromMs + durationMs)) {
            sample.copy(armExtension = Float.NaN, elbowAngle = Float.NaN, valid = false)
        } else {
            sample
        }
    }

    fun run(stream: List<PushUpSample>, counter: PushUpCounter = PushUpCounter()): PushUpCounter {
        for (sample in stream) counter.submit(sample)
        return counter
    }
}
