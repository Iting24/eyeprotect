package com.example.eyeprotect

import android.graphics.PointF
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
        val length = minOf(candidate.landmarkVector.size, signature.landmarkVector.size)
        if (length < MIN_VECTOR_SIZE) return false

        var sumSquares = 0.0
        for (index in 0 until length) {
            val delta = candidate.landmarkVector[index] - signature.landmarkVector[index]
            sumSquares += delta * delta
        }
        val vectorDistance = sqrt(sumSquares / length)
        val widthDelta = kotlin.math.abs(candidate.boxWidthToEyeDistance - signature.boxWidthToEyeDistance)
        val heightDelta = kotlin.math.abs(candidate.boxHeightToEyeDistance - signature.boxHeightToEyeDistance)

        return vectorDistance <= VECTOR_DISTANCE_THRESHOLD &&
            widthDelta <= BOX_RATIO_THRESHOLD &&
            heightDelta <= BOX_RATIO_THRESHOLD
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

    private val NORMALIZED_LANDMARKS = listOf(
        FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT,
        FaceLandmark.MOUTH_RIGHT,
        FaceLandmark.LEFT_CHEEK,
        FaceLandmark.RIGHT_CHEEK,
        FaceLandmark.LEFT_EAR,
        FaceLandmark.RIGHT_EAR
    )

    private const val MIN_EYE_DISTANCE_PX = 24f
    private const val MIN_VECTOR_SIZE = 10
    private const val VECTOR_DISTANCE_THRESHOLD = 0.14
    private const val BOX_RATIO_THRESHOLD = 0.22f
}
