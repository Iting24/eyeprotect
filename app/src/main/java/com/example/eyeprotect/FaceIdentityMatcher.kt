package com.example.eyeprotect

import android.graphics.PointF
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class FaceSignature(
    val boxWidthToEyeDistance: Float,
    val boxHeightToEyeDistance: Float,
    val landmarkVector: List<Float>
)

object FaceIdentityMatcher {

    fun createSignature(face: Face): FaceSignature? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val eyeDistance = distance(leftEye, rightEye)
        if (eyeDistance <= MIN_EYE_DISTANCE_PX) return null

        val eyeMid = PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
        val rotation = atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble())

        val vector = buildList {
            addNormalizedPoint(this, leftEye, eyeMid, rotation, eyeDistance)
            addNormalizedPoint(this, rightEye, eyeMid, rotation, eyeDistance)
            NORMALIZED_LANDMARKS.forEach { landmarkType ->
                val point = face.getLandmark(landmarkType)?.position ?: return@forEach
                addNormalizedPoint(this, point, eyeMid, rotation, eyeDistance)
            }
            CONTOUR_SAMPLE_TYPES.forEach { (contourType, sampleCount) ->
                val contourPoints = face.getContour(contourType)?.points.orEmpty()
                addContourSamples(
                    destination = this,
                    contourPoints = contourPoints,
                    sampleCount = sampleCount,
                    origin = eyeMid,
                    rotation = rotation,
                    scale = eyeDistance
                )
            }
        }

        if (vector.size < MIN_VECTOR_SIZE) return null

        return FaceSignature(
            boxWidthToEyeDistance = face.boundingBox.width() / eyeDistance,
            boxHeightToEyeDistance = face.boundingBox.height() / eyeDistance,
            landmarkVector = vector
        )
    }

    fun average(signatures: List<FaceSignature>): FaceSignature? {
        val valid = signatures.filter { it.landmarkVector.isNotEmpty() }
        if (valid.isEmpty()) return null

        val minVectorSize = valid.minOf { it.landmarkVector.size }
        if (minVectorSize < MIN_VECTOR_SIZE) return null

        return FaceSignature(
            boxWidthToEyeDistance = valid.map { it.boxWidthToEyeDistance }.average().toFloat(),
            boxHeightToEyeDistance = valid.map { it.boxHeightToEyeDistance }.average().toFloat(),
            landmarkVector = List(minVectorSize) { index ->
                valid.map { it.landmarkVector[index] }.average().toFloat()
            }
        )
    }

    fun isMatch(face: Face, signature: FaceSignature): Boolean {
        val candidate = createSignature(face) ?: return false
        return isMatch(candidate, signature)
    }

    fun isMatch(candidate: FaceSignature, reference: FaceSignature): Boolean {
        return matches(
            candidate = candidate,
            reference = reference,
            vectorDistanceThreshold = PROFILE_VECTOR_DISTANCE_THRESHOLD,
            boxRatioThreshold = PROFILE_BOX_RATIO_THRESHOLD
        )
    }

    fun isSessionMatch(candidate: FaceSignature, sessionReference: FaceSignature): Boolean {
        return matches(
            candidate = candidate,
            reference = sessionReference,
            vectorDistanceThreshold = SESSION_VECTOR_DISTANCE_THRESHOLD,
            boxRatioThreshold = SESSION_BOX_RATIO_THRESHOLD
        )
    }

    private fun matches(
        candidate: FaceSignature,
        reference: FaceSignature,
        vectorDistanceThreshold: Double,
        boxRatioThreshold: Float
    ): Boolean {
        val length = minOf(candidate.landmarkVector.size, reference.landmarkVector.size)
        if (length < MIN_VECTOR_SIZE) return false

        var sumSquares = 0.0
        for (index in 0 until length) {
            val delta = candidate.landmarkVector[index] - reference.landmarkVector[index]
            sumSquares += delta * delta
        }
        val vectorDistance = sqrt(sumSquares / length)
        val widthDelta = kotlin.math.abs(candidate.boxWidthToEyeDistance - reference.boxWidthToEyeDistance)
        val heightDelta = kotlin.math.abs(candidate.boxHeightToEyeDistance - reference.boxHeightToEyeDistance)

        return vectorDistance <= vectorDistanceThreshold &&
            widthDelta <= boxRatioThreshold &&
            heightDelta <= boxRatioThreshold
    }

    private fun addNormalizedPoint(
        destination: MutableList<Float>,
        point: PointF,
        origin: PointF,
        rotation: Double,
        scale: Float
    ) {
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        val cos = cos(-rotation)
        val sin = sin(-rotation)
        val normalizedX = ((dx * cos) - (dy * sin)) / scale
        val normalizedY = ((dx * sin) + (dy * cos)) / scale
        destination += normalizedX.toFloat()
        destination += normalizedY.toFloat()
    }

    private fun distance(first: PointF, second: PointF): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun addContourSamples(
        destination: MutableList<Float>,
        contourPoints: List<PointF>,
        sampleCount: Int,
        origin: PointF,
        rotation: Double,
        scale: Float
    ) {
        if (contourPoints.isEmpty() || sampleCount <= 0) return
        repeat(sampleCount) { index ->
            val pointIndex = if (sampleCount == 1) {
                contourPoints.lastIndex / 2
            } else {
                ((contourPoints.lastIndex.toFloat() * index) / (sampleCount - 1)).toInt()
            }.coerceIn(0, contourPoints.lastIndex)
            addNormalizedPoint(
                destination = destination,
                point = contourPoints[pointIndex],
                origin = origin,
                rotation = rotation,
                scale = scale
            )
        }
    }

    private val NORMALIZED_LANDMARKS = listOf(
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
        FaceLandmark.LEFT_EAR,
        FaceLandmark.RIGHT_EAR
    )

    private val CONTOUR_SAMPLE_TYPES = listOf(
        FaceContour.LEFT_EYE to 4,
        FaceContour.RIGHT_EYE to 4,
        FaceContour.LEFT_EYEBROW_TOP to 3,
        FaceContour.RIGHT_EYEBROW_TOP to 3,
        FaceContour.NOSE_BRIDGE to 3,
        FaceContour.NOSE_BOTTOM to 3,
        FaceContour.UPPER_LIP_TOP to 3,
        FaceContour.LOWER_LIP_BOTTOM to 3,
        FaceContour.FACE to 5
    )

    private const val MIN_EYE_DISTANCE_PX = 24f
    private const val MIN_VECTOR_SIZE = 10
    private const val PROFILE_VECTOR_DISTANCE_THRESHOLD = 0.12
    private const val PROFILE_BOX_RATIO_THRESHOLD = 0.18f
    private const val SESSION_VECTOR_DISTANCE_THRESHOLD = 0.08
    private const val SESSION_BOX_RATIO_THRESHOLD = 0.12f
}
