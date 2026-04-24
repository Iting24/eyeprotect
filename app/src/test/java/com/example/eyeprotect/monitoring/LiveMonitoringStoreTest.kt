package com.example.eyeprotect.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMonitoringStoreTest {

    @Test
    fun `camera frame replaces all warning bits`() {
        val merged = LiveMonitoringStore.mergeWarningsMask(
            previousWarningsMask = 0xF,
            incomingWarningsMask = 0x2,
            isCameraFrame = true
        )

        assertEquals(0x2, merged)
    }

    @Test
    fun `sensor frame only changes lying bit and preserves camera warnings`() {
        val merged = LiveMonitoringStore.mergeWarningsMask(
            previousWarningsMask = 0x7,
            incomingWarningsMask = 0x8,
            isCameraFrame = false
        )

        assertEquals(0xF, merged)
    }

    @Test
    fun `sensor frame clears only lying bit when lying ends`() {
        val merged = LiveMonitoringStore.mergeWarningsMask(
            previousWarningsMask = 0xF,
            incomingWarningsMask = 0x0,
            isCameraFrame = false
        )

        assertEquals(0x7, merged)
    }
}
