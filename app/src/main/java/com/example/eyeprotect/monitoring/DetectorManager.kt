package com.example.eyeprotect.monitoring

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.eyeprotect.PostureAndEyeDetector
import com.example.eyeprotect.WarningState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns camera + sensors, and translates ML Kit results into the existing rule-based warnings.
 *
 * This is intentionally extracted from [com.example.eyeprotect.EyeHealthAccessibilityService] so
 * it can be driven by a ForegroundService (or other owners) without changing the rule logic.
 */
class DetectorManager(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val poseDetector: PoseDetector,
    private val ruleDetector: PostureAndEyeDetector = PostureAndEyeDetector()
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var gravitySensor: Sensor? = null

    private var lastDetectionTimestamp = 0L
    private var lastSensorPublishTimestamp = 0L
    private var lastFaceSeenTimestamp = 0L
    private var lastGyroMagnitude = 0.0

    private var lastPitchDegrees = Double.NaN
    private var lastRollDegrees = Double.NaN
    private var lastTiltDegrees = Double.NaN
    private var lastTiltFromHorizontalDegrees = Double.NaN
    private var lyingCandidateStartTimestamp = 0L
    private var isLyingActive = false
    private var squintWarningStreak = 0
    private var slouchWarningStreak = 0

    private var isRunning = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val wx = event.values.getOrNull(0)?.toDouble() ?: 0.0
                    val wy = event.values.getOrNull(1)?.toDouble() ?: 0.0
                    val wz = event.values.getOrNull(2)?.toDouble() ?: 0.0
                    lastGyroMagnitude = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
                }
                Sensor.TYPE_ROTATION_VECTOR -> updateOrientationFromRotationVector(event.values)
                Sensor.TYPE_GRAVITY -> updateTiltFromGravity(event.values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun setThresholds(
        irisDistance: Float?,
        eyeOpenThreshold: Float?,
        slouchRatioThreshold: Double?
    ) {
        irisDistance?.let {
            ruleDetector.irisDistanceThreshold = it.coerceIn(0.03f, 0.45f)
            ruleDetector.enableTooCloseWarning = true
        }
        eyeOpenThreshold?.let {
            ruleDetector.eyeOpenThreshold = it.coerceIn(0.10f, 0.90f)
            ruleDetector.enableSquintWarning = true
        }
        slouchRatioThreshold?.let {
            ruleDetector.slouchingPostureRatioThreshold = it.coerceIn(0.10, 2.50)
            ruleDetector.enableSlouchWarning = true
        }
    }

    fun start(
        onMetrics: (MonitoringMetrics) -> Unit,
        onWarnings: (Set<WarningState>) -> Unit = {}
    ) {
        if (isRunning) return
        isRunning = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startSensors(onMetrics)
        startCamera(onMetrics, onWarnings)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopCamera()
        stopSensors()
        cameraExecutor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun startSensors(onMetrics: (MonitoringMetrics) -> Unit) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        rotationVectorSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        gravitySensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }

        // Publish periodically even before camera produces frames.
        publishSensorMetricsIfNeeded(onMetrics)
    }

    private fun stopSensors() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (_: Exception) {
        }
    }

    private fun updateOrientationFromRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val pitchRad = orientation[1].toDouble()
        val rollRad = orientation[2].toDouble()
        lastPitchDegrees = kotlin.math.abs(Math.toDegrees(pitchRad))
        lastRollDegrees = kotlin.math.abs(Math.toDegrees(rollRad))
        updateLyingState()
    }

    private fun updateTiltFromGravity(values: FloatArray) {
        val gx = values.getOrNull(0)?.toDouble() ?: return
        val gy = values.getOrNull(1)?.toDouble() ?: return
        val gz = values.getOrNull(2)?.toDouble() ?: return
        val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        if (g <= 0.0) return

        val cos = (gz / g).coerceIn(-1.0, 1.0)
        lastTiltDegrees = Math.toDegrees(kotlin.math.acos(cos))
        lastTiltFromHorizontalDegrees = kotlin.math.min(lastTiltDegrees, 180.0 - lastTiltDegrees)
        updateLyingState()
    }

    private fun updateLyingState() {
        val now = SystemClock.uptimeMillis()
        val isCandidate = if (!lastTiltDegrees.isNaN()) {
            !lastTiltFromHorizontalDegrees.isNaN() && lastTiltFromHorizontalDegrees <= LYING_TILT_FROM_HORIZONTAL_DEG
        } else if (!lastPitchDegrees.isNaN() && !lastRollDegrees.isNaN()) {
            val nearHorizontal = lastPitchDegrees >= LYING_PITCH_DEG
            val sideWhileHorizontal = lastRollDegrees >= LYING_ROLL_DEG && lastPitchDegrees >= LYING_SIDE_MIN_PITCH_DEG
            (nearHorizontal || sideWhileHorizontal)
        } else {
            false
        }

        val handHeldLike = lastGyroMagnitude in LYING_MIN_GYRO_MAG..LYING_MAX_GYRO_MAG
        val faceRecentlySeen = now - lastFaceSeenTimestamp <= LYING_FACE_RECENCY_MS

        if (isCandidate && handHeldLike && faceRecentlySeen) {
            if (lyingCandidateStartTimestamp == 0L) lyingCandidateStartTimestamp = now
            if (!isLyingActive && now - lyingCandidateStartTimestamp >= LYING_HOLD_MS) {
                isLyingActive = true
            }
        } else {
            lyingCandidateStartTimestamp = 0L
            isLyingActive = false
        }
    }

    private fun publishSensorMetricsIfNeeded(onMetrics: (MonitoringMetrics) -> Unit) {
        if (!isRunning) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSensorPublishTimestamp < SENSOR_METRICS_INTERVAL_MS) return
        lastSensorPublishTimestamp = now

        val lyingBit = if (isLyingActive) 8 else 0
        onMetrics(
            MonitoringMetrics(
                ts = now,
                warningsMask = lyingBit,
                isLyingActive = isLyingActive,
                lastFaceDetectedTime = lastFaceSeenTimestamp,
                isCameraFrame = false,
                pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
                rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
                tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null
            )
        )
    }

    private fun startCamera(
        onMetrics: (MonitoringMetrics) -> Unit,
        onWarnings: (Set<WarningState>) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider: ProcessCameraProvider = cameraProviderFuture.get()
                cameraProvider = provider
                val analyzerImpl = FrameAnalyzer(onMetrics, onWarnings)
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzerImpl) }
                imageAnalyzer = analyzer
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        try {
            imageAnalyzer?.clearAnalyzer()
        } catch (_: Exception) {
        }
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        imageAnalyzer = null
        cameraProvider = null
    }

    @ExperimentalGetImage
    private fun analyzeImage(
        imageProxy: ImageProxy,
        onMetrics: (MonitoringMetrics) -> Unit,
        onWarnings: (Set<WarningState>) -> Unit
    ) {
        if (!isRunning) {
            imageProxy.close()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastDetectionTimestamp < DETECTION_INTERVAL_MS) {
            publishSensorMetricsIfNeeded(onMetrics)
            imageProxy.close()
            return
        }
        lastDetectionTimestamp = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width

        val faceTask = faceDetector.process(image)
        val poseTask = poseDetector.process(image)

        Tasks.whenAllComplete(faceTask, poseTask).addOnCompleteListener {
            if (!isRunning) {
                imageProxy.close()
                return@addOnCompleteListener
            }
            val face = if (faceTask.isSuccessful) faceTask.result?.firstOrNull() else null
            val pose = if (poseTask.isSuccessful) poseTask.result else null
            if (face != null) lastFaceSeenTimestamp = SystemClock.uptimeMillis()

            val warnings = ruleDetector.detectWarnings(
                face = face,
                pose = pose,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

            val stableWarnings = stabilizeCameraWarnings(warnings)
            val warningsWithLying = if (isLyingActive) stableWarnings + WarningState.LYING else stableWarnings
            onWarnings(warningsWithLying)
            val irisNorm = face?.let { ruleDetector.computeNormalizedIrisDistance(it, imageWidth) }
            val eyeOpenMin = face?.let { ruleDetector.computeEyeOpenMin(it) }
            val slouchScore = pose?.let { ruleDetector.computePostureRatio(it) }?.toFloat()

            val warningsMask =
                (if (warningsWithLying.contains(WarningState.TOO_CLOSE)) 1 else 0) or
                    (if (warningsWithLying.contains(WarningState.SQUINTING)) 2 else 0) or
                    (if (warningsWithLying.contains(WarningState.SLOUCHING)) 4 else 0) or
                    (if (warningsWithLying.contains(WarningState.LYING)) 8 else 0)

            onMetrics(
                MonitoringMetrics(
                    ts = SystemClock.uptimeMillis(),
                    warningsMask = warningsMask,
                    isLyingActive = isLyingActive,
                    lastFaceDetectedTime = lastFaceSeenTimestamp,
                    isCameraFrame = true,
                    faceDetected = face != null,
                    poseDetected = pose != null,
                    faceError = !faceTask.isSuccessful,
                    poseError = !poseTask.isSuccessful,
                    irisNorm = irisNorm,
                    eyeOpenMin = eyeOpenMin,
                    slouchScore = slouchScore,
                    pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
                    rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
                    tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null
                )
            )

            imageProxy.close()
        }
    }

    private fun stabilizeCameraWarnings(warnings: Set<WarningState>): Set<WarningState> {
        // Distance warning should be immediate; squint/slouch are noisier ML classifications.
        squintWarningStreak = if (warnings.contains(WarningState.SQUINTING)) squintWarningStreak + 1 else 0
        slouchWarningStreak = if (warnings.contains(WarningState.SLOUCHING)) slouchWarningStreak + 1 else 0

        return buildSet {
            if (warnings.contains(WarningState.TOO_CLOSE)) add(WarningState.TOO_CLOSE)
            if (squintWarningStreak >= CAMERA_WARNING_CONFIRM_FRAMES) add(WarningState.SQUINTING)
            if (slouchWarningStreak >= CAMERA_WARNING_CONFIRM_FRAMES) add(WarningState.SLOUCHING)
        }
    }

    private inner class FrameAnalyzer(
        private val onMetrics: (MonitoringMetrics) -> Unit,
        private val onWarnings: (Set<WarningState>) -> Unit
    ) : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            analyzeImage(imageProxy, onMetrics, onWarnings)
        }
    }

    companion object {
        private const val TAG = "DetectorManager"
        private const val DETECTION_INTERVAL_MS = 750L

        private const val SENSOR_METRICS_INTERVAL_MS = 500L
        private const val LYING_HOLD_MS = 4000L
        private const val CAMERA_WARNING_CONFIRM_FRAMES = 2
        private const val LYING_PITCH_DEG = 65.0
        private const val LYING_ROLL_DEG = 65.0
        private const val LYING_SIDE_MIN_PITCH_DEG = 35.0
        private const val LYING_TILT_FROM_HORIZONTAL_DEG = 25.0
        private const val LYING_MIN_GYRO_MAG = 0.03
        private const val LYING_MAX_GYRO_MAG = 3.0
        private const val LYING_FACE_RECENCY_MS = 5000L
    }
}
