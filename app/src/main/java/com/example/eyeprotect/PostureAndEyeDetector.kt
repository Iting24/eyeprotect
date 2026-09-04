package com.example.eyeprotect

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class WarningState {
    TOO_CLOSE,
    SLOUCHING,
    SQUINTING,
    LYING
}

class PostureAndEyeDetector {
    private val minPoseLikelihood = 0.65f

    var enableTooCloseWarning = false
    var enableSlouchWarning = false
    var enableSquintWarning = false

    var irisDistanceThreshold = 0.12f
    // Posture ratio = (ear-shoulder vertical distance) / (shoulder width).
    // Smaller ratio means head/neck collapsed forward/down more.
    var slouchingPostureRatioThreshold = 0.55
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

        if (leftOpen == null || rightOpen == null) return null
        return minOf(leftOpen, rightOpen)
    }

    fun computeEyeBrowGapRatio(face: Face): Float? {
        val leftEyeCenter = contourCenter(face, FaceContour.LEFT_EYE)
        val rightEyeCenter = contourCenter(face, FaceContour.RIGHT_EYE)
        val leftBrowCenter = contourCenter(face, FaceContour.LEFT_EYEBROW_TOP)
        val rightBrowCenter = contourCenter(face, FaceContour.RIGHT_EYEBROW_TOP)
        val faceHeight = face.boundingBox.height().toFloat()
        if (leftEyeCenter == null || rightEyeCenter == null || leftBrowCenter == null || rightBrowCenter == null || faceHeight <= 0f) {
            return null
        }

        val leftGap = abs(leftEyeCenter.y - leftBrowCenter.y)
        val rightGap = abs(rightEyeCenter.y - rightBrowCenter.y)
        return ((leftGap + rightGap) / 2f) / faceHeight
    }

    fun isSquinting(face: Face, threshold: Float = eyeOpenThreshold): Boolean {
        return areBothEyesBelowThreshold(
            leftEyeOpenProbability = face.leftEyeOpenProbability,
            rightEyeOpenProbability = face.rightEyeOpenProbability,
            threshold = threshold,
        ) && !isSmiling(face)
    }

    fun areBothEyesBelowThreshold(
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?,
        threshold: Float
    ): Boolean {
        val leftOpen = leftEyeOpenProbability ?: return false
        val rightOpen = rightEyeOpenProbability ?: return false
        return leftOpen < threshold && rightOpen < threshold
    }

    fun areBothEyesAboveThreshold(
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?,
        threshold: Float
    ): Boolean {
        val leftOpen = leftEyeOpenProbability ?: return false
        val rightOpen = rightEyeOpenProbability ?: return false
        return leftOpen > threshold && rightOpen > threshold
    }

    /**
     * ML Kit does not provide a separate teeth signal, so a reliable smile is inferred
     * from its smile classifier or from visibly raised mouth corners.
     */
    fun isSmiling(face: Face): Boolean {
        val smileProbability = face.smilingProbability ?: 0f
        if (smileProbability >= SMILE_PROBABILITY_THRESHOLD) return true

        val cornerLiftRatio = computeMouthCornerLiftRatio(face) ?: 0f
        if (cornerLiftRatio >= MOUTH_CORNER_LIFT_RATIO_THRESHOLD) return true

        val mouthOpenRatio = computeMouthOpenRatio(face) ?: 0f
        // ML Kit has no direct teeth contour. A clearly open, wide mouth is the most
        // reliable geometry-only fallback for a broad, teeth-showing smile.
        if (
            mouthOpenRatio >= OPEN_SMILE_MOUTH_RATIO_THRESHOLD &&
            (computeMouthWidthRatio(face) ?: 0f) >= OPEN_SMILE_MOUTH_WIDTH_RATIO_THRESHOLD
        ) {
            return true
        }
        return smileProbability >= OPEN_SMILE_PROBABILITY_THRESHOLD &&
            mouthOpenRatio >= MODERATE_OPEN_SMILE_MOUTH_RATIO_THRESHOLD
    }

    private fun computeMouthCornerLiftRatio(face: Face): Float? {
        val upperLipPoints = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: return null
        val faceHeight = face.boundingBox.height().toFloat()
        if (upperLipPoints.size < 3 || faceHeight <= 0f) return null

        val leftCorner = upperLipPoints.minByOrNull { it.x } ?: return null
        val rightCorner = upperLipPoints.maxByOrNull { it.x } ?: return null
        val mouthCenterY = upperLipPoints.map { it.y }.average().toFloat()
        val cornerY = (leftCorner.y + rightCorner.y) / 2f

        // Android image coordinates grow downward, so a positive result means raised corners.
        return (mouthCenterY - cornerY) / faceHeight
    }

    private fun computeMouthOpenRatio(face: Face): Float? {
        val upperInnerLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
        val lowerInnerLip = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return null
        val faceHeight = face.boundingBox.height().toFloat()
        if (upperInnerLip.isEmpty() || lowerInnerLip.isEmpty() || faceHeight <= 0f) return null

        val upperY = upperInnerLip.map { it.y }.average().toFloat()
        val lowerY = lowerInnerLip.map { it.y }.average().toFloat()
        return abs(lowerY - upperY) / faceHeight
    }

    private fun computeMouthWidthRatio(face: Face): Float? {
        val upperLipPoints = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: return null
        val faceWidth = face.boundingBox.width().toFloat()
        if (upperLipPoints.size < 2 || faceWidth <= 0f) return null

        val minX = upperLipPoints.minOf { it.x }
        val maxX = upperLipPoints.maxOf { it.x }
        return (maxX - minX) / faceWidth
    }

    private fun contourCenter(face: Face, contourType: Int): PointF? {
        val points = face.getContour(contourType)?.points ?: return null
        if (points.isEmpty()) return null
        var sumX = 0f
        var sumY = 0f
        points.forEach { point ->
            sumX += point.x
            sumY += point.y
        }
        return PointF(sumX / points.size, sumY / points.size)
    }

    fun computePostureRatio(pose: Pose): Double? {
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        if (leftEar == null || rightEar == null || leftShoulder == null || rightShoulder == null) return null
        if (
            leftEar.inFrameLikelihood < minPoseLikelihood ||
            rightEar.inFrameLikelihood < minPoseLikelihood ||
            leftShoulder.inFrameLikelihood < minPoseLikelihood ||
            rightShoulder.inFrameLikelihood < minPoseLikelihood
        ) return null

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

        return (dy / shoulderWidth).toDouble()
    }

    fun detectWarnings(
        face: Face?,
        pose: Pose?,
        imageWidth: Int,
        imageHeight: Int
    ): Set<WarningState> {
        val warnings = mutableSetOf<WarningState>()
        val hasUsableFace = face?.hasUsableFaceData() == true

        if (hasUsableFace) {
            val detectedFace = face ?: return warnings
            // 1. 偵測距離 (瞳距)
            if (enableTooCloseWarning) {
                val normalizedDist = computeNormalizedIrisDistance(detectedFace, imageWidth)
                if (normalizedDist != null && normalizedDist > irisDistanceThreshold) {
                    warnings.add(WarningState.TOO_CLOSE)
                }
            }

            // 2. 偵測瞇眼 (使用 ML Kit 分類結果)
            if (enableSquintWarning && isSquinting(detectedFace)) {
                warnings.add(WarningState.SQUINTING)
            }
        }

        pose?.let {
            // 3. 偵測駝背 (檢查耳朵相對於肩膀的前傾角度)
            if (enableSlouchWarning && hasUsableFace) {
                val ratio = computePostureRatio(it)
                if (ratio != null && ratio < slouchingPostureRatioThreshold) {
                    warnings.add(WarningState.SLOUCHING)
                }
            }
        }

        return warnings
    }

    private fun Face.hasUsableFaceData(): Boolean {
        val leftEye = getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightEye = getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        return leftEye != null &&
            rightEye != null &&
            leftEyeOpenProbability != null &&
            rightEyeOpenProbability != null
    }

    private companion object {
        const val SMILE_PROBABILITY_THRESHOLD = 0.45f
        const val MOUTH_CORNER_LIFT_RATIO_THRESHOLD = 0.008f
        const val OPEN_SMILE_PROBABILITY_THRESHOLD = 0.30f
        const val OPEN_SMILE_MOUTH_RATIO_THRESHOLD = 0.025f
        const val OPEN_SMILE_MOUTH_WIDTH_RATIO_THRESHOLD = 0.42f
        const val MODERATE_OPEN_SMILE_MOUTH_RATIO_THRESHOLD = 0.020f
    }
}
