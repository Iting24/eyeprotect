package com.example.eyeprotect

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.eyeprotect.monitoring.DetectorManager
import com.example.eyeprotect.monitoring.DeepNightLyingReminder
import com.example.eyeprotect.monitoring.LiveMonitoringStore
import com.example.eyeprotect.monitoring.MonitoringMetrics
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
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

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var detectorManager: DetectorManager? = null

    private var isMonitoringEnabled = true
    private var lastTtsTimestamp = 0L
    private var lastVibrationTimestamp = 0L
    private var lastLyingAlertTimestamp = 0L
    private var isTooCloseOverlayShown = false
    private var cachedChineseVoices: List<Voice> = emptyList()
    private var lastVoiceRefreshTimestamp = 0L

    private val thresholdUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            detectorManager?.setThresholds(
                irisDistance = intent.getFloatExtraOrNull("irisDistance"),
                eyeOpenThreshold = intent.getFloatExtraOrNull("eyeOpenThreshold"),
                slouchRatioThreshold = intent.getFloatExtraOrNull("slouchAngleThreshold")?.toDouble()
            )
        }
    }

    private val monitoringToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET_MONITORING) return
            val enabled = intent.getBooleanExtra(EXTRA_MONITORING_ENABLED, true)
            prefs.edit().putBoolean(PREF_MONITORING_ENABLED, enabled).apply()
            applyMonitoringState(enabled)
        }
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        isMonitoringEnabled = prefs.getBoolean(PREF_MONITORING_ENABLED, true)

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
        tts.language = Locale.TRADITIONAL_CHINESE
        applyMonitoringState(isMonitoringEnabled)
    }

    private fun applyMonitoringState(enabled: Boolean) {
        isMonitoringEnabled = enabled
        if (enabled) {
            startMonitoringIfNeeded()
        } else {
            stopMonitoring()
            publishMonitoringPaused()
        }
    }

    private fun startMonitoringIfNeeded() {
        if (detectorManager != null) return

        detectorManager = DetectorManager(
            context = this,
            faceDetector = faceDetector,
            poseDetector = poseDetector
        ).apply {
            setThresholds(
                irisDistance = prefs.getFloatOrNull(KEY_IRIS_THRESHOLD),
                eyeOpenThreshold = prefs.getFloatOrNull(KEY_EYE_OPEN_THRESHOLD),
                slouchRatioThreshold = prefs.getFloatOrNull(KEY_SLOUCH_THRESHOLD)?.toDouble()
            )
            start(
                onMetrics = { publishLiveMetrics(it) },
                onWarnings = { handleWarningState(it) }
            )
        }
    }

    private fun stopMonitoring() {
        detectorManager?.stop()
        detectorManager = null
        ContextCompat.getMainExecutor(this).execute {
            hideScreenOverlay()
            isTooCloseOverlayShown = false
        }
    }

    private fun publishMonitoringPaused() {
        LiveMonitoringStore.publishPaused(this)
    }

    private fun publishLiveMetrics(metrics: MonitoringMetrics) {
        if (!isMonitoringEnabled) return
        LiveMonitoringStore.publishMetrics(this, metrics)

        if (prefs.getBoolean(PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, false)) {
            DeepNightLyingReminder.update(
                metrics = metrics,
                tts = tts
            )
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

        ContextCompat.getMainExecutor(this).execute {
            if (!isMonitoringEnabled) return@execute

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
            } else if (isTooCloseOverlayShown) {
                hideScreenOverlay()
                isTooCloseOverlayShown = false
            }

            val postureText = mutableListOf<String>()
            if (warnings.contains(WarningState.SQUINTING)) postureText.add("不要瞇眼")
            if (warnings.contains(WarningState.SLOUCHING)) postureText.add("請坐端正")
            if (warnings.contains(WarningState.LYING)) {
                val now = SystemClock.uptimeMillis()
                if (now - lastLyingAlertTimestamp >= LYING_ALERT_COOLDOWN_MS) {
                    lastLyingAlertTimestamp = now
                    postureText.add("不要躺著滑手機")
                }
            }

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
        if (!priority && currentTime - lastVibrationTimestamp <= VIBRATION_COOLDOWN_MS) return false

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
        if (!priority && currentTime - lastTtsTimestamp <= VOICE_ALERT_COOLDOWN_MS) return false

        if (priority && tts.isSpeaking) {
            tts.stop()
        }
        if (tts.isSpeaking) return false

        applyRandomVoiceAndTone(currentTime)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warning")
        lastTtsTimestamp = currentTime
        return true
    }

    private fun applyRandomVoiceAndTone(now: Long) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            (cachedChineseVoices.isEmpty() || now - lastVoiceRefreshTimestamp > 60_000L)
        ) {
            cachedChineseVoices = tts.voices
                ?.asSequence()
                ?.filter { it.locale.language == Locale.CHINESE.language }
                ?.filter { !it.isNetworkConnectionRequired }
                ?.toList()
                ?: emptyList()
            lastVoiceRefreshTimestamp = now
        }

        val style = Random.nextInt(3)
        val (pitch, rate, voiceHint) = when (style) {
            0 -> Triple(0.85f + Random.nextFloat() * 0.10f, 0.95f + Random.nextFloat() * 0.10f, "male")
            1 -> Triple(1.10f + Random.nextFloat() * 0.15f, 1.00f + Random.nextFloat() * 0.12f, "female")
            else -> Triple(0.95f + Random.nextFloat() * 0.15f, 0.95f + Random.nextFloat() * 0.15f, "neutral")
        }

        tts.setPitch(pitch)
        tts.setSpeechRate(rate)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || cachedChineseVoices.isEmpty()) return

        val candidates = when (voiceHint) {
            "male" -> cachedChineseVoices.filter { voiceNameSuggestsMale(it.name) }
            "female" -> cachedChineseVoices.filter { voiceNameSuggestsFemale(it.name) }
            else -> cachedChineseVoices
        }
        val pool = if (candidates.isNotEmpty()) candidates else cachedChineseVoices
        tts.voice = pool.random()
    }

    private fun voiceNameSuggestsMale(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return normalized.contains("male") || normalized.contains("man") || normalized.contains("m-") || normalized.contains("男")
    }

    private fun voiceNameSuggestsFemale(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return normalized.contains("female") || normalized.contains("woman") || normalized.contains("f-") || normalized.contains("女")
    }

    private fun showScreenOverlay() {
        if (!isMonitoringEnabled || overlayView != null) return
        overlayView = View(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay error", e)
        }
    }

    private fun hideScreenOverlay() {
        overlayView?.let {
            try {
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
            .setContentTitle("VisionGuard AI 提醒")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.CHINESE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopMonitoring()
        unregisterReceiver(thresholdUpdateReceiver)
        unregisterReceiver(monitoringToggleReceiver)
        tts.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VisionGuard Alerts", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun Intent.getFloatExtraOrNull(name: String): Float? =
        if (hasExtra(name)) getFloatExtra(name, 0f) else null

    private fun android.content.SharedPreferences.getFloatOrNull(name: String): Float? =
        if (contains(name)) getFloat(name, 0f) else null

    companion object {
        const val ACTION_UPDATE_THRESHOLDS = "com.example.eyeprotect.UPDATE_THRESHOLDS"
        const val ACTION_LIVE_METRICS = "com.example.eyeprotect.LIVE_METRICS"
        const val ACTION_SET_MONITORING = "com.example.eyeprotect.SET_MONITORING"

        private const val TAG = "EyeHealthService"
        private const val PREFS_NAME = "eyeprotect_prefs"
        private const val CHANNEL_ID = "WarningChannel"
        private const val NOTIFICATION_ID = 1
        private const val VOICE_ALERT_COOLDOWN_MS = 15_000L
        private const val VIBRATION_COOLDOWN_MS = 3_000L
        private const val LYING_ALERT_COOLDOWN_MS = 20_000L

        private const val KEY_IRIS_THRESHOLD = "iris_threshold"
        private const val KEY_EYE_OPEN_THRESHOLD = "eye_open_threshold"
        private const val KEY_SLOUCH_THRESHOLD = "slouch_angle_threshold"

        const val PREF_MONITORING_ENABLED = "monitoring_enabled"
        const val PREF_LIVE_TS = "live_ts"
        const val PREF_LIVE_IRIS_NORM = "live_iris_norm"
        const val PREF_LIVE_EYE_OPEN_MIN = "live_eye_open_min"
        const val PREF_LIVE_SLOUCH_SCORE = "live_slouch_score"
        const val PREF_LIVE_FACE_SEEN_UPTIME_MS = "live_face_seen_uptime_ms"
        const val PREF_LIVE_PITCH_DEG = "live_pitch_deg"
        const val PREF_LIVE_ROLL_DEG = "live_roll_deg"
        const val PREF_LIVE_TILT_DEG = "live_tilt_deg"
        const val PREF_LIVE_WARNINGS_MASK = "live_warnings_mask"
        const val PREF_LIVE_IS_CAMERA_FRAME = "live_is_camera_frame"
        const val PREF_LIVE_FACE_DETECTED = "live_face_detected"
        const val PREF_LIVE_POSE_DETECTED = "live_pose_detected"
        const val PREF_LIVE_FACE_ERROR = "live_face_error"
        const val PREF_LIVE_POSE_ERROR = "live_pose_error"

        const val EXTRA_LIVE_TS = "ts"
        const val EXTRA_LIVE_IRIS_NORM = "irisNorm"
        const val EXTRA_LIVE_EYE_OPEN_MIN = "eyeOpenMin"
        const val EXTRA_LIVE_SLOUCH_SCORE = "slouchScore"
        const val EXTRA_LIVE_FACE_SEEN_UPTIME_MS = "faceSeenUptimeMs"
        const val EXTRA_LIVE_PITCH_DEG = "pitchDeg"
        const val EXTRA_LIVE_ROLL_DEG = "rollDeg"
        const val EXTRA_LIVE_TILT_DEG = "tiltDeg"
        const val EXTRA_LIVE_WARNINGS_MASK = "warningsMask"
        const val EXTRA_LIVE_IS_CAMERA_FRAME = "isCameraFrame"
        const val EXTRA_LIVE_FACE_DETECTED = "faceDetected"
        const val EXTRA_LIVE_POSE_DETECTED = "poseDetected"
        const val EXTRA_LIVE_FACE_ERROR = "faceError"
        const val EXTRA_LIVE_POSE_ERROR = "poseError"
        const val EXTRA_MONITORING_ENABLED = "enabled"
    }
}
