package com.example.eyeprotect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPrefsTest {

    @Test
    fun `valid calibration accepts sane thresholds`() {
        assertTrue(
            CalibrationPrefs.hasValidCalibration(
                irisThreshold = 0.12f,
                eyeOpenThreshold = 0.45f,
                slouchThreshold = 0.8f
            )
        )
    }

    @Test
    fun `valid calibration rejects missing or corrupt threshold values`() {
        assertFalse(CalibrationPrefs.hasValidCalibration(Float.NaN, 0.45f, 0.8f))
        assertFalse(CalibrationPrefs.hasValidCalibration(0.12f, 0f, 0.8f))
        assertFalse(CalibrationPrefs.hasValidCalibration(0.12f, 0.45f, 99f))
    }

    @Test
    fun `sanitizers clamp calibration thresholds to supported ranges`() {
        assertEquals(0.03f, CalibrationPrefs.sanitizeIrisThreshold(-1f), 0.0001f)
        assertEquals(0.90f, CalibrationPrefs.sanitizeEyeOpenThreshold(9f), 0.0001f)
        assertEquals(2.50f, CalibrationPrefs.sanitizeSlouchThreshold(99f), 0.0001f)
    }
}
