package com.example.eyeprotect

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import com.example.eyeprotect.R
import com.example.eyeprotect.monitoring.DeepNightLyingReminder
import com.example.eyeprotect.monitoring.EyeExerciseOverlayService
import com.example.eyeprotect.monitoring.MonitoringMetrics
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import com.google.android.gms.tasks.Tasks
import kotlin.random.Random

@AndroidEntryPoint
@Suppress("LeakingThis")
class EyeHealthAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener, LifecycleOwner {

    @Inject
    lateinit var faceDetector: FaceDetector
    
    @Inject
    lateinit var poseDetector: PoseDetector
    
    @Inject
    lateinit var tts: TextToSpeech

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private val detector = PostureAndEyeDetector()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val cameraFrameAnalyzer = object : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            analyzeImage(imageProxy)
        }
    }
    private var lastDetectionTimestamp = 0L
    private var lastTtsTimestamp = 0L
    private var lastVibrationTimestamp = 0L
    private var isTooCloseOverlayShown = false
    private var cachedChineseVoices: List<Voice> = emptyList()
    private var lastVoiceRefreshTimestamp = 0L
    private var lastGyroMagnitude = 0.0

    private var lastPitchDegrees = Double.NaN
    private var lastRollDegrees = Double.NaN
    private var lastTiltDegrees = Double.NaN
    private var lastTiltFromHorizontalDegrees = Double.NaN
    private var lyingCandidateStartTimestamp = 0L
    private var isLyingActive = false
    private var lastSensorPublishTimestamp = 0L
    private var lastLyingAlertTimestamp = 0L
    private var lastFaceSeenTimestamp = 0L
    private var isMonitoringEnabled = true

    private var autoExerciseUsageMs = 0L
    private var lastAutoExerciseTickUptimeMs = 0L
    private var lastAutoExerciseShownUptimeMs = 0L

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var gravitySensor: Sensor? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val wx = event.values.getOrNull(0)?.toDouble() ?: 0.0
                    val wy = event.values.getOrNull(1)?.toDouble() ?: 0.0
                    val wz = event.values.getOrNull(2)?.toDouble() ?: 0.0
                    lastGyroMagnitude = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    updateOrientationFromRotationVector(event.values)
                }
                Sensor.TYPE_GRAVITY -> {
                    updateTiltFromGravity(event.values)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val thresholdUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra("irisDistance")) {
                detector.irisDistanceThreshold = intent.getFloatExtra("irisDistance", detector.irisDistanceThreshold)
                detector.enableTooCloseWarning = true
            }
            if (intent.hasExtra("eyeOpenThreshold")) {
                detector.eyeOpenThreshold = intent.getFloatExtra("eyeOpenThreshold", detector.eyeOpenThreshold)
                detector.enableSquintWarning = true
            }
            if (intent.hasExtra("slouchAngleThreshold")) {
                detector.slouchingPostureRatioThreshold =
                    intent.getFloatExtra("slouchAngleThreshold", detector.slouchingPostureRatioThreshold.toFloat()).toDouble()
                detector.enableSlouchWarning = true
            }
        }
    }

    private val monitoringToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET_MONITORING) return
            val enabled = intent.getBooleanExtra(EXTRA_MONITORING_ENABLED, true)
            val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_MONITORING_ENABLED, enabled).apply()
            applyMonitoringState(enabled)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // 載入儲存的門檻值 (未校正前不啟用提醒，避免誤報)
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        isMonitoringEnabled = prefs.getBoolean(PREF_MONITORING_ENABLED, true)
        if (prefs.contains("iris_threshold")) {
            detector.irisDistanceThreshold = prefs.getFloat("iris_threshold", detector.irisDistanceThreshold)
            detector.enableTooCloseWarning = true
        }
        if (prefs.contains("eye_open_threshold")) {
            detector.eyeOpenThreshold = prefs.getFloat("eye_open_threshold", detector.eyeOpenThreshold)
            detector.enableSquintWarning = true
        }
        if (prefs.contains("slouch_angle_threshold")) {
            detector.slouchingPostureRatioThreshold =
                prefs.getFloat("slouch_angle_threshold", detector.slouchingPostureRatioThreshold.toFloat()).toDouble()
            detector.enableSlouchWarning = true
        }

        ContextCompat.registerReceiver(
            this,
            thresholdUpdateReceiver,
            IntentFilter(ACTION_UPDATE_THRESHOLDS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            monitoringToggleReceiver,
            IntentFilter(ACTION_SET_MONITORING),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // Best-effort; init callback isn't guaranteed with current DI wiring.
        tts.language = Locale.TRADITIONAL_CHINESE
        applyMonitoringState(isMonitoringEnabled)
    }

    private fun applyMonitoringState(enabled: Boolean) {
        isMonitoringEnabled = enabled
        if (enabled) {
            startSensors()
            startCamera()
        } else {
            stopCamera()
            stopSensors()
            ContextCompat.getMainExecutor(this).execute {
                hideScreenOverlay()
                isTooCloseOverlayShown = false
            }
            isLyingActive = false
            lyingCandidateStartTimestamp = 0L
            lastDetectionTimestamp = 0L
            publishMonitoringPaused()
        }
    }

    private fun publishMonitoringPaused() {
        val now = SystemClock.uptimeMillis()
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(PREF_LIVE_TS, now)
            .putInt(PREF_LIVE_WARNINGS_MASK, 0)
            .putFloat(PREF_LIVE_IRIS_NORM, Float.NaN)
            .putFloat(PREF_LIVE_EYE_OPEN_MIN, Float.NaN)
            .putFloat(PREF_LIVE_SLOUCH_SCORE, Float.NaN)
            .putLong(PREF_LIVE_FACE_SEEN_UPTIME_MS, 0L)
            .apply()

        val intent = Intent(ACTION_LIVE_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LIVE_TS, now)
            putExtra(EXTRA_LIVE_WARNINGS_MASK, 0)
            putExtra(EXTRA_LIVE_IRIS_NORM, Float.NaN)
            putExtra(EXTRA_LIVE_EYE_OPEN_MIN, Float.NaN)
            putExtra(EXTRA_LIVE_SLOUCH_SCORE, Float.NaN)
            putExtra(EXTRA_LIVE_FACE_SEEN_UPTIME_MS, 0L)
        }
        sendBroadcast(intent)
    }

    private fun startSensors() {
        if (!isMonitoringEnabled) return
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        rotationVectorSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gravitySensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopSensors() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (_: Exception) {
        }
    }

    private fun updateOrientationFromRotationVector(values: FloatArray) {
        // Derive pitch/roll from rotation vector. This relies on sensor fusion (uses gyro internally).
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val pitchRad = orientation[1].toDouble()
        val rollRad = orientation[2].toDouble()
        lastPitchDegrees = kotlin.math.abs(Math.toDegrees(pitchRad))
        lastRollDegrees = kotlin.math.abs(Math.toDegrees(rollRad))

        updateLyingState()
        publishSensorMetricsIfNeeded()
    }

    private fun updateTiltFromGravity(values: FloatArray) {
        val gx = values.getOrNull(0)?.toDouble() ?: return
        val gy = values.getOrNull(1)?.toDouble() ?: return
        val gz = values.getOrNull(2)?.toDouble() ?: return
        val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        if (g <= 0.0) return

        // Tilt angle between device Z axis (screen normal) and gravity vector.
        // Range is 0..180 (0 ~= flat one side, 180 ~= flat the other side, 90 ~= upright).
        val cos = (gz / g).coerceIn(-1.0, 1.0)
        lastTiltDegrees = Math.toDegrees(kotlin.math.acos(cos))
        lastTiltFromHorizontalDegrees = kotlin.math.min(lastTiltDegrees, 180.0 - lastTiltDegrees)

        updateLyingState()
        publishSensorMetricsIfNeeded()
    }

    private fun updateLyingState() {
        val now = SystemClock.uptimeMillis()
        // Prefer gravity-based tilt if available; rotation-vector pitch/roll varies by device and screen rotation.
        val isCandidate = if (!lastTiltDegrees.isNaN()) {
            !lastTiltFromHorizontalDegrees.isNaN() && lastTiltFromHorizontalDegrees <= LYING_TILT_FROM_HORIZONTAL_DEG
        } else if (!lastPitchDegrees.isNaN() && !lastRollDegrees.isNaN()) {
            // Fallback: old heuristic.
            val nearHorizontal = lastPitchDegrees >= LYING_PITCH_DEG
            val sideWhileHorizontal = lastRollDegrees >= LYING_ROLL_DEG && lastPitchDegrees >= LYING_SIDE_MIN_PITCH_DEG
            (nearHorizontal || sideWhileHorizontal)
        } else {
            false
        }

        // Hand-held tends to have small continuous micro-motion; completely static (on desk) should not trigger.
        val handHeldLike = lastGyroMagnitude in LYING_MIN_GYRO_MAG..LYING_MAX_GYRO_MAG

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = powerManager.isInteractive
        val faceRecentlySeen = now - lastFaceSeenTimestamp <= LYING_FACE_RECENCY_MS

        if (isCandidate && handHeldLike && isInteractive && faceRecentlySeen) {
            if (lyingCandidateStartTimestamp == 0L) lyingCandidateStartTimestamp = now
            if (!isLyingActive && now - lyingCandidateStartTimestamp >= LYING_HOLD_MS) {
                isLyingActive = true
                onLyingActivated(now)
            }
        } else {
            lyingCandidateStartTimestamp = 0L
            isLyingActive = false
        }
    }

    private fun onLyingActivated(now: Long) {
        if (!isMonitoringEnabled) return
        if (now - lastLyingAlertTimestamp < LYING_ALERT_COOLDOWN_MS) return
        lastLyingAlertTimestamp = now

        // Trigger immediately; not dependent on camera analyze loop.
        val mainExecutor = ContextCompat.getMainExecutor(this)
        mainExecutor.execute {
            if (!isMonitoringEnabled) return@execute
            val didSpeak = speakWarning("不要躺著滑手機", priority = true)
            if (didSpeak) {
                vibrateWarning(priority = true)
                showWarningNotification("不要躺著滑手機")
            }
        }
    }

    private fun publishSensorMetricsIfNeeded() {
        if (!isMonitoringEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSensorPublishTimestamp < SENSOR_METRICS_INTERVAL_MS) return
        lastSensorPublishTimestamp = now

        // Publish even if camera isn't producing frames so the "lying" indicator updates.
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val existingMask = prefs.getInt(PREF_LIVE_WARNINGS_MASK, 0)
        val lyingBit = if (isLyingActive) 8 else 0
        val mergedMask = (existingMask and 0x7) or lyingBit
        val editor = prefs.edit()
            .putLong(PREF_LIVE_TS, now)
            .putInt(PREF_LIVE_WARNINGS_MASK, mergedMask)
            .putLong(PREF_LIVE_FACE_SEEN_UPTIME_MS, lastFaceSeenTimestamp)
        if (!lastPitchDegrees.isNaN()) editor.putFloat(PREF_LIVE_PITCH_DEG, lastPitchDegrees.toFloat())
        if (!lastRollDegrees.isNaN()) editor.putFloat(PREF_LIVE_ROLL_DEG, lastRollDegrees.toFloat())
        if (!lastTiltDegrees.isNaN()) editor.putFloat(PREF_LIVE_TILT_DEG, lastTiltDegrees.toFloat())
        editor.apply()

        val intent = Intent(ACTION_LIVE_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LIVE_TS, now)
            putExtra(EXTRA_LIVE_WARNINGS_MASK, mergedMask)
            putExtra(EXTRA_LIVE_FACE_SEEN_UPTIME_MS, lastFaceSeenTimestamp)
            if (!lastPitchDegrees.isNaN()) putExtra(EXTRA_LIVE_PITCH_DEG, lastPitchDegrees.toFloat())
            if (!lastRollDegrees.isNaN()) putExtra(EXTRA_LIVE_ROLL_DEG, lastRollDegrees.toFloat())
            if (!lastTiltDegrees.isNaN()) putExtra(EXTRA_LIVE_TILT_DEG, lastTiltDegrees.toFloat())
        }
        sendBroadcast(intent)
    }

    private fun startCamera() {
        if (!isMonitoringEnabled) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider: ProcessCameraProvider = cameraProviderFuture.get()
                cameraProvider = provider
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, cameraFrameAnalyzer)
                    }
                imageAnalyzer = analyzer
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
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
    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!isMonitoringEnabled) {
            imageProxy.close()
            return
        }
        val currentTimestamp = SystemClock.uptimeMillis()
        if (currentTimestamp - lastDetectionTimestamp < DETECTION_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastDetectionTimestamp = currentTimestamp

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width

        // 並行處理人臉與姿勢偵測
        val faceTask = faceDetector.process(image)
        val poseTask = poseDetector.process(image)

        // Always update state even if one task fails; otherwise UI/warnings can get "stuck".
        Tasks.whenAllComplete(faceTask, poseTask).addOnCompleteListener {
            if (!isMonitoringEnabled) {
                imageProxy.close()
                return@addOnCompleteListener
            }
            val face = if (faceTask.isSuccessful) faceTask.result?.firstOrNull() else null
            val pose = if (poseTask.isSuccessful) poseTask.result else null
            if (face != null) lastFaceSeenTimestamp = SystemClock.uptimeMillis()

            val warnings = detector.detectWarnings(
                face = face,
                pose = pose,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

            publishLiveMetrics(
                face = face,
                pose = pose,
                imageWidth = imageWidth,
                warnings = warnings
            )
            handleWarningState(warnings)

            imageProxy.close()
        }
    }

    private fun publishLiveMetrics(
        face: com.google.mlkit.vision.face.Face?,
        pose: com.google.mlkit.vision.pose.Pose?,
        imageWidth: Int,
        warnings: Set<WarningState>
    ) {
        if (!isMonitoringEnabled) return
        val warningsWithLying = if (isLyingActive) warnings + WarningState.LYING else warnings
        val irisNorm = face?.let { detector.computeNormalizedIrisDistance(it, imageWidth) }
        val eyeOpenMin = face?.let { detector.computeEyeOpenMin(it) }
        val slouchScore = pose?.let { detector.computePostureRatio(it) }

        val warningsMask =
            (if (warningsWithLying.contains(WarningState.TOO_CLOSE)) 1 else 0) or
                (if (warningsWithLying.contains(WarningState.SQUINTING)) 2 else 0) or
                (if (warningsWithLying.contains(WarningState.SLOUCHING)) 4 else 0) or
                (if (warningsWithLying.contains(WarningState.LYING)) 8 else 0)

        val now = SystemClock.uptimeMillis()
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong(PREF_LIVE_TS, now)
            .putInt(PREF_LIVE_WARNINGS_MASK, warningsMask)
            .putLong(PREF_LIVE_FACE_SEEN_UPTIME_MS, lastFaceSeenTimestamp)
        // Clear stale values when the current frame has no face/pose.
        editor.putFloat(PREF_LIVE_IRIS_NORM, irisNorm ?: Float.NaN)
        editor.putFloat(PREF_LIVE_EYE_OPEN_MIN, eyeOpenMin ?: Float.NaN)
        editor.putFloat(PREF_LIVE_SLOUCH_SCORE, slouchScore?.toFloat() ?: Float.NaN)
        if (!lastPitchDegrees.isNaN()) editor.putFloat(PREF_LIVE_PITCH_DEG, lastPitchDegrees.toFloat())
        if (!lastRollDegrees.isNaN()) editor.putFloat(PREF_LIVE_ROLL_DEG, lastRollDegrees.toFloat())
        if (!lastTiltDegrees.isNaN()) editor.putFloat(PREF_LIVE_TILT_DEG, lastTiltDegrees.toFloat())
        editor.apply()

        val intent = Intent(ACTION_LIVE_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LIVE_TS, now)
            putExtra(EXTRA_LIVE_WARNINGS_MASK, warningsMask)
            putExtra(EXTRA_LIVE_FACE_SEEN_UPTIME_MS, lastFaceSeenTimestamp)
            putExtra(EXTRA_LIVE_IRIS_NORM, irisNorm ?: Float.NaN)
            putExtra(EXTRA_LIVE_EYE_OPEN_MIN, eyeOpenMin ?: Float.NaN)
            putExtra(EXTRA_LIVE_SLOUCH_SCORE, slouchScore?.toFloat() ?: Float.NaN)
            if (!lastPitchDegrees.isNaN()) putExtra(EXTRA_LIVE_PITCH_DEG, lastPitchDegrees.toFloat())
            if (!lastRollDegrees.isNaN()) putExtra(EXTRA_LIVE_ROLL_DEG, lastRollDegrees.toFloat())
            if (!lastTiltDegrees.isNaN()) putExtra(EXTRA_LIVE_TILT_DEG, lastTiltDegrees.toFloat())
        }
        sendBroadcast(intent)

        maybeRunAutoNightReminder(
            nowUptimeMs = now,
            warningsMask = warningsMask,
            irisNorm = irisNorm,
            eyeOpenMin = eyeOpenMin,
            slouchScore = slouchScore?.toFloat()
        )
        maybeTriggerAutoEyeExercise(nowUptimeMs = now)
    }

    private fun maybeRunAutoNightReminder(
        nowUptimeMs: Long,
        warningsMask: Int,
        irisNorm: Float?,
        eyeOpenMin: Float?,
        slouchScore: Float?,
    ) {
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, false)) return
        if (!isMonitoringEnabled) return

        val metrics = MonitoringMetrics(
            ts = nowUptimeMs,
            warningsMask = warningsMask,
            isLyingActive = isLyingActive,
            lastFaceDetectedTime = lastFaceSeenTimestamp,
            irisNorm = irisNorm,
            eyeOpenMin = eyeOpenMin,
            slouchScore = slouchScore,
            pitchDeg = if (!lastPitchDegrees.isNaN()) lastPitchDegrees.toFloat() else null,
            rollDeg = if (!lastRollDegrees.isNaN()) lastRollDegrees.toFloat() else null,
            tiltDeg = if (!lastTiltDegrees.isNaN()) lastTiltDegrees.toFloat() else null
        )

        ContextCompat.getMainExecutor(this).execute {
            if (!isMonitoringEnabled) return@execute
            DeepNightLyingReminder.update(metrics = metrics, tts = tts)
        }
    }

    private fun maybeTriggerAutoEyeExercise(nowUptimeMs: Long) {
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, false)) {
            autoExerciseUsageMs = 0L
            lastAutoExerciseTickUptimeMs = 0L
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = powerManager.isInteractive
        val faceRecent =
            lastFaceSeenTimestamp > 0L && nowUptimeMs - lastFaceSeenTimestamp <= AUTO_EXERCISE_FACE_RECENCY_MS
        val active = isInteractive && faceRecent

        if (!active) {
            if (lastAutoExerciseTickUptimeMs > 0L && nowUptimeMs - lastAutoExerciseTickUptimeMs >= AUTO_EXERCISE_RESET_GAP_MS) {
                autoExerciseUsageMs = 0L
            }
            lastAutoExerciseTickUptimeMs = nowUptimeMs
            return
        }

        if (lastAutoExerciseTickUptimeMs > 0L) {
            val gap = nowUptimeMs - lastAutoExerciseTickUptimeMs
            if (gap >= AUTO_EXERCISE_RESET_GAP_MS) {
                autoExerciseUsageMs = 0L
            } else {
                autoExerciseUsageMs += gap.coerceAtMost(AUTO_EXERCISE_MAX_TICK_MS)
            }
        }
        lastAutoExerciseTickUptimeMs = nowUptimeMs

        if (autoExerciseUsageMs < AUTO_EXERCISE_TRIGGER_MS) return
        if (lastAutoExerciseShownUptimeMs > 0L && nowUptimeMs - lastAutoExerciseShownUptimeMs < AUTO_EXERCISE_COOLDOWN_MS) return

        lastAutoExerciseShownUptimeMs = nowUptimeMs
        autoExerciseUsageMs = 0L
        ContextCompat.getMainExecutor(this).execute {
            if (!isMonitoringEnabled) return@execute
            EyeExerciseOverlayService.start(this, AUTO_EXERCISE_SECONDS)
        }
    }

    private fun handleWarningState(warnings: Set<WarningState>) {
        if (!isMonitoringEnabled) {
            ContextCompat.getMainExecutor(this).execute {
                hideScreenOverlay()
                isTooCloseOverlayShown = false
            }
            return
        }
        val mainExecutor = ContextCompat.getMainExecutor(this)
        
        mainExecutor.execute {
            if (!isMonitoringEnabled) return@execute
            val effectiveWarnings = if (isLyingActive) warnings + WarningState.LYING else warnings
            // 處理距離過近 (全螢幕遮罩)
            if (effectiveWarnings.contains(WarningState.TOO_CLOSE)) {
                if (!isTooCloseOverlayShown) {
                    showScreenOverlay()
                    isTooCloseOverlayShown = true
                    vibrateWarning(priority = true)
                    speakWarning("請保持距離", priority = true)
                } else {
                    val didSpeak = speakWarning("請保持距離")
                    if (didSpeak) vibrateWarning()
                }
            } else {
                if (isTooCloseOverlayShown) {
                    hideScreenOverlay()
                    isTooCloseOverlayShown = false
                }
            }

            // 處理瞇眼與駝背 (通知與語音)
            val postureText = mutableListOf<String>()
            if (effectiveWarnings.contains(WarningState.SQUINTING)) postureText.add("不要瞇眼")
            if (effectiveWarnings.contains(WarningState.SLOUCHING)) postureText.add("請坐端正")
            if (effectiveWarnings.contains(WarningState.LYING)) postureText.add("不要躺著滑手機")

            if (postureText.isNotEmpty()) {
                val message = postureText.joinToString("，")
                val didSpeak = speakWarning(message)
                if (didSpeak) {
                    vibrateWarning()
                    showWarningNotification(message)
                }
            }
        }
    }

    private fun vibrateWarning(priority: Boolean = false): Boolean {
        if (!isMonitoringEnabled) return false
        val currentTime = SystemClock.uptimeMillis()
        if (!priority && (currentTime - lastVibrationTimestamp <= VIBRATION_COOLDOWN_MS)) return false

        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(220)
        }

        lastVibrationTimestamp = currentTime
        return true
    }

    private fun speakWarning(text: String, priority: Boolean = false): Boolean {
        if (!isMonitoringEnabled) return false
        val currentTime = SystemClock.uptimeMillis()
        if (priority || (currentTime - lastTtsTimestamp > VOICE_ALERT_COOLDOWN_MS)) {
            if (priority && tts.isSpeaking) {
                // Force the urgent message through.
                tts.stop()
            }
            if (!tts.isSpeaking) {
                applyRandomVoiceAndTone(currentTime)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warning")
                lastTtsTimestamp = currentTime
                return true
            }
        }
        return false
    }

    private fun applyRandomVoiceAndTone(now: Long) {
        // Refresh voice list occasionally; some engines populate voices after first init.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && (cachedChineseVoices.isEmpty() || now - lastVoiceRefreshTimestamp > 60_000L)) {
            cachedChineseVoices = tts.voices
                ?.asSequence()
                ?.filter { it.locale.language == Locale.CHINESE.language }
                ?.filter { !it.isNetworkConnectionRequired }
                ?.toList()
                ?: emptyList()
            lastVoiceRefreshTimestamp = now
        }

        // "Male/female/tone" isn't standardized across engines; approximate with pitch/rate,
        // and also pick voice by name hints when available.
        val style = Random.nextInt(3) // 0 male-ish, 1 female-ish, 2 neutral
        val (pitch, rate, voiceHint) = when (style) {
            0 -> Triple(0.85f + Random.nextFloat() * 0.10f, 0.95f + Random.nextFloat() * 0.10f, "male")
            1 -> Triple(1.10f + Random.nextFloat() * 0.15f, 1.00f + Random.nextFloat() * 0.12f, "female")
            else -> Triple(0.95f + Random.nextFloat() * 0.15f, 0.95f + Random.nextFloat() * 0.15f, "neutral")
        }

        tts.setPitch(pitch)
        tts.setSpeechRate(rate)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (cachedChineseVoices.isEmpty()) return

        val candidates = when (voiceHint) {
            "male" -> cachedChineseVoices.filter { voiceNameSuggestsMale(it.name) }
            "female" -> cachedChineseVoices.filter { voiceNameSuggestsFemale(it.name) }
            else -> cachedChineseVoices
        }
        val pool = if (candidates.isNotEmpty()) candidates else cachedChineseVoices
        tts.voice = pool.random()
    }

    private fun voiceNameSuggestsMale(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("male") || n.contains("man") || n.contains("m-") || n.contains("男")
    }

    private fun voiceNameSuggestsFemale(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("female") || n.contains("woman") || n.contains("f-") || n.contains("女")
    }

    private fun showScreenOverlay() {
        if (!isMonitoringEnabled) return
        if (overlayView != null) return
        overlayView = View(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try { windowManager.addView(overlayView, params) } catch (e: Exception) { Log.e(TAG, "Overlay error", e) }
    }

    private fun hideScreenOverlay() {
        overlayView?.let {
            try {
                // More aggressive removal; helps avoid a stuck dim overlay when state changes quickly.
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        overlayView = null
    }

    @SuppressLint("NotificationPermission")
    private fun showWarningNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_health)
            .setContentTitle("護眼提醒")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.CHINESE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(thresholdUpdateReceiver)
        unregisterReceiver(monitoringToggleReceiver)
        stopSensors()
        stopCamera()
        cameraExecutor.shutdown()
        tts.shutdown()
        hideScreenOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Eye Health Warnings", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_UPDATE_THRESHOLDS = "com.example.eyeprotect.UPDATE_THRESHOLDS"
        const val ACTION_LIVE_METRICS = "com.example.eyeprotect.LIVE_METRICS"
        const val ACTION_SET_MONITORING = "com.example.eyeprotect.SET_MONITORING"
        private const val TAG = "EyeHealthService"
        private const val CHANNEL_ID = "WarningChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_INTERVAL_MS = 1000L
        private const val VOICE_ALERT_COOLDOWN_MS = 15000L
        private const val VIBRATION_COOLDOWN_MS = 3000L

        const val PREF_MONITORING_ENABLED = "monitoring_enabled"
        const val PREF_LIVE_TS = "live_ts"
        const val PREF_LIVE_IRIS_NORM = "live_iris_norm"
        const val PREF_LIVE_EYE_OPEN_MIN = "live_eye_open_min"
        const val PREF_LIVE_SLOUCH_SCORE = "live_slouch_score"
        const val PREF_LIVE_PITCH_DEG = "live_pitch_deg"
        const val PREF_LIVE_ROLL_DEG = "live_roll_deg"
        const val PREF_LIVE_TILT_DEG = "live_tilt_deg"
        const val PREF_LIVE_WARNINGS_MASK = "live_warnings_mask"
        const val PREF_LIVE_FACE_SEEN_UPTIME_MS = "live_face_seen_uptime_ms"

        const val EXTRA_LIVE_TS = "ts"
        const val EXTRA_LIVE_IRIS_NORM = "irisNorm"
        const val EXTRA_LIVE_EYE_OPEN_MIN = "eyeOpenMin"
        const val EXTRA_LIVE_SLOUCH_SCORE = "slouchScore"
        const val EXTRA_LIVE_PITCH_DEG = "pitchDeg"
        const val EXTRA_LIVE_ROLL_DEG = "rollDeg"
        const val EXTRA_LIVE_TILT_DEG = "tiltDeg"
        const val EXTRA_LIVE_WARNINGS_MASK = "warningsMask"
        const val EXTRA_LIVE_FACE_SEEN_UPTIME_MS = "faceSeenUptimeMs"
        const val EXTRA_MONITORING_ENABLED = "enabled"

        private const val SENSOR_METRICS_INTERVAL_MS = 500L
        private const val LYING_HOLD_MS = 3000L
        private const val LYING_PITCH_DEG = 65.0
        private const val LYING_ROLL_DEG = 65.0
        private const val LYING_SIDE_MIN_PITCH_DEG = 35.0
        private const val LYING_TILT_FROM_HORIZONTAL_DEG = 25.0
        private const val LYING_MIN_GYRO_MAG = 0.03 // rad/s; near-zero often means on a desk
        private const val LYING_MAX_GYRO_MAG = 3.0 // rad/s
        private const val LYING_FACE_RECENCY_MS = 5000L
        private const val LYING_ALERT_COOLDOWN_MS = 20000L

        private const val AUTO_EXERCISE_FACE_RECENCY_MS = 5_000L
        private const val AUTO_EXERCISE_TRIGGER_MS = 20 * 60_000L
        private const val AUTO_EXERCISE_RESET_GAP_MS = 5 * 60_000L
        private const val AUTO_EXERCISE_COOLDOWN_MS = 60 * 60_000L
        private const val AUTO_EXERCISE_MAX_TICK_MS = 2_000L
        private const val AUTO_EXERCISE_SECONDS = 30
    }
}
