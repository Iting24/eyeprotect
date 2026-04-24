package com.example.eyeprotect.monitoring

import android.content.Context
import android.content.Intent
import com.example.eyeprotect.EyeHealthAccessibilityService

object LiveMonitoringStore {

    fun publishMetrics(context: Context, metrics: MonitoringMetrics) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val warningsMask = mergeWarningsMask(
            previousWarningsMask = prefs.getInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, 0),
            incomingWarningsMask = metrics.warningsMask,
            isCameraFrame = metrics.isCameraFrame
        )
        val editor = prefs.edit()
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_TS, metrics.ts)
            .putInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, warningsMask)
            .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_IS_CAMERA_FRAME, metrics.isCameraFrame)
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_FACE_SEEN_UPTIME_MS, metrics.lastFaceDetectedTime)
        if (metrics.isCameraFrame) {
            editor
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_IRIS_NORM, metrics.irisNorm ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_EYE_OPEN_MIN, metrics.eyeOpenMin ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_SLOUCH_SCORE, metrics.slouchScore ?: Float.NaN)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_DETECTED, metrics.faceDetected)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_DETECTED, metrics.poseDetected)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_ERROR, metrics.faceError)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_ERROR, metrics.poseError)
        }
        metrics.pitchDeg?.let { editor.putFloat(EyeHealthAccessibilityService.PREF_LIVE_PITCH_DEG, it) }
        metrics.rollDeg?.let { editor.putFloat(EyeHealthAccessibilityService.PREF_LIVE_ROLL_DEG, it) }
        metrics.tiltDeg?.let { editor.putFloat(EyeHealthAccessibilityService.PREF_LIVE_TILT_DEG, it) }
        editor.apply()

        val intent = Intent(EyeHealthAccessibilityService.ACTION_LIVE_METRICS).apply {
            setPackage(context.packageName)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TS, metrics.ts)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_WARNINGS_MASK, warningsMask)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IS_CAMERA_FRAME, metrics.isCameraFrame)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_SEEN_UPTIME_MS, metrics.lastFaceDetectedTime)
            if (metrics.isCameraFrame) {
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IRIS_NORM, metrics.irisNorm ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN, metrics.eyeOpenMin ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE, metrics.slouchScore ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_DETECTED, metrics.faceDetected)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_DETECTED, metrics.poseDetected)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_ERROR, metrics.faceError)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_ERROR, metrics.poseError)
            }
            metrics.pitchDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_PITCH_DEG, it) }
            metrics.rollDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_ROLL_DEG, it) }
            metrics.tiltDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TILT_DEG, it) }
        }
        context.sendBroadcast(intent)
    }

    fun publishPaused(context: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        publishMetrics(
            context = context,
            metrics = MonitoringMetrics(
                ts = now,
                warningsMask = 0,
                isLyingActive = false,
                lastFaceDetectedTime = 0L,
                isCameraFrame = true,
                irisNorm = Float.NaN,
                eyeOpenMin = Float.NaN,
                slouchScore = Float.NaN
            )
        )
    }

    fun mergeWarningsMask(
        previousWarningsMask: Int,
        incomingWarningsMask: Int,
        isCameraFrame: Boolean
    ): Int {
        if (isCameraFrame) return incomingWarningsMask and 0xF

        // Sensor-only frames should update only lying state and orientation.
        // Keep camera-derived warnings until the next camera frame clears them.
        return (previousWarningsMask and 0x7) or (incomingWarningsMask and 0x8)
    }

    private const val PREFS_NAME = "eyeprotect_prefs"
}
