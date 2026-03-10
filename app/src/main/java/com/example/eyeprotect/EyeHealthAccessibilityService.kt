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
import android.speech.tts.TextToSpeech
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
    private var isTooCloseOverlayShown = false

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
                detector.slouchingAngleThresholdDegrees =
                    intent.getFloatExtra("slouchAngleThreshold", detector.slouchingAngleThresholdDegrees.toFloat()).toDouble()
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
            detector.slouchingAngleThresholdDegrees =
                prefs.getFloat("slouch_angle_threshold", detector.slouchingAngleThresholdDegrees.toFloat()).toDouble()
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

        val mediaImage = imageProxy.image ?: return
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // 並行處理人臉與姿勢偵測
        val faceTask = faceDetector.process(image)
        val poseTask = poseDetector.process(image)

        Tasks.whenAllComplete(faceTask, poseTask)
            .addOnSuccessListener {
                val faces = faceTask.result
                val pose = poseTask.result
                
                val warnings = detector.detectWarnings(
                    face = faces?.firstOrNull(),
                    pose = pose,
                    imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height,
                    imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width
                )
                
                handleWarningState(warnings)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleWarningState(warnings: Set<WarningState>) {
        val mainExecutor = ContextCompat.getMainExecutor(this)
        
        mainExecutor.execute {
            // 處理距離過近 (全螢幕遮罩)
            if (warnings.contains(WarningState.TOO_CLOSE)) {
                if (!isTooCloseOverlayShown) {
                    showScreenOverlay()
                    isTooCloseOverlayShown = true
                    speakWarning("請保持距離", priority = true)
                } else {
                    speakWarning("請保持距離")
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
                if (didSpeak) showWarningNotification(message)
            }
        }
    }

    private fun speakWarning(text: String, priority: Boolean = false): Boolean {
        val currentTime = SystemClock.uptimeMillis()
        if (priority || (currentTime - lastTtsTimestamp > VOICE_ALERT_COOLDOWN_MS)) {
            if (!tts.isSpeaking) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warning")
                lastTtsTimestamp = currentTime
                return true
            }
        }
        return false
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
        private const val TAG = "EyeHealthService"
        private const val CHANNEL_ID = "WarningChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_INTERVAL_MS = 1000L
        private const val VOICE_ALERT_COOLDOWN_MS = 15000L
    }
}
