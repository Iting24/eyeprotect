package com.example.eyeprotect

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

enum class WarningState {
    TOO_CLOSE,
    SLOUCHING,
    SQUINTING
}

class PostureAndEyeDetector {

    var enableTooCloseWarning = false
    var enableSlouchWarning = false
    var enableSquintWarning = false

    var irisDistanceThreshold = 0.12f
    var slouchingAngleThresholdDegrees = 25.0
    var eyeOpenThreshold = 0.4f // 低於此值視為瞇眼

    fun computeNormalizedIrisDistance(face: Face, imageWidth: Int): Float? {
        val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        if (leftEye == null || rightEye == null || imageWidth <= 0) return null

        val dist = sqrt((leftEye.x - rightEye.x).pow(2) + (leftEye.y - rightEye.y).pow(2))
        return dist / imageWidth.toFloat()
    }

    fun computeEyeOpenMin(face: Face): Float? {
        val leftOpen = face.leftEyeOpenProbability
        val rightOpen = face.rightEyeOpenProbability

        return when {
            leftOpen != null && rightOpen != null -> minOf(leftOpen, rightOpen)
            leftOpen != null -> leftOpen
            rightOpen != null -> rightOpen
            else -> null
        }
    }

    fun computeSlouchAngleDegrees(pose: Pose): Double? {
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        if (leftEar == null || rightEar == null || leftShoulder == null || rightShoulder == null) return null

        val earY = (leftEar.position.y + rightEar.position.y) / 2
        val shoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2

        val dy = abs(shoulderY - earY)
        if (dy <= 0f) return null

        // 肩寬作為尺度，耳朵越接近肩膀(垂直距離越小)則嚴重度越高。
        val shoulderWidth = sqrt(
            (leftShoulder.position.x - rightShoulder.position.x).pow(2) +
                (leftShoulder.position.y - rightShoulder.position.y).pow(2)
        )
        if (shoulderWidth <= 0f) return null

        // 角度型嚴重度：atan2(肩寬, 耳朵-肩膀垂直距離)，越接近 90 度代表越低頭/駝背。
        return Math.toDegrees(atan2(shoulderWidth.toDouble(), dy.toDouble()))
    }

    fun detectWarnings(
        face: Face?,
        pose: Pose?,
        imageWidth: Int,
        imageHeight: Int
    ): Set<WarningState> {
        val warnings = mutableSetOf<WarningState>()

        face?.let {
            // 1. 偵測距離 (瞳距)
            if (enableTooCloseWarning) {
                val normalizedDist = computeNormalizedIrisDistance(it, imageWidth)
                if (normalizedDist != null && normalizedDist > irisDistanceThreshold) {
                    warnings.add(WarningState.TOO_CLOSE)
                }
            }

            // 2. 偵測瞇眼 (使用 ML Kit 分類結果)
            if (enableSquintWarning) {
                val eyeOpenMin = computeEyeOpenMin(it)
                if (eyeOpenMin != null && eyeOpenMin < eyeOpenThreshold) {
                    warnings.add(WarningState.SQUINTING)
                }
            }
        }

        pose?.let {
            // 3. 偵測駝背 (檢查耳朵相對於肩膀的前傾角度)
            if (enableSlouchWarning) {
                val angle = computeSlouchAngleDegrees(it)
                if (angle != null && angle > slouchingAngleThresholdDegrees) {
                    warnings.add(WarningState.SLOUCHING)
                }
            }
        }

        return warnings
    }
}
