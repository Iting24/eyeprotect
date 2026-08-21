package com.example.eyeprotect.monitoring

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
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
import com.example.eyeprotect.CalibrationPrefs
import com.example.eyeprotect.PostureAndEyeDetector
import com.example.eyeprotect.WarningState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.Pose
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
    private val thresholdsProvider: ((Int) -> CalibrationPrefs.Thresholds?)? = null,
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
    private var lastGyroMagnitude = 0.0
    private var currentTargetRotation = currentDisplayRotation()

    private var lastPitchDegrees = Double.NaN
    private var lastRollDegrees = Double.NaN
    private var lastTiltDegrees = Double.NaN
    private var lastTiltFromHorizontalDegrees = Double.NaN
    private var lyingCandidateStartTimestamp = 0L
    private var isLyingActive = false
    private var squintCandidateStartTimestamp = 0L
    private var smoothedFacePitchDegrees = Float.NaN
    private var baselineFacePitchDegrees = Float.NaN
    private var isLowHeadActive = false
    private var slouchWarningStreak = 0
    private var lastDetectedWarnings: Set<WarningState> = emptySet()
    private var lastPublishedWarnings: Set<WarningState> = emptySet()

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
        onWarnings: (Set<WarningState>) -> Unit = {},
        onWarningActivated: (WarningState) -> Unit = {},
        onWarningDeactivated: (WarningState) -> Unit = {}
    ) {
        if (isRunning) return
        isRunning = true
        lastDetectedWarnings = emptySet()
        lastPublishedWarnings = emptySet()
        squintCandidateStartTimestamp = 0L
        smoothedFacePitchDegrees = Float.NaN
        baselineFacePitchDegrees = Float.NaN
        isLowHeadActive = false
        slouchWarningStreak = 0
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        syncThresholdsForOrientation(context.resources.configuration.orientation)
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
        squintCandidateStartTimestamp = 0L
        smoothedFacePitchDegrees = Float.NaN
        baselineFacePitchDegrees = Float.NaN
        isLowHeadActive = false
        slouchWarningStreak = 0
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

    private fun stopSensors() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (_: Exception) {
        }
    }

    private fun startOrientationTracking() {
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val updatedRotation = orientationToSurfaceRotation(orientation)
                if (currentTargetRotation == updatedRotation) return
                currentTargetRotation = updatedRotation
                syncThresholdsForOrientation(
                    if (updatedRotation == Surface.ROTATION_90 || updatedRotation == Surface.ROTATION_270) {
                        Configuration.ORIENTATION_LANDSCAPE
                    } else {
                        Configuration.ORIENTATION_PORTRAIT
                    }
                )
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

    private fun updateOrientationFromRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        val adjustedRotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        when (currentTargetRotation) {
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
                warningsMask = lyingBit,
                detectedWarningsMask = lyingBit,
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
                warningsMask = if (isLyingActive) 8 else 0,
                detectedWarningsMask = if (isLyingActive) 8 else 0,
                isLyingActive = isLyingActive,
                lastFaceDetectedTime = lastFaceSeenTimestamp,
                isCameraFrame = true,
                faceDetected = false,
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
        val now = SystemClock.uptimeMillis()
        syncThresholdsForOrientation(
            if (currentTargetRotation == Surface.ROTATION_90 || currentTargetRotation == Surface.ROTATION_270) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        )
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
        try {
            val detection = detectBestFrameResult(
                mediaImage = mediaImage,
                sourceWidth = imageProxy.width,
                sourceHeight = imageProxy.height,
                primaryRotationDegrees = imageProxy.imageInfo.rotationDegrees
            )
            if (!isRunning) return

            val face = detection.face
            val pose = detection.pose
            if (face != null) lastFaceSeenTimestamp = SystemClock.uptimeMillis()

            val warnings = ruleDetector.detectWarnings(
                face = face,
                pose = pose,
                imageWidth = detection.imageWidth,
                imageHeight = detection.imageHeight
            )

            val detectedWarningsWithLying = if (isLyingActive) warnings + WarningState.LYING else warnings
            lastDetectedWarnings = detectedWarningsWithLying

            val stableWarnings = stabilizeCameraWarnings(warnings, face)
            val warningsWithLying = if (isLyingActive) stableWarnings + WarningState.LYING else stableWarnings
            val activatedPublishedWarnings = warningsWithLying - lastPublishedWarnings
            val deactivatedPublishedWarnings = lastPublishedWarnings - warningsWithLying
            activatedPublishedWarnings.forEach(onWarningActivated)
            deactivatedPublishedWarnings.forEach(onWarningDeactivated)
            lastPublishedWarnings = warningsWithLying
            onWarnings(warningsWithLying)
            val irisNorm = face?.let { ruleDetector.computeNormalizedIrisDistance(it, detection.imageWidth) }
            val leftEyeOpen = face?.leftEyeOpenProbability
            val rightEyeOpen = face?.rightEyeOpenProbability
            val facePitchDeg = if (!smoothedFacePitchDegrees.isNaN()) smoothedFacePitchDegrees else null
            val eyeOpenMin = face?.let { ruleDetector.computeEyeOpenMin(it) }
            val slouchScore = pose?.let { ruleDetector.computePostureRatio(it) }?.toFloat()
            val squintHoldMs = face?.let(::currentSquintHoldMs)

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
                    isLyingActive = isLyingActive,
                    lastFaceDetectedTime = lastFaceSeenTimestamp,
                    isCameraFrame = true,
                    faceDetected = face != null,
                    poseDetected = pose != null,
                    faceError = detection.faceError,
                    poseError = detection.poseError,
                    irisNorm = irisNorm,
                    leftEyeOpen = leftEyeOpen,
                    rightEyeOpen = rightEyeOpen,
                    eyeOpenMin = eyeOpenMin,
                    slouchScore = slouchScore,
                    facePitchDeg = facePitchDeg,
                    pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
                    rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
                    tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null,
                    squintHoldMs = squintHoldMs
                )
            )
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Camera analysis failed", exception)
            publishCameraError(onMetrics)
        } finally {
            imageProxy.close()
        }
    }

    private fun syncThresholdsForOrientation(orientation: Int) {
        val thresholds = thresholdsProvider?.invoke(orientation) ?: return
        setThresholds(
            irisDistance = thresholds.irisThreshold,
            eyeOpenThreshold = thresholds.eyeOpenThreshold,
            slouchRatioThreshold = thresholds.slouchThreshold.toDouble()
        )
    }

    private fun stabilizeCameraWarnings(
        warnings: Set<WarningState>,
        face: com.google.mlkit.vision.face.Face?
    ): Set<WarningState> {
        // Distance warning should be immediate; squint/slouch are noisier ML classifications.
        val now = SystemClock.uptimeMillis()
        val leftEyeOpen = face?.leftEyeOpenProbability
        val rightEyeOpen = face?.rightEyeOpenProbability
        val faceHeadPitchAbs = face?.let { kotlin.math.abs(it.headEulerAngleX) }
        val headYawAbs = kotlin.math.abs(face?.headEulerAngleY ?: 0f)
        val headRollAbs = kotlin.math.abs(face?.headEulerAngleZ ?: 0f)
        val eyeBrowGapRatio = face?.let(ruleDetector::computeEyeBrowGapRatio)
        val lowHeadActive = updateLowHeadState(
            facePitchAbs = faceHeadPitchAbs,
            headYawAbs = headYawAbs,
            headRollAbs = headRollAbs,
            squintDetected = warnings.contains(WarningState.SQUINTING)
        )
        val adjustedSquintThreshold = adjustedSquintThreshold(
            baseThreshold = ruleDetector.eyeOpenThreshold,
            lowHeadActive = lowHeadActive,
            headYawAbs = headYawAbs,
            headRollAbs = headRollAbs,
            eyeBrowGapRatio = eyeBrowGapRatio
        )
        val adjustedSquintHoldMs = adjustedSquintHoldMs(
            lowHeadActive = lowHeadActive,
            headYawAbs = headYawAbs,
            headRollAbs = headRollAbs,
            eyeBrowGapRatio = eyeBrowGapRatio
        )
        val squintDetected = ruleDetector.areBothEyesBelowThreshold(
            leftEyeOpenProbability = leftEyeOpen,
            rightEyeOpenProbability = rightEyeOpen,
            threshold = adjustedSquintThreshold
        )
        if (squintDetected) {
            if (squintCandidateStartTimestamp == 0L) squintCandidateStartTimestamp = now
        } else {
            squintCandidateStartTimestamp = 0L
        }
        slouchWarningStreak = if (warnings.contains(WarningState.SLOUCHING)) slouchWarningStreak + 1 else 0

        val publishSquintWarning = shouldPublishSquintWarning(
            squintDetected = squintDetected,
            candidateStartTimestamp = squintCandidateStartTimestamp,
            now = now,
            holdMs = adjustedSquintHoldMs
        )

        return buildSet {
            if (warnings.contains(WarningState.TOO_CLOSE)) add(WarningState.TOO_CLOSE)
            if (publishSquintWarning) {
                add(WarningState.SQUINTING)
            }
            if (slouchWarningStreak >= CAMERA_WARNING_CONFIRM_FRAMES) add(WarningState.SLOUCHING)
        }
    }

    private fun detectBestFrameResult(
        mediaImage: android.media.Image,
        sourceWidth: Int,
        sourceHeight: Int,
        primaryRotationDegrees: Int
    ): FrameDetectionResult {
        val candidateRotations = buildList {
            add(primaryRotationDegrees)
            addAll(FALLBACK_ROTATION_DEGREES)
        }.distinct()

        var firstResult: FrameDetectionResult? = null
        for (rotationDegrees in candidateRotations) {
            val result = detectFrame(mediaImage, sourceWidth, sourceHeight, rotationDegrees)
            if (firstResult == null) firstResult = result
            if (result.face != null) return result
        }
        return firstResult ?: FrameDetectionResult(
            face = null,
            pose = null,
            imageWidth = sourceWidth,
            imageHeight = sourceHeight,
            faceError = true,
            poseError = true
        )
    }

    private fun detectFrame(
        mediaImage: android.media.Image,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int
    ): FrameDetectionResult {
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val imageWidth = if (rotationDegrees % 180 == 0) sourceWidth else sourceHeight
        val imageHeight = if (rotationDegrees % 180 == 0) sourceHeight else sourceWidth

        val faceTask = faceDetector.process(image)
        val poseTask = poseDetector.process(image)
        Tasks.whenAllComplete(faceTask, poseTask)

        val faceResult = runCatching { Tasks.await(faceTask).firstOrNull() }.getOrNull()
        val poseResult = runCatching { Tasks.await(poseTask) }.getOrNull()

        return FrameDetectionResult(
            face = faceResult,
            pose = poseResult,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            faceError = faceTask.isCanceled || faceTask.exception != null,
            poseError = poseTask.isCanceled || poseTask.exception != null
        )
    }

    private fun currentSquintHoldMs(face: com.google.mlkit.vision.face.Face): Long {
        val headYawAbs = kotlin.math.abs(face.headEulerAngleY)
        val headRollAbs = kotlin.math.abs(face.headEulerAngleZ)
        val eyeBrowGapRatio = ruleDetector.computeEyeBrowGapRatio(face)
        return adjustedSquintHoldMs(
            lowHeadActive = isLowHeadActive,
            headYawAbs = headYawAbs,
            headRollAbs = headRollAbs,
            eyeBrowGapRatio = eyeBrowGapRatio
        )
    }

    private fun updateLowHeadState(
        facePitchAbs: Float?,
        headYawAbs: Float,
        headRollAbs: Float,
        squintDetected: Boolean
    ): Boolean {
        smoothedFacePitchDegrees = smoothFacePitch(smoothedFacePitchDegrees, facePitchAbs)
        val smoothedPitch = smoothedFacePitchDegrees
        if (smoothedPitch.isNaN()) {
            isLowHeadActive = false
            return false
        }

        val baselineLocked = squintDetected || headYawAbs >= SIDE_HEAD_YAW_DEG || headRollAbs >= TILT_HEAD_ROLL_DEG
        baselineFacePitchDegrees = updateFacePitchBaseline(
            previousBaseline = baselineFacePitchDegrees,
            smoothedFacePitch = smoothedPitch,
            allowUpdate = !baselineLocked && !isLowHeadActive
        )
        isLowHeadActive = evaluateLowHeadState(
            smoothedFacePitch = smoothedPitch,
            baselineFacePitch = baselineFacePitchDegrees,
            wasActive = isLowHeadActive
        )
        return isLowHeadActive
    }

    private fun currentDisplayRotation(): Int {
        val contextDisplayRotation = runCatching { context.display?.rotation }.getOrNull()
        if (contextDisplayRotation != null) return contextDisplayRotation

        val displayManager = context.getSystemService(DisplayManager::class.java)
        val managedDisplayRotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        if (managedDisplayRotation != null) return managedDisplayRotation

        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        val windowRotation = windowManager?.defaultDisplay?.rotation
        if (windowRotation != null) return windowRotation

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

    private data class FrameDetectionResult(
        val face: Face?,
        val pose: Pose?,
        val imageWidth: Int,
        val imageHeight: Int,
        val faceError: Boolean,
        val poseError: Boolean
    )
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
        internal const val SQUINT_WARNING_HOLD_MS = 3000L
        internal const val LOW_HEAD_SQUINT_HOLD_MS = 3000L
        internal const val FACE_PITCH_SMOOTHING_ALPHA = 0.25f
        internal const val FACE_PITCH_BASELINE_ALPHA = 0.08f
        internal const val LOW_HEAD_ENTER_DELTA_DEG = 4f
        internal const val LOW_HEAD_EXIT_DELTA_DEG = 2f
        internal const val LOW_HEAD_THRESHOLD_SCALE = 0.8f
        internal const val SIDE_HEAD_YAW_DEG = 15f
        internal const val TILT_HEAD_ROLL_DEG = 12f
        internal const val OFF_AXIS_SQUINT_HOLD_MS = 6000L
        internal const val OFF_AXIS_THRESHOLD_SCALE = 0.7f
        internal const val SMALL_BROW_GAP_RATIO = 0.055f
        internal const val SMALL_BROW_GAP_HOLD_MS = 6000L
        internal const val SMALL_BROW_GAP_THRESHOLD_SCALE = 0.7f
        private const val LYING_PITCH_DEG = 65.0
        private const val LYING_ROLL_DEG = 65.0
        private const val LYING_SIDE_MIN_PITCH_DEG = 35.0
        private const val LYING_TILT_FROM_HORIZONTAL_DEG = 25.0
        private const val LYING_MIN_GYRO_MAG = 0.03
        private const val LYING_MAX_GYRO_MAG = 3.0
        private const val LYING_FACE_RECENCY_MS = 5000L
        private val FALLBACK_ROTATION_DEGREES = listOf(0, 90, 270, 180)

        internal fun shouldPublishSquintWarning(
            squintDetected: Boolean,
            candidateStartTimestamp: Long,
            now: Long,
            holdMs: Long = SQUINT_WARNING_HOLD_MS
        ): Boolean {
            if (!squintDetected || candidateStartTimestamp == 0L) return false
            return now - candidateStartTimestamp >= holdMs
        }

        internal fun adjustedSquintThreshold(
            baseThreshold: Float,
            lowHeadActive: Boolean,
            headYawAbs: Float,
            headRollAbs: Float,
            eyeBrowGapRatio: Float?
        ): Float {
            var adjusted = baseThreshold
            if (lowHeadActive) adjusted *= LOW_HEAD_THRESHOLD_SCALE
            if (headYawAbs >= SIDE_HEAD_YAW_DEG || headRollAbs >= TILT_HEAD_ROLL_DEG) adjusted *= OFF_AXIS_THRESHOLD_SCALE
            if (eyeBrowGapRatio != null && eyeBrowGapRatio <= SMALL_BROW_GAP_RATIO) adjusted *= SMALL_BROW_GAP_THRESHOLD_SCALE
            return adjusted.coerceIn(0.10f, 0.90f)
        }

        internal fun adjustedSquintHoldMs(
            lowHeadActive: Boolean,
            headYawAbs: Float,
            headRollAbs: Float,
            eyeBrowGapRatio: Float?
        ): Long {
            var holdMs = SQUINT_WARNING_HOLD_MS
            if (lowHeadActive) holdMs = maxOf(holdMs, LOW_HEAD_SQUINT_HOLD_MS)
            if (headYawAbs >= SIDE_HEAD_YAW_DEG || headRollAbs >= TILT_HEAD_ROLL_DEG) holdMs = maxOf(holdMs, OFF_AXIS_SQUINT_HOLD_MS)
            if (eyeBrowGapRatio != null && eyeBrowGapRatio <= SMALL_BROW_GAP_RATIO) {
                holdMs = maxOf(holdMs, SMALL_BROW_GAP_HOLD_MS)
            }
            return holdMs
        }

        internal fun smoothFacePitch(previousSmoothed: Float, facePitchAbs: Float?): Float {
            if (facePitchAbs == null) return previousSmoothed
            if (previousSmoothed.isNaN()) return facePitchAbs
            return previousSmoothed + FACE_PITCH_SMOOTHING_ALPHA * (facePitchAbs - previousSmoothed)
        }

        internal fun updateFacePitchBaseline(
            previousBaseline: Float,
            smoothedFacePitch: Float,
            allowUpdate: Boolean
        ): Float {
            if (smoothedFacePitch.isNaN()) return previousBaseline
            if (previousBaseline.isNaN()) return smoothedFacePitch
            if (!allowUpdate) return previousBaseline
            return previousBaseline + FACE_PITCH_BASELINE_ALPHA * (smoothedFacePitch - previousBaseline)
        }

        internal fun evaluateLowHeadState(
            smoothedFacePitch: Float,
            baselineFacePitch: Float,
            wasActive: Boolean
        ): Boolean {
            if (smoothedFacePitch.isNaN() || baselineFacePitch.isNaN()) return false
            val enterThreshold = baselineFacePitch + LOW_HEAD_ENTER_DELTA_DEG
            val exitThreshold = baselineFacePitch + LOW_HEAD_EXIT_DELTA_DEG
            return if (wasActive) smoothedFacePitch >= exitThreshold else smoothedFacePitch >= enterThreshold
        }
    }
}
