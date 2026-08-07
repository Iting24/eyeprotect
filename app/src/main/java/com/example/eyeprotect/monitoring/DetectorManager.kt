package com.example.eyeprotect.monitoring

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.eyeprotect.FaceIdentityMatcher
import com.example.eyeprotect.FaceProfile
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
    private val activeProfileProvider: () -> FaceProfile? = { null },
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
    private var orientationListener: OrientationEventListener? = null

    private var lastDetectionTimestamp = 0L
    private var lastSensorPublishTimestamp = 0L
    private var lastFaceSeenTimestamp = 0L
    private var lastOwnerMatchTimestamp = 0L
    private var lastGyroMagnitude = 0.0
    private var currentTargetRotation = fallbackRotationFromConfiguration()

    private var lastPitchDegrees = Double.NaN
    private var lastRollDegrees = Double.NaN
    private var lastTiltDegrees = Double.NaN
    private var lastTiltFromHorizontalDegrees = Double.NaN
    private var lyingCandidateStartTimestamp = 0L
    private var isLyingActive = false
    private var squintWarningStreak = 0
    private var slouchWarningStreak = 0
    private var lastDetectedWarnings: Set<WarningState> = emptySet()
    private var lastPublishedWarnings: Set<WarningState> = emptySet()
    private var isIdentityPaused = false
    private var lastAppliedProfileFingerprint: String? = null

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
        ruleDetector.enableTooCloseWarning = irisDistance != null
        irisDistance?.let {
            ruleDetector.irisDistanceThreshold = it.coerceIn(0.03f, 0.45f)
        }
        ruleDetector.enableSquintWarning = eyeOpenThreshold != null
        eyeOpenThreshold?.let {
            ruleDetector.eyeOpenThreshold = it.coerceIn(0.10f, 0.90f)
        }
        ruleDetector.enableSlouchWarning = slouchRatioThreshold != null
        slouchRatioThreshold?.let {
            ruleDetector.slouchingPostureRatioThreshold = it.coerceIn(0.10, 2.50)
        }
    }

    fun start(
        onMetrics: (MonitoringMetrics) -> Unit,
        onWarnings: (Set<WarningState>) -> Unit = {},
        onWarningActivated: (WarningState) -> Unit = {},
        onWarningDeactivated: (WarningState) -> Unit = {}
    ) {
        if (isRunning) return
        isRunning = true
        lastDetectedWarnings = emptySet()
        lastPublishedWarnings = emptySet()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startOrientationTracking()
        startSensors(onMetrics)
        startCamera(onMetrics, onWarnings, onWarningActivated, onWarningDeactivated)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopCamera()
        stopSensors()
        stopOrientationTracking()
        cameraExecutor.shutdown()
        lastDetectedWarnings = emptySet()
        lastPublishedWarnings = emptySet()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun startSensors(onMetrics: (MonitoringMetrics) -> Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (manager == null) {
            Log.w(TAG, "Sensor service unavailable; continuing without orientation metrics")
            publishSensorMetricsIfNeeded(onMetrics)
            return
        }
        sensorManager = manager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        try {
            rotationVectorSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
            gravitySensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Sensor registration failed; continuing without orientation metrics", exception)
        }

        // Publish periodically even before camera produces frames.
        publishSensorMetricsIfNeeded(onMetrics)
    }

    private fun startOrientationTracking() {
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val updatedRotation = orientationToSurfaceRotation(orientation)
                if (currentTargetRotation == updatedRotation) return
                currentTargetRotation = updatedRotation
                imageAnalyzer?.targetRotation = updatedRotation
            }
        }.also { listener ->
            if (listener.canDetectOrientation()) {
                listener.enable()
            } else {
                currentTargetRotation = currentDisplayRotation()
            }
        }
    }

    private fun stopOrientationTracking() {
        orientationListener?.disable()
        orientationListener = null
    }

    private fun stopSensors() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (_: Exception) {
        }
    }

    private fun updateOrientationFromRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        val adjustedRotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                adjustedRotationMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                adjustedRotationMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                adjustedRotationMatrix
            )
            else -> rotationMatrix.copyInto(adjustedRotationMatrix)
        }
        SensorManager.getOrientation(adjustedRotationMatrix, orientation)

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
                warningsMask = if (isIdentityPaused) 0 else lyingBit,
                detectedWarningsMask = if (isIdentityPaused) 0 else lyingBit,
                isLyingActive = isLyingActive && !isIdentityPaused,
                lastFaceDetectedTime = if (isIdentityPaused) 0L else lastFaceSeenTimestamp,
                isCameraFrame = false,
                faceMatchedActiveProfile = !isIdentityPaused,
                identityPaused = isIdentityPaused,
                pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
                rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
                tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null
            )
        )
    }

    private fun startCamera(
        onMetrics: (MonitoringMetrics) -> Unit,
        onWarnings: (Set<WarningState>) -> Unit,
        onWarningActivated: (WarningState) -> Unit,
        onWarningDeactivated: (WarningState) -> Unit
    ) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val provider: ProcessCameraProvider = cameraProviderFuture.get()
                    cameraProvider = provider
                    val analyzerImpl = FrameAnalyzer(onMetrics, onWarnings, onWarningActivated, onWarningDeactivated)
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(currentTargetRotation)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, analyzerImpl) }
                    imageAnalyzer = analyzer
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(this, cameraSelector, analyzer)
                } catch (e: Exception) {
                    Log.e(TAG, "CameraX failed", e)
                    publishCameraError(onMetrics)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Unable to request CameraX provider", exception)
            publishCameraError(onMetrics)
        }
    }

    private fun publishCameraError(onMetrics: (MonitoringMetrics) -> Unit) {
        onMetrics(
            MonitoringMetrics(
                ts = SystemClock.uptimeMillis(),
                warningsMask = if (isLyingActive && !isIdentityPaused) 8 else 0,
                detectedWarningsMask = if (isLyingActive && !isIdentityPaused) 8 else 0,
                isLyingActive = isLyingActive && !isIdentityPaused,
                lastFaceDetectedTime = if (isIdentityPaused) 0L else lastFaceSeenTimestamp,
                isCameraFrame = true,
                faceDetected = false,
                faceMatchedActiveProfile = !isIdentityPaused,
                identityPaused = isIdentityPaused,
                poseDetected = false,
                faceError = true,
                poseError = true,
                pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
                rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
                tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null
            )
        )
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
        onWarnings: (Set<WarningState>) -> Unit,
        onWarningActivated: (WarningState) -> Unit,
        onWarningDeactivated: (WarningState) -> Unit
    ) {
        if (!isRunning) {
            imageProxy.close()
            return
        }
        val currentRotation = currentTargetRotation
        if (imageAnalyzer?.targetRotation != currentRotation) {
            imageAnalyzer?.targetRotation = currentRotation
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
            publishCameraError(onMetrics)
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width

        val faceTask = faceDetector.process(image)
        val poseTask = poseDetector.process(image)

        Tasks.whenAllComplete(faceTask, poseTask).addOnCompleteListener {
            try {
                if (!isRunning) {
                    return@addOnCompleteListener
                }
                syncActiveProfile()
                val activeProfile = activeProfileProvider()
                val face = if (faceTask.isSuccessful) faceTask.result?.firstOrNull() else null
                val pose = if (poseTask.isSuccessful) poseTask.result else null
                val analysisTimestamp = SystemClock.uptimeMillis()
                val requiresIdentityMatch = activeProfile?.signature != null
                val faceMatchesActiveProfile = when {
                    !requiresIdentityMatch -> face != null
                    face == null -> false
                    else -> FaceIdentityMatcher.isMatch(face, activeProfile.signature!!)
                }

                if (!requiresIdentityMatch) {
                    isIdentityPaused = false
                    if (face != null) {
                        lastFaceSeenTimestamp = analysisTimestamp
                        lastOwnerMatchTimestamp = analysisTimestamp
                    }
                } else if (faceMatchesActiveProfile) {
                    isIdentityPaused = false
                    lastFaceSeenTimestamp = analysisTimestamp
                    lastOwnerMatchTimestamp = analysisTimestamp
                } else if (face != null) {
                    // A different face is in front of the camera: pause immediately.
                    isIdentityPaused = true
                    lastFaceSeenTimestamp = 0L
                } else if (analysisTimestamp - lastOwnerMatchTimestamp > OWNER_MATCH_GRACE_MS) {
                    // If we can no longer verify the owner for a short period, stop reminders.
                    isIdentityPaused = true
                    lastFaceSeenTimestamp = 0L
                }

                val warnings = if (isIdentityPaused) {
                    emptySet()
                } else {
                    ruleDetector.detectWarnings(
                        face = face,
                        pose = pose,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                }

                val detectedWarningsWithLying =
                    if (isLyingActive && !isIdentityPaused) warnings + WarningState.LYING else warnings
                val activatedDetectedWarnings = detectedWarningsWithLying - lastDetectedWarnings
                val deactivatedDetectedWarnings = lastDetectedWarnings - detectedWarningsWithLying
                activatedDetectedWarnings.forEach(onWarningActivated)
                deactivatedDetectedWarnings.forEach(onWarningDeactivated)
                lastDetectedWarnings = detectedWarningsWithLying

                val stableWarnings = stabilizeCameraWarnings(warnings)
                val warningsWithLying =
                    if (isLyingActive && !isIdentityPaused) stableWarnings + WarningState.LYING else stableWarnings
                lastPublishedWarnings = warningsWithLying
                onWarnings(warningsWithLying)
                val irisNorm = face?.let { ruleDetector.computeNormalizedIrisDistance(it, imageWidth) }
                val eyeOpenMin = face?.let { ruleDetector.computeEyeOpenMin(it) }
                val slouchScore = pose?.let { ruleDetector.computePostureRatio(it) }?.toFloat()

                val warningsMask =
                    (if (warningsWithLying.contains(WarningState.TOO_CLOSE)) 1 else 0) or
                        (if (warningsWithLying.contains(WarningState.SQUINTING)) 2 else 0) or
                        (if (warningsWithLying.contains(WarningState.SLOUCHING)) 4 else 0) or
                        (if (warningsWithLying.contains(WarningState.LYING)) 8 else 0)
                val detectedWarningsMask =
                    (if (detectedWarningsWithLying.contains(WarningState.TOO_CLOSE)) 1 else 0) or
                        (if (detectedWarningsWithLying.contains(WarningState.SQUINTING)) 2 else 0) or
                        (if (detectedWarningsWithLying.contains(WarningState.SLOUCHING)) 4 else 0) or
                        (if (detectedWarningsWithLying.contains(WarningState.LYING)) 8 else 0)

                onMetrics(
                    MonitoringMetrics(
                        ts = SystemClock.uptimeMillis(),
                        warningsMask = warningsMask,
                        detectedWarningsMask = detectedWarningsMask,
                        isLyingActive = isLyingActive && !isIdentityPaused,
                        lastFaceDetectedTime = if (isIdentityPaused) 0L else lastFaceSeenTimestamp,
                        isCameraFrame = true,
                        faceDetected = face != null,
                        faceMatchedActiveProfile = faceMatchesActiveProfile,
                        identityPaused = isIdentityPaused,
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
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Camera analysis failed", exception)
                publishCameraError(onMetrics)
            } finally {
                imageProxy.close()
            }
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
        private val onWarnings: (Set<WarningState>) -> Unit,
        private val onWarningActivated: (WarningState) -> Unit,
        private val onWarningDeactivated: (WarningState) -> Unit
    ) : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            analyzeImage(imageProxy, onMetrics, onWarnings, onWarningActivated, onWarningDeactivated)
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
        private const val OWNER_MATCH_GRACE_MS = 1500L
    }

    private fun syncActiveProfile() {
        val profile = activeProfileProvider()
        val fingerprint = buildString {
            append(profile?.id ?: "none")
            append(':')
            append(profile?.calibratedAtEpochMs ?: 0L)
        }
        if (fingerprint == lastAppliedProfileFingerprint) return
        lastAppliedProfileFingerprint = fingerprint

        if (profile?.hasValidCalibration == true) {
            setThresholds(
                irisDistance = profile.irisThreshold,
                eyeOpenThreshold = profile.eyeOpenThreshold,
                slouchRatioThreshold = profile.slouchThreshold?.toDouble()
            )
        } else {
            setThresholds(
                irisDistance = null,
                eyeOpenThreshold = null,
                slouchRatioThreshold = null
            )
            isIdentityPaused = false
        }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    private fun currentDisplayRotation(): Int {
        val contextDisplayRotation = context.display?.rotation
        if (contextDisplayRotation != null) return contextDisplayRotation

        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return windowManager?.defaultDisplay?.rotation ?: fallbackRotationFromConfiguration()
    }

    private fun fallbackRotationFromConfiguration(): Int {
        return when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> Surface.ROTATION_90
            Configuration.ORIENTATION_PORTRAIT -> Surface.ROTATION_0
            else -> Surface.ROTATION_0
        }
    }

    private fun orientationToSurfaceRotation(orientation: Int): Int {
        return when {
            orientation in 45..134 -> Surface.ROTATION_270
            orientation in 135..224 -> Surface.ROTATION_180
            orientation in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}
