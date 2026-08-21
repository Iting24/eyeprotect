package com.example.eyeprotect.monitoring

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
    @Inject lateinit var reportRepo: MonitoringReportRepository
    @Inject lateinit var faceDetector: FaceDetector
    @Inject lateinit var poseDetector: PoseDetector

    private var detectorManager: DetectorManager? = null
    private var sessionStartedAtEpochMs: Long = 0L

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
        persistSessionDuration()
        reportRepo.stopSession()
        scheduleRestartIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfNeeded()
        super.onTaskRemoved(rootIntent)
    }

    private fun startMonitoringIfNeeded() {
        if (detectorManager != null) return
        repo.setRunning(true)
        sessionStartedAtEpochMs = System.currentTimeMillis()
        LiveMonitoringStore.resetSessionSummary(this, sessionStartedAtEpochMs)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!CalibrationPrefs.hasCompleteCalibrationSet(prefs)) {
            Log.w(TAG, "Monitoring start blocked: missing or invalid calibration")
            setAutoRestartEnabled(this, false)
            stopSelf()
            return
        }
        val currentOrientation = CalibrationPrefs.currentDeviceOrientation(this)
        val initialThresholds = CalibrationPrefs.resolveThresholds(prefs, currentOrientation)
            ?: run {
                Log.w(TAG, "Monitoring start blocked: no thresholds resolved for current orientation")
                setAutoRestartEnabled(this, false)
                stopSelf()
                return
            }
        val ruleDetector = PostureAndEyeDetector().apply {
            irisDistanceThreshold = initialThresholds.irisThreshold
            enableTooCloseWarning = true
            eyeOpenThreshold = initialThresholds.eyeOpenThreshold
            enableSquintWarning = true
            slouchingPostureRatioThreshold = initialThresholds.slouchThreshold.toDouble()
            enableSlouchWarning = true
        }

        detectorManager = DetectorManager(
            context = this,
            faceDetector = faceDetector,
            poseDetector = poseDetector,
            thresholdsProvider = { orientation ->
                CalibrationPrefs.resolveThresholds(prefs, orientation)
            },
            ruleDetector = ruleDetector
        ).also { manager ->
            reportRepo.startSession()
            LiveMonitoringStore.publishStarting(this)
            manager.start(
                onMetrics = { metrics ->
                    repo.updateMetrics(metrics)
                    LiveMonitoringStore.publishMetrics(this, metrics)
                    reportRepo.recordMetrics(metrics)
                },
                onWarningActivated = { warningState ->
                    warningState.toMonitoringIssueType()?.let { issueType ->
                        reportRepo.recordReminder(issueType)
                    }
                },
                onWarningDeactivated = { }
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
        const val ACTION_RESTART = "com.example.eyeprotect.monitoring.RESTART"

        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "visionguard_monitoring"
        private const val NOTIFICATION_ID = 1101
        private const val RESTART_DELAY_MS = 1_500L

        private const val PREFS_NAME = "eyeprotect_prefs"
        private const val PREF_AUTO_RESTART_ENABLED = "monitoring_auto_restart_enabled"
        const val PREF_LAST_SESSION_STARTED_AT = "last_session_started_at"
        const val PREF_LAST_SESSION_DURATION_MS = "last_session_duration_ms"
        const val PREF_LAST_TOO_CLOSE_COUNT = "last_too_close_count"
        const val PREF_LAST_SQUINT_COUNT = "last_squint_count"
        const val PREF_LAST_SLOUCH_COUNT = "last_slouch_count"
        const val PREF_LAST_TOO_CLOSE_CORRECTION_COUNT = "last_too_close_correction_count"
        const val PREF_LAST_SQUINT_CORRECTION_COUNT = "last_squint_correction_count"
        const val PREF_LAST_SLOUCH_CORRECTION_COUNT = "last_slouch_correction_count"
        fun start(context: Context): Boolean {
            if (!context.hasRequiredMonitoringPermissions()) {
                Log.w(TAG, "Monitoring start blocked: missing camera or notification permission")
                return false
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!CalibrationPrefs.hasCompleteCalibrationSet(prefs)) {
                Log.w(TAG, "Monitoring start blocked: missing or invalid calibration")
                return false
            }
            setAutoRestartEnabled(context, true)
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
            setAutoRestartEnabled(context, false)
            context.stopService(Intent(context, MonitoringForegroundService::class.java))
        }

        fun shouldAutoRestart(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_RESTART_ENABLED, false)
        }

        private fun setAutoRestartEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTO_RESTART_ENABLED, enabled)
                .apply()
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

    private fun com.example.eyeprotect.WarningState.toMonitoringIssueType(): MonitoringIssueType? = when (this) {
        com.example.eyeprotect.WarningState.TOO_CLOSE -> MonitoringIssueType.TOO_CLOSE
        com.example.eyeprotect.WarningState.SQUINTING -> MonitoringIssueType.SQUINTING
        com.example.eyeprotect.WarningState.SLOUCHING -> MonitoringIssueType.SLOUCHING
        com.example.eyeprotect.WarningState.LYING -> MonitoringIssueType.LYING
    }

    private fun persistSessionDuration() {
        if (sessionStartedAtEpochMs <= 0L) return
        val durationMs = (System.currentTimeMillis() - sessionStartedAtEpochMs).coerceAtLeast(0L)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_SESSION_STARTED_AT, sessionStartedAtEpochMs)
            .putLong(PREF_LAST_SESSION_DURATION_MS, durationMs)
            .apply()
        sessionStartedAtEpochMs = 0L
    }

    private fun scheduleRestartIfNeeded() {
        if (!shouldAutoRestart(this)) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val restartIntent = Intent(this, MonitoringRestartReceiver::class.java).setAction(ACTION_RESTART)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
