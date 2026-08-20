package com.example.eyeprotect

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager

object CalibrationPrefs {
    private const val KEY_CALIBRATION_SCHEMA_VERSION = "calibration_schema_version"
    const val KEY_IRIS_THRESHOLD = "iris_threshold"
    const val KEY_EYE_OPEN_THRESHOLD = "eye_open_threshold"
    const val KEY_SLOUCH_THRESHOLD = "slouch_angle_threshold"
    private const val KEY_IRIS_THRESHOLD_PORTRAIT = "iris_threshold_portrait"
    private const val KEY_EYE_OPEN_THRESHOLD_PORTRAIT = "eye_open_threshold_portrait"
    private const val KEY_SLOUCH_THRESHOLD_PORTRAIT = "slouch_angle_threshold_portrait"
    private const val KEY_IRIS_THRESHOLD_LANDSCAPE = "iris_threshold_landscape"
    private const val KEY_EYE_OPEN_THRESHOLD_LANDSCAPE = "eye_open_threshold_landscape"
    private const val KEY_SLOUCH_THRESHOLD_LANDSCAPE = "slouch_angle_threshold_landscape"
    private const val CURRENT_CALIBRATION_SCHEMA_VERSION = 3

    private val irisRange = 0.03f..0.45f
    private val eyeOpenRange = 0.10f..0.90f
    private val slouchRange = 0.10f..2.50f

    enum class OrientationBucket {
        PORTRAIT,
        LANDSCAPE
    }

    data class Thresholds(
        val irisThreshold: Float,
        val eyeOpenThreshold: Float,
        val slouchThreshold: Float
    )

    fun hasValidCalibration(prefs: SharedPreferences): Boolean {
        ensureCurrentCalibrationSchema(prefs)
        return loadThresholds(prefs, OrientationBucket.PORTRAIT) != null ||
            loadThresholds(prefs, OrientationBucket.LANDSCAPE) != null ||
            loadLegacyThresholds(prefs) != null
    }

    fun hasCompleteCalibrationSet(prefs: SharedPreferences): Boolean {
        ensureCurrentCalibrationSchema(prefs)
        return loadThresholds(prefs, OrientationBucket.PORTRAIT) != null &&
            loadThresholds(prefs, OrientationBucket.LANDSCAPE) != null
    }

    fun hasValidCalibration(prefs: SharedPreferences, orientation: Int): Boolean {
        return resolveThresholds(prefs, orientation) != null
    }

    fun currentDeviceOrientation(context: Context): Int {
        val contextDisplayRotation = runCatching { context.display?.rotation }.getOrNull()
        val rotation = contextDisplayRotation
            ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: run {
                @Suppress("DEPRECATION")
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation
            }

        return when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
            Surface.ROTATION_0, Surface.ROTATION_180 -> Configuration.ORIENTATION_PORTRAIT
            else -> context.resources.configuration.orientation
        }
    }

    fun missingBuckets(prefs: SharedPreferences): List<OrientationBucket> {
        ensureCurrentCalibrationSchema(prefs)
        return buildList {
            if (loadThresholds(prefs, OrientationBucket.PORTRAIT) == null) add(OrientationBucket.PORTRAIT)
            if (loadThresholds(prefs, OrientationBucket.LANDSCAPE) == null) add(OrientationBucket.LANDSCAPE)
        }
    }

    fun currentBucket(orientation: Int): OrientationBucket {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            OrientationBucket.LANDSCAPE
        } else {
            OrientationBucket.PORTRAIT
        }
    }

    fun resolveThresholds(
        prefs: SharedPreferences,
        orientation: Int
    ): Thresholds? {
        ensureCurrentCalibrationSchema(prefs)
        val bucket = currentBucket(orientation)
        return loadThresholds(prefs, bucket)
            ?: loadLegacyThresholds(prefs)
            ?: loadThresholds(
                prefs,
                if (bucket == OrientationBucket.PORTRAIT) OrientationBucket.LANDSCAPE else OrientationBucket.PORTRAIT
            )
    }

    fun saveThresholds(
        prefs: SharedPreferences,
        orientation: Int,
        thresholds: Thresholds
    ) {
        ensureCurrentCalibrationSchema(prefs)
        val bucket = currentBucket(orientation)
        val sanitized = Thresholds(
            irisThreshold = sanitizeIrisThreshold(thresholds.irisThreshold),
            eyeOpenThreshold = sanitizeEyeOpenThreshold(thresholds.eyeOpenThreshold),
            slouchThreshold = sanitizeSlouchThreshold(thresholds.slouchThreshold)
        )

        val (irisKey, eyeKey, slouchKey) = when (bucket) {
            OrientationBucket.PORTRAIT -> Triple(
                KEY_IRIS_THRESHOLD_PORTRAIT,
                KEY_EYE_OPEN_THRESHOLD_PORTRAIT,
                KEY_SLOUCH_THRESHOLD_PORTRAIT
            )
            OrientationBucket.LANDSCAPE -> Triple(
                KEY_IRIS_THRESHOLD_LANDSCAPE,
                KEY_EYE_OPEN_THRESHOLD_LANDSCAPE,
                KEY_SLOUCH_THRESHOLD_LANDSCAPE
            )
        }

        prefs.edit()
            .putInt(KEY_CALIBRATION_SCHEMA_VERSION, CURRENT_CALIBRATION_SCHEMA_VERSION)
            .putFloat(irisKey, sanitized.irisThreshold)
            .putFloat(eyeKey, sanitized.eyeOpenThreshold)
            .putFloat(slouchKey, sanitized.slouchThreshold)
            // Keep legacy keys in sync as a compatibility fallback.
            .putFloat(KEY_IRIS_THRESHOLD, sanitized.irisThreshold)
            .putFloat(KEY_EYE_OPEN_THRESHOLD, sanitized.eyeOpenThreshold)
            .putFloat(KEY_SLOUCH_THRESHOLD, sanitized.slouchThreshold)
            .commit()
    }

    fun clearAllCalibration(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_IRIS_THRESHOLD)
            .remove(KEY_EYE_OPEN_THRESHOLD)
            .remove(KEY_SLOUCH_THRESHOLD)
            .remove(KEY_IRIS_THRESHOLD_PORTRAIT)
            .remove(KEY_EYE_OPEN_THRESHOLD_PORTRAIT)
            .remove(KEY_SLOUCH_THRESHOLD_PORTRAIT)
            .remove(KEY_IRIS_THRESHOLD_LANDSCAPE)
            .remove(KEY_EYE_OPEN_THRESHOLD_LANDSCAPE)
            .remove(KEY_SLOUCH_THRESHOLD_LANDSCAPE)
            .putInt(KEY_CALIBRATION_SCHEMA_VERSION, CURRENT_CALIBRATION_SCHEMA_VERSION)
            .commit()
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

    fun ensureCurrentCalibrationSchema(prefs: SharedPreferences) {
        val storedVersion = prefs.getInt(KEY_CALIBRATION_SCHEMA_VERSION, 0)
        if (storedVersion >= CURRENT_CALIBRATION_SCHEMA_VERSION) return

        clearAllCalibration(prefs)
    }

    private fun loadThresholds(
        prefs: SharedPreferences,
        bucket: OrientationBucket
    ): Thresholds? {
        val (irisKey, eyeKey, slouchKey) = when (bucket) {
            OrientationBucket.PORTRAIT -> Triple(
                KEY_IRIS_THRESHOLD_PORTRAIT,
                KEY_EYE_OPEN_THRESHOLD_PORTRAIT,
                KEY_SLOUCH_THRESHOLD_PORTRAIT
            )
            OrientationBucket.LANDSCAPE -> Triple(
                KEY_IRIS_THRESHOLD_LANDSCAPE,
                KEY_EYE_OPEN_THRESHOLD_LANDSCAPE,
                KEY_SLOUCH_THRESHOLD_LANDSCAPE
            )
        }
        if (!prefs.contains(irisKey) || !prefs.contains(eyeKey) || !prefs.contains(slouchKey)) return null
        val thresholds = Thresholds(
            irisThreshold = prefs.getFloat(irisKey, Float.NaN),
            eyeOpenThreshold = prefs.getFloat(eyeKey, Float.NaN),
            slouchThreshold = prefs.getFloat(slouchKey, Float.NaN)
        )
        return if (hasValidCalibration(thresholds.irisThreshold, thresholds.eyeOpenThreshold, thresholds.slouchThreshold)) {
            thresholds
        } else {
            null
        }
    }

    private fun loadLegacyThresholds(prefs: SharedPreferences): Thresholds? {
        if (!prefs.contains(KEY_IRIS_THRESHOLD) ||
            !prefs.contains(KEY_EYE_OPEN_THRESHOLD) ||
            !prefs.contains(KEY_SLOUCH_THRESHOLD)
        ) {
            return null
        }
        val thresholds = Thresholds(
            irisThreshold = prefs.getFloat(KEY_IRIS_THRESHOLD, Float.NaN),
            eyeOpenThreshold = prefs.getFloat(KEY_EYE_OPEN_THRESHOLD, Float.NaN),
            slouchThreshold = prefs.getFloat(KEY_SLOUCH_THRESHOLD, Float.NaN)
        )
        return if (hasValidCalibration(thresholds.irisThreshold, thresholds.eyeOpenThreshold, thresholds.slouchThreshold)) {
            thresholds
        } else {
            null
        }
    }

    private fun Float.isValidIn(range: ClosedFloatingPointRange<Float>): Boolean = !isNaN() && this in range
}
