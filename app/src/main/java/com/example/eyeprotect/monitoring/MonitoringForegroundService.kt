package com.example.eyeprotect.monitoring

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.eyeprotect.CalibrationPrefs
import com.example.eyeprotect.MainActivity
import com.example.eyeprotect.PostureAndEyeDetector
import com.example.eyeprotect.R
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringForegroundService : Service() {

    @Inject lateinit var repo: MonitoringRepository
    @Inject lateinit var faceDetector: FaceDetector
    @Inject lateinit var poseDetector: PoseDetector

    private var detectorManager: DetectorManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                startMonitoringIfNeeded()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        detectorManager?.stop()
        detectorManager = null
        repo.setRunning(false)
        LiveMonitoringStore.publishPaused(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoringIfNeeded() {
        if (detectorManager != null) return
        repo.setRunning(true)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!CalibrationPrefs.hasValidCalibration(prefs)) {
            Log.w(TAG, "Monitoring start blocked: missing or invalid calibration")
            stopSelf()
            return
        }
        val ruleDetector = PostureAndEyeDetector().apply {
            irisDistanceThreshold = prefs.getFloat(CalibrationPrefs.KEY_IRIS_THRESHOLD, irisDistanceThreshold)
            enableTooCloseWarning = true
            eyeOpenThreshold = prefs.getFloat(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD, eyeOpenThreshold)
            enableSquintWarning = true
            slouchingPostureRatioThreshold =
                prefs.getFloat(CalibrationPrefs.KEY_SLOUCH_THRESHOLD, slouchingPostureRatioThreshold.toFloat()).toDouble()
            enableSlouchWarning = true
        }

        detectorManager = DetectorManager(
            context = this,
            faceDetector = faceDetector,
            poseDetector = poseDetector,
            ruleDetector = ruleDetector
        ).also { manager ->
            manager.start(
                onMetrics = { metrics ->
                    repo.updateMetrics(metrics)
                    LiveMonitoringStore.publishMetrics(this, metrics)
                }
            )
        }
    }

    @SuppressLint("NotificationPermission")
    private fun startInForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eye_health)
            .setContentTitle("VisionGuard AI")
            .setContentText("距離、姿勢與躺姿監測中（前景服務）")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VisionGuard Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.eyeprotect.monitoring.START"
        const val ACTION_STOP = "com.example.eyeprotect.monitoring.STOP"

        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "visionguard_monitoring"
        private const val NOTIFICATION_ID = 1101

        private const val PREFS_NAME = "eyeprotect_prefs"
        fun start(context: Context): Boolean {
            if (!context.hasRequiredMonitoringPermissions()) {
                Log.w(TAG, "Monitoring start blocked: missing camera or notification permission")
                return false
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!CalibrationPrefs.hasValidCalibration(prefs)) {
                Log.w(TAG, "Monitoring start blocked: missing or invalid calibration")
                return false
            }
            val intent = Intent(context, MonitoringForegroundService::class.java).setAction(ACTION_START)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Unable to start monitoring foreground service", exception)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringForegroundService::class.java))
        }

        private fun Context.hasRequiredMonitoringPermissions(): Boolean {
            val hasCamera =
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasNotifications =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return hasCamera && hasNotifications
        }
    }
}
