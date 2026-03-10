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
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import com.google.android.gms.tasks.Tasks
import kotlin.random.Random

@AndroidEntryPoint
@Suppress("LeakingThis")
@ExperimentalGetImage
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
    private var lastDetectionTimestamp = 0L
    private var lastTtsTimestamp = 0L
    private var lastVibrationTimestamp = 0L
    private var isTooCloseOverlayShown = false
    private var cachedChineseVoices: List<Voice> = emptyList()
    private var lastVoiceRefreshTimestamp = 0L

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

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // 載入儲存的門檻值 (未校正前不啟用提醒，避免誤報)
        val prefs = getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE)
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startCamera()
        createNotificationChannel()
        // Best-effort; init callback isn't guaranteed with current DI wiring.
        tts.language = Locale.TRADITIONAL_CHINESE
    }

    @ExperimentalGetImage
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, this::analyzeImage)
                    }
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun analyzeImage(imageProxy: ImageProxy) {
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

        Tasks.whenAllComplete(faceTask, poseTask)
            .addOnSuccessListener {
                val faces = faceTask.result
                val pose = poseTask.result
                val face = faces?.firstOrNull()
                
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
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun publishLiveMetrics(
        face: com.google.mlkit.vision.face.Face?,
        pose: com.google.mlkit.vision.pose.Pose?,
        imageWidth: Int,
        warnings: Set<WarningState>
    ) {
        val irisNorm = face?.let { detector.computeNormalizedIrisDistance(it, imageWidth) }
        val eyeOpenMin = face?.let { detector.computeEyeOpenMin(it) }
        val slouchScore = pose?.let { detector.computePostureRatio(it) }

        val warningsMask =
            (if (warnings.contains(WarningState.TOO_CLOSE)) 1 else 0) or
                (if (warnings.contains(WarningState.SQUINTING)) 2 else 0) or
                (if (warnings.contains(WarningState.SLOUCHING)) 4 else 0)

        val now = SystemClock.uptimeMillis()
        val prefs = getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong(PREF_LIVE_TS, now)
            .putInt(PREF_LIVE_WARNINGS_MASK, warningsMask)
        if (irisNorm != null) editor.putFloat(PREF_LIVE_IRIS_NORM, irisNorm)
        if (eyeOpenMin != null) editor.putFloat(PREF_LIVE_EYE_OPEN_MIN, eyeOpenMin)
        if (slouchScore != null) editor.putFloat(PREF_LIVE_SLOUCH_SCORE, slouchScore.toFloat())
        editor.apply()

        val intent = Intent(ACTION_LIVE_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LIVE_TS, now)
            putExtra(EXTRA_LIVE_WARNINGS_MASK, warningsMask)
            if (irisNorm != null) putExtra(EXTRA_LIVE_IRIS_NORM, irisNorm)
            if (eyeOpenMin != null) putExtra(EXTRA_LIVE_EYE_OPEN_MIN, eyeOpenMin)
            if (slouchScore != null) putExtra(EXTRA_LIVE_SLOUCH_SCORE, slouchScore.toFloat())
        }
        sendBroadcast(intent)
    }

    private fun handleWarningState(warnings: Set<WarningState>) {
        val mainExecutor = ContextCompat.getMainExecutor(this)
        
        mainExecutor.execute {
            // 處理距離過近 (全螢幕遮罩)
            if (warnings.contains(WarningState.TOO_CLOSE)) {
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
            if (warnings.contains(WarningState.SQUINTING)) postureText.add("不要瞇眼")
            if (warnings.contains(WarningState.SLOUCHING)) postureText.add("請坐端正")

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
        val currentTime = SystemClock.uptimeMillis()
        if (priority || (currentTime - lastTtsTimestamp > VOICE_ALERT_COOLDOWN_MS)) {
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
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
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
        private const val TAG = "EyeHealthService"
        private const val CHANNEL_ID = "WarningChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_INTERVAL_MS = 1000L
        private const val VOICE_ALERT_COOLDOWN_MS = 15000L
        private const val VIBRATION_COOLDOWN_MS = 3000L

        const val PREF_LIVE_TS = "live_ts"
        const val PREF_LIVE_IRIS_NORM = "live_iris_norm"
        const val PREF_LIVE_EYE_OPEN_MIN = "live_eye_open_min"
        const val PREF_LIVE_SLOUCH_SCORE = "live_slouch_score"
        const val PREF_LIVE_WARNINGS_MASK = "live_warnings_mask"

        const val EXTRA_LIVE_TS = "ts"
        const val EXTRA_LIVE_IRIS_NORM = "irisNorm"
        const val EXTRA_LIVE_EYE_OPEN_MIN = "eyeOpenMin"
        const val EXTRA_LIVE_SLOUCH_SCORE = "slouchScore"
        const val EXTRA_LIVE_WARNINGS_MASK = "warningsMask"
    }
}
