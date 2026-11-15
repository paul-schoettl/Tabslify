package com.tabslify.tabs.fitnesstab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpCapturePresetTest {

    @Test
    fun presetsHaveUniqueNamesAndConsistentExpectedCounts() {
        val names = PUSH_UP_CAPTURE_PRESETS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        PUSH_UP_CAPTURE_PRESETS.forEach { preset ->
            if (preset.label.scenario.startsWith("negative")) {
                assertEquals(0, preset.label.expectedReps)
            } else {
                assertEquals(10, preset.label.expectedReps)
            }
            assertTrue(preset.label.viewAngle.isNotBlank())
            assertTrue(preset.label.form.isNotBlank())
        }
    }
}
