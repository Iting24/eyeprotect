package com.example.eyeprotect

import android.content.SharedPreferences

object CalibrationPrefs {
    const val KEY_IRIS_THRESHOLD = "iris_threshold"
    const val KEY_EYE_OPEN_THRESHOLD = "eye_open_threshold"
    const val KEY_SLOUCH_THRESHOLD = "slouch_angle_threshold"

    private val irisRange = 0.03f..0.45f
    private val eyeOpenRange = 0.10f..0.90f
    private val slouchRange = 0.10f..2.50f

    fun hasValidCalibration(prefs: SharedPreferences): Boolean {
        if (!prefs.contains(KEY_IRIS_THRESHOLD) ||
            !prefs.contains(KEY_EYE_OPEN_THRESHOLD) ||
            !prefs.contains(KEY_SLOUCH_THRESHOLD)
        ) {
            return false
        }
        return hasValidCalibration(
            irisThreshold = prefs.getFloat(KEY_IRIS_THRESHOLD, Float.NaN),
            eyeOpenThreshold = prefs.getFloat(KEY_EYE_OPEN_THRESHOLD, Float.NaN),
            slouchThreshold = prefs.getFloat(KEY_SLOUCH_THRESHOLD, Float.NaN)
        )
    }

    fun hasValidCalibration(
        irisThreshold: Float,
        eyeOpenThreshold: Float,
        slouchThreshold: Float
    ): Boolean {
        return irisThreshold.isValidIn(irisRange) &&
            eyeOpenThreshold.isValidIn(eyeOpenRange) &&
            slouchThreshold.isValidIn(slouchRange)
    }

    fun sanitizeIrisThreshold(value: Float): Float = value.coerceIn(irisRange.start, irisRange.endInclusive)

    fun sanitizeEyeOpenThreshold(value: Float): Float = value.coerceIn(eyeOpenRange.start, eyeOpenRange.endInclusive)

    fun sanitizeSlouchThreshold(value: Float): Float = value.coerceIn(slouchRange.start, slouchRange.endInclusive)

    private fun Float.isValidIn(range: ClosedFloatingPointRange<Float>): Boolean = !isNaN() && this in range
}
