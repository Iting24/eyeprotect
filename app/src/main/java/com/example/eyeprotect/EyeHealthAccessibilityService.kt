package com.example.eyeprotect

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.vision.face.FaceDetector
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * An accessibility service to monitor user's posture and eye health.
 *
 * NOTE: This service requires the user to grant both Accessibility and Camera permissions.
 * It also needs to be declared in the AndroidManifest.xml and have a configuration file
 * in `res/xml`.
 */
@AndroidEntryPoint
@Suppress("LeakingThis")
class EyeHealthAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener, LifecycleOwner {

    @Inject
    lateinit var faceDetector: FaceDetector
    @Inject
    lateinit var tts: TextToSpeech

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private val postureDetector = PostureAndEyeDetector()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastDetectionTimestamp = 0L
    private var lastTtsTimestamp = 0L
    private var isTooCloseOverlayShown = false

    // For managing the camera lifecycle within the service
    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val thresholdUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra("irisDistance")) {
                postureDetector.irisDistanceThreshold = intent.getFloatExtra("irisDistance", postureDetector.irisDistanceThreshold)
            }
            if (intent.hasExtra("slouchingAngle")) {
                postureDetector.slouchingAngleThresholdDegrees = intent.getDoubleExtra("slouchingAngle", postureDetector.slouchingAngleThresholdDegrees)
            }
            if (intent.hasExtra("ear")) {
                postureDetector.earThreshold = intent.getFloatExtra("ear", postureDetector.earThreshold)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        LocalBroadcastManager.getInstance(this).registerReceiver(thresholdUpdateReceiver, IntentFilter(ACTION_UPDATE_THRESHOLDS))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected.")

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts.setOnUtteranceProgressListener(null)

        startCamera()
        createNotificationChannel()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, this::analyzeImage)
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
                Log.d(TAG, "Camera started successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        // Throttle analysis to once every 500ms
        val currentTimestamp = SystemClock.uptimeMillis()
        if (currentTimestamp - lastDetectionTimestamp < DETECTION_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastDetectionTimestamp = currentTimestamp

        val image = imageProxy.image
        if (image != null) {
            val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(inputImage)
                .addOnSuccessListener {
                    // TODO: Implement what to do with the face detection results
                }
                .addOnFailureListener {
                    Log.e(TAG, "Face detection failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleWarningState(warnings: Set<WarningState>) {
        // Handle "Too Close" warning
        if (warnings.contains(WarningState.TOO_CLOSE)) {
            if (!isTooCloseOverlayShown) {
                showScreenOverlay()
                isTooCloseOverlayShown = true
            }

            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - lastTtsTimestamp > VOICE_ALERT_COOLDOWN_MS) {
                speakWarning("Please keep your distance")
                lastTtsTimestamp = currentTime
            }
        } else {
            if (isTooCloseOverlayShown) {
                hideScreenOverlay()
                isTooCloseOverlayShown = false
            }
        }

        val postureWarnings = mutableListOf<String>()
        if (warnings.contains(WarningState.SLOUCHING)) {
            postureWarnings.add("slouching")
        }
        if (warnings.contains(WarningState.SQUINTING)) {
            postureWarnings.add("squinting")
        }

        if (postureWarnings.isNotEmpty()) {
            showWarningNotification("Posture alert: ${postureWarnings.joinToString(" and ")}")
        }
    }

    private fun showScreenOverlay() {
        if (overlayView != null) return

        overlayView = View(this)
        overlayView?.setBackgroundColor(Color.parseColor("#AA000000"))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun hideScreenOverlay() {
        if (overlayView == null) return
        try {
            windowManager.removeView(overlayView)
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        }
    }

    private fun speakWarning(text: String) {
        if (tts.isSpeaking) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun showWarningNotification(warningText: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper icon
            .setContentTitle("Eye Health Warning")
            .setContentText(warningText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported.")
            }
        } else {
            Log.e(TAG, "TTS initialization failed.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this implementation
    }

    override fun onInterrupt() {
        // Service interrupted
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(thresholdUpdateReceiver)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
        faceDetector.close()
        tts.stop()
        tts.shutdown()
        hideScreenOverlay()
        Log.d(TAG, "Accessibility Service destroyed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Eye Health Warnings"
            val descriptionText = "Notifications for posture and eye strain"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_UPDATE_THRESHOLDS = "com.example.eyeprotect.UPDATE_THRESHOLDS"
        const val ACTION_WARNING = "com.example.eyeprotect.WARNING"
        private const val TAG = "EyeHealthService"
        private const val CHANNEL_ID = "EyeHealthWarningChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_INTERVAL_MS = 500L
        private const val VOICE_ALERT_COOLDOWN_MS = 10000L
    }
}
