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
        val detectedWarningsMask = mergeWarningsMask(
            previousWarningsMask = prefs.getInt(PREF_LAST_DETECTED_WARNINGS_MASK, 0),
            incomingWarningsMask = metrics.detectedWarningsMask,
            isCameraFrame = metrics.isCameraFrame
        )
        updateSessionSummaryFromWarnings(
            prefs = prefs,
            warningsMask = warningsMask,
            nowEpochMs = System.currentTimeMillis(),
        )
        val editor = prefs.edit()
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_TS, metrics.ts)
            .putInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, warningsMask)
            .putInt(PREF_LAST_DETECTED_WARNINGS_MASK, detectedWarningsMask)
            .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_IS_CAMERA_FRAME, metrics.isCameraFrame)
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_FACE_SEEN_UPTIME_MS, metrics.lastFaceDetectedTime)
        if (metrics.isCameraFrame) {
            editor
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_IRIS_NORM, metrics.irisNorm ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_LEFT_EYE_OPEN, metrics.leftEyeOpen ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_RIGHT_EYE_OPEN, metrics.rightEyeOpen ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_EYE_OPEN_MIN, metrics.eyeOpenMin ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_SLOUCH_SCORE, metrics.slouchScore ?: Float.NaN)
                .putFloat(EyeHealthAccessibilityService.PREF_LIVE_FACE_PITCH_DEG, metrics.facePitchDeg ?: Float.NaN)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_DETECTED, metrics.faceDetected)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_DETECTED, metrics.poseDetected)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_ERROR, metrics.faceError)
                .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_ERROR, metrics.poseError)
        }
        metrics.squintHoldMs?.let { editor.putLong(EyeHealthAccessibilityService.PREF_LIVE_SQUINT_HOLD_MS, it) }
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
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_LEFT_EYE_OPEN, metrics.leftEyeOpen ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_RIGHT_EYE_OPEN, metrics.rightEyeOpen ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN, metrics.eyeOpenMin ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE, metrics.slouchScore ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_PITCH_DEG, metrics.facePitchDeg ?: Float.NaN)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_DETECTED, metrics.faceDetected)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_DETECTED, metrics.poseDetected)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_ERROR, metrics.faceError)
                putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_ERROR, metrics.poseError)
            }
            metrics.squintHoldMs?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SQUINT_HOLD_MS, it) }
            metrics.pitchDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_PITCH_DEG, it) }
            metrics.rollDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_ROLL_DEG, it) }
            metrics.tiltDeg?.let { putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TILT_DEG, it) }
        }
        context.sendBroadcast(intent)
    }

    fun publishPaused(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_TS, android.os.SystemClock.uptimeMillis())
            .putInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, 0)
            .putInt(PREF_LAST_DETECTED_WARNINGS_MASK, 0)
            .putInt(PREF_SESSION_TRACKING_WARNINGS_MASK, 0)
            .putBoolean(EyeHealthAccessibilityService.PREF_LIVE_IS_CAMERA_FRAME, true)
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_FACE_SEEN_UPTIME_MS, 0L)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_IRIS_NORM, Float.NaN)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_LEFT_EYE_OPEN, Float.NaN)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_RIGHT_EYE_OPEN, Float.NaN)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_EYE_OPEN_MIN, Float.NaN)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_SLOUCH_SCORE, Float.NaN)
            .putFloat(EyeHealthAccessibilityService.PREF_LIVE_FACE_PITCH_DEG, Float.NaN)
            .putLong(EyeHealthAccessibilityService.PREF_LIVE_SQUINT_HOLD_MS, 0L)
            .apply()

        val intent = Intent(EyeHealthAccessibilityService.ACTION_LIVE_METRICS).apply {
            setPackage(context.packageName)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TS, android.os.SystemClock.uptimeMillis())
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_WARNINGS_MASK, 0)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IS_CAMERA_FRAME, true)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_SEEN_UPTIME_MS, 0L)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IRIS_NORM, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_LEFT_EYE_OPEN, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_RIGHT_EYE_OPEN, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_PITCH_DEG, Float.NaN)
            putExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SQUINT_HOLD_MS, 0L)
        }
        context.sendBroadcast(intent)
    }

    fun resetSessionSummary(context: Context, startedAtEpochMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(MonitoringForegroundService.PREF_LAST_SESSION_STARTED_AT, startedAtEpochMs)
            .putLong(MonitoringForegroundService.PREF_LAST_SESSION_DURATION_MS, 0L)
            .putInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT, 0)
            .putInt(MonitoringForegroundService.PREF_LAST_SQUINT_COUNT, 0)
            .putInt(MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT, 0)
            .putInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_CORRECTION_COUNT, 0)
            .putInt(MonitoringForegroundService.PREF_LAST_SQUINT_CORRECTION_COUNT, 0)
            .putInt(MonitoringForegroundService.PREF_LAST_SLOUCH_CORRECTION_COUNT, 0)
            .putInt(PREF_LAST_DETECTED_WARNINGS_MASK, 0)
            .putInt(PREF_SESSION_TRACKING_WARNINGS_MASK, 0)
            .putLong(PREF_TOO_CLOSE_REMINDER_AT, 0L)
            .putLong(PREF_SQUINT_REMINDER_AT, 0L)
            .putLong(PREF_SLOUCH_REMINDER_AT, 0L)
            .apply()
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

    private fun updateSessionSummaryFromWarnings(
        prefs: android.content.SharedPreferences,
        warningsMask: Int,
        nowEpochMs: Long,
    ) {
        val previousMask = prefs.getInt(PREF_SESSION_TRACKING_WARNINGS_MASK, 0) and 0x7
        val currentMask = warningsMask and 0x7
        val editor = prefs.edit()

        processWarningBit(
            prefs = prefs,
            editor = editor,
            previousMask = previousMask,
            currentMask = currentMask,
            bit = MonitoringIssueType.TOO_CLOSE.mask,
            reminderCountKey = MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT,
            correctionCountKey = MonitoringForegroundService.PREF_LAST_TOO_CLOSE_CORRECTION_COUNT,
            reminderAtKey = PREF_TOO_CLOSE_REMINDER_AT,
            nowEpochMs = nowEpochMs,
        )
        processWarningBit(
            prefs = prefs,
            editor = editor,
            previousMask = previousMask,
            currentMask = currentMask,
            bit = MonitoringIssueType.SQUINTING.mask,
            reminderCountKey = MonitoringForegroundService.PREF_LAST_SQUINT_COUNT,
            correctionCountKey = MonitoringForegroundService.PREF_LAST_SQUINT_CORRECTION_COUNT,
            reminderAtKey = PREF_SQUINT_REMINDER_AT,
            nowEpochMs = nowEpochMs,
        )
        processWarningBit(
            prefs = prefs,
            editor = editor,
            previousMask = previousMask,
            currentMask = currentMask,
            bit = MonitoringIssueType.SLOUCHING.mask,
            reminderCountKey = MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT,
            correctionCountKey = MonitoringForegroundService.PREF_LAST_SLOUCH_CORRECTION_COUNT,
            reminderAtKey = PREF_SLOUCH_REMINDER_AT,
            nowEpochMs = nowEpochMs,
        )

        editor.putInt(PREF_SESSION_TRACKING_WARNINGS_MASK, currentMask).apply()
    }

    private fun processWarningBit(
        prefs: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
        previousMask: Int,
        currentMask: Int,
        bit: Int,
        reminderCountKey: String,
        correctionCountKey: String,
        reminderAtKey: String,
        nowEpochMs: Long,
    ) {
        val wasActive = previousMask and bit != 0
        val isActive = currentMask and bit != 0
        if (!wasActive && isActive) {
            editor.putInt(reminderCountKey, prefs.getInt(reminderCountKey, 0) + 1)
            editor.putLong(reminderAtKey, nowEpochMs)
            return
        }
        if (wasActive && !isActive) {
            val remindedAt = prefs.getLong(reminderAtKey, 0L)
            if (remindedAt > 0L && nowEpochMs - remindedAt <= ImmediateCorrectionTracker.DEFAULT_CORRECTION_WINDOW_MS) {
                editor.putInt(correctionCountKey, prefs.getInt(correctionCountKey, 0) + 1)
            }
            editor.putLong(reminderAtKey, 0L)
        }
    }

    private const val PREFS_NAME = "eyeprotect_prefs"
    private const val PREF_LAST_DETECTED_WARNINGS_MASK = "last_detected_warnings_mask"
    private const val PREF_SESSION_TRACKING_WARNINGS_MASK = "session_tracking_warnings_mask"
    private const val PREF_TOO_CLOSE_REMINDER_AT = "too_close_reminder_at"
    private const val PREF_SQUINT_REMINDER_AT = "squint_reminder_at"
    private const val PREF_SLOUCH_REMINDER_AT = "slouch_reminder_at"
}
