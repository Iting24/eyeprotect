package com.example.eyeprotect

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.PI

// In a real application, you would import these from the MediaPipe library.
// e.g. import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
// e.g. import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
// e.g. import com.google.mediapipe.tasks.vision.core.NormalizedLandmark
// The following are dummy classes for compilation and demonstration.
data class NormalizedLandmark(val x: Float, val y: Float, val z: Float)
data class FaceLandmarkerResult(val faceLandmarks: List<List<NormalizedLandmark>>)
data class PoseLandmarkerResult(val landmarks: List<List<NormalizedLandmark>>)

/**
 * Represents the possible warnings related to user posture and eye strain.
 */
enum class WarningState {
    TOO_CLOSE,
    SLOUCHING,
    SQUINTING
}

/**
 * A class to analyze face and pose landmarks to detect potential issues like
 * being too close to the screen, slouching, or squinting.
 *
 * This class uses landmark data from MediaPipe's FaceLandmarker and PoseLandmarker.
 */
class PostureAndEyeDetector {

    // Thresholds for various warnings. These can be adjusted.
    var irisDistanceThreshold = 0.12f
    var slouchingAngleThresholdDegrees = 20.0
    var earThreshold = 0.2f

    companion object {
        // Landmark indices from MediaPipe documentation.
        // https://developers.google.com/mediapipe/solutions/vision/face_landmarker
        private val LEFT_IRIS_INDICES = listOf(474, 475, 476, 477)
        private val RIGHT_IRIS_INDICES = listOf(469, 470, 471, 472)

        private val LEFT_EYE_INDICES = listOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EYE_INDICES = listOf(33, 160, 158, 133, 153, 144)

        // https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_EAR = 7
        private const val RIGHT_EAR = 8
    }

    /**
     * Analyzes landmark data to produce a set of warnings.
     *
     * @param faceResult Results from FaceLandmarker.
     * @param poseResult Results from PoseLandmarker.
     * @return A set of [WarningState] indicating any detected issues.
     */
    fun getWarningState(
        faceResult: FaceLandmarkerResult?,
        poseResult: PoseLandmarkerResult?
    ): Set<WarningState> {
        val warnings = mutableSetOf<WarningState>()

        faceResult?.faceLandmarks?.firstOrNull()?.let { faceLandmarks ->
            if (isTooClose(faceLandmarks)) {
                warnings.add(WarningState.TOO_CLOSE)
            }
            if (isSquinting(faceLandmarks)) {
                warnings.add(WarningState.SQUINTING)
            }
        }

        poseResult?.landmarks?.firstOrNull()?.let { poseLandmarks ->
            if (isSlouching(poseLandmarks)) {
                warnings.add(WarningState.SLOUCHING)
            }
        }

        return warnings
    }

    private fun isTooClose(landmarks: List<NormalizedLandmark>): Boolean {
        val leftIrisCenter = getAveragePosition(landmarks, LEFT_IRIS_INDICES)
        val rightIrisCenter = getAveragePosition(landmarks, RIGHT_IRIS_INDICES)

        val distance = distance(leftIrisCenter, rightIrisCenter)

        return distance > irisDistanceThreshold
    }

    private fun isSlouching(landmarks: List<NormalizedLandmark>): Boolean {
        val leftShoulder = landmarks[LEFT_SHOULDER]
        val rightShoulder = landmarks[RIGHT_SHOULDER]
        val leftEar = landmarks[LEFT_EAR]
        val rightEar = landmarks[RIGHT_EAR]

        val shoulderMidpoint = NormalizedLandmark(
            (leftShoulder.x + rightShoulder.x) / 2f,
            (leftShoulder.y + rightShoulder.y) / 2f,
            (leftShoulder.z + rightShoulder.z) / 2f
        )

        val earMidpoint = NormalizedLandmark(
            (leftEar.x + rightEar.x) / 2f,
            (leftEar.y + rightEar.y) / 2f,
            (leftEar.z + rightEar.z) / 2f
        )

        val dx = abs(earMidpoint.x - shoulderMidpoint.x)
        val dy = abs(earMidpoint.y - shoulderMidpoint.y)

        if (dy == 0f) return false

        val angle = atan(dx / dy) * (180.0 / PI)
        return angle > slouchingAngleThresholdDegrees
    }

    private fun isSquinting(landmarks: List<NormalizedLandmark>): Boolean {
        val leftEAR = calculateEAR(landmarks, LEFT_EYE_INDICES)
        val rightEAR = calculateEAR(landmarks, RIGHT_EYE_INDICES)
        val avgEAR = (leftEAR + rightEAR) / 2.0

        return avgEAR < earThreshold
    }

    private fun calculateEAR(landmarks: List<NormalizedLandmark>, eyeIndices: List<Int>): Float {
        // EAR = (||p2-p6|| + ||p3-p5||) / (2 * ||p1-p4||)
        val p1 = landmarks[eyeIndices[0]] // horizontal
        val p2 = landmarks[eyeIndices[1]] // vertical
        val p3 = landmarks[eyeIndices[2]] // vertical
        val p4 = landmarks[eyeIndices[3]] // horizontal
        val p5 = landmarks[eyeIndices[4]] // vertical
        val p6 = landmarks[eyeIndices[5]] // vertical

        val verticalDist = distance(p2, p6) + distance(p3, p5)
        val horizontalDist = distance(p1, p4)

        if (horizontalDist == 0f) {
            return 0f
        }

        return verticalDist / (2f * horizontalDist)
    }

    private fun getAveragePosition(landmarks: List<NormalizedLandmark>, indices: List<Int>): NormalizedLandmark {
        var x = 0f
        var y = 0f
        var z = 0f
        for (index in indices) {
            x += landmarks[index].x
            y += landmarks[index].y
            z += landmarks[index].z
        }
        val count = indices.size
        return NormalizedLandmark(x / count, y / count, z / count)
    }

    private fun distance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        // Not using Z for 2D distance calculation on the normalized image plane
        return sqrt(dx * dx + dy * dy)
    }
}
