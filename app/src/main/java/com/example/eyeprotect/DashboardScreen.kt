package com.example.eyeprotect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.eyeprotect.monitoring.MonitoringForegroundService
import com.example.eyeprotect.ui.theme.EyeDesignTokens
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HISTORY_MAX_POINTS = 120
private val DashboardContentMaxWidth = 348.dp
@Composable
private fun cardTitleTextColor(): Color {
    return EyeDesignTokens.colors.textPrimary
}

@Composable
private fun cardBodyTextColor(): Color {
    return EyeDesignTokens.colors.textSecondary
}

@Composable
private fun groupedSectionBackground(): Color {
    return EyeDesignTokens.colors.surfaceSubtle
}

@Composable
private fun groupedSectionBorder(): Color {
    return EyeDesignTokens.colors.borderSubtle
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    isServiceEnabled: Boolean,
    hasCameraPermission: Boolean,
    hasCalibrated: Boolean,
    onRequestPermission: (() -> Unit)? = null,
    onReCalibrate: (() -> Unit)? = null
) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = EyeDesignTokens.colors
    val spacing = EyeDesignTokens.spacing
    val prefs = remember { context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var monitoringEnabled by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, false))
    }
    var hasNotificationPermission by remember { mutableStateOf(context.hasPostNotificationsPermission()) }

    var liveTs by remember { mutableLongStateOf(prefs.getLong(EyeHealthAccessibilityService.PREF_LIVE_TS, 0L)) }
    var irisNorm by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_IRIS_NORM, Float.NaN)) }
    var eyeOpenMin by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_EYE_OPEN_MIN, Float.NaN)) }
    var slouchScore by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_SLOUCH_SCORE, Float.NaN)) }
    var faceSeenUptimeMs by remember { mutableLongStateOf(prefs.getLong(EyeHealthAccessibilityService.PREF_LIVE_FACE_SEEN_UPTIME_MS, 0L)) }
    var pitchDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_PITCH_DEG, Float.NaN)) }
    var rollDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_ROLL_DEG, Float.NaN)) }
    var tiltDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_TILT_DEG, Float.NaN)) }
    var warningsMask by remember { mutableIntStateOf(prefs.getInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, 0)) }
    var lastWasCameraFrame by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_IS_CAMERA_FRAME, false))
    }
    var faceDetected by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_DETECTED, false))
    }
    var faceMatchedActiveProfile by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_MATCHED, false))
    }
    var identityPaused by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_IDENTITY_PAUSED, false))
    }
    var poseDetected by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_DETECTED, false))
    }
    var faceError by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_FACE_ERROR, false))
    }
    var poseError by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_LIVE_POSE_ERROR, false))
    }

    val irisThreshold = prefs.getFloat(CalibrationPrefs.KEY_IRIS_THRESHOLD, Float.NaN)
    val eyeOpenThreshold = prefs.getFloat(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD, Float.NaN)
    val slouchThreshold = prefs.getFloat(CalibrationPrefs.KEY_SLOUCH_THRESHOLD, Float.NaN)

    val distanceHistory = remember { mutableStateListOf<Float>() }
    val eyeOpenHistory = remember { mutableStateListOf<Float>() }
    val postureHistory = remember { mutableStateListOf<Float>() }
    val lyingHistory = remember { mutableStateListOf<Float>() }
    var expandedMetric by remember { mutableStateOf<HistoryMetric?>(null) }
    var monitoringDetailsOpen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationPermission = context.hasPostNotificationsPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != EyeHealthAccessibilityService.ACTION_LIVE_METRICS) return
                val incomingWarningsMask =
                    intent.getIntExtra(EyeHealthAccessibilityService.EXTRA_LIVE_WARNINGS_MASK, warningsMask)
                liveTs = intent.getLongExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TS, liveTs)
                warningsMask = incomingWarningsMask
                lastWasCameraFrame = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IS_CAMERA_FRAME, lastWasCameraFrame)
                faceDetected = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_DETECTED, faceDetected)
                faceMatchedActiveProfile =
                    intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_MATCHED, faceMatchedActiveProfile)
                identityPaused =
                    intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IDENTITY_PAUSED, identityPaused)
                poseDetected = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_DETECTED, poseDetected)
                faceError = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_ERROR, faceError)
                poseError = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_POSE_ERROR, poseError)
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IRIS_NORM)) {
                    irisNorm = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IRIS_NORM, irisNorm)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN)) {
                    eyeOpenMin = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN, eyeOpenMin)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE)) {
                    slouchScore = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE, slouchScore)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_PITCH_DEG)) {
                    pitchDeg = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_PITCH_DEG, pitchDeg)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_ROLL_DEG)) {
                    rollDeg = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_ROLL_DEG, rollDeg)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TILT_DEG)) {
                    tiltDeg = intent.getFloatExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TILT_DEG, tiltDeg)
                }
                if (intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_SEEN_UPTIME_MS)) {
                    faceSeenUptimeMs =
                        intent.getLongExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_SEEN_UPTIME_MS, faceSeenUptimeMs)
                }

                if (lastWasCameraFrame) {
                    pushHistory(distanceHistory, distancePercent(irisNorm, irisThreshold)?.toFloat() ?: Float.NaN)
                    pushHistory(eyeOpenHistory, probabilityPercent(eyeOpenMin)?.toFloat() ?: Float.NaN)
                    pushHistory(postureHistory, ratioPercent(slouchScore, slouchThreshold)?.toFloat() ?: Float.NaN)
                }
                pushHistory(lyingHistory, horizontalPercentFromTilt(tiltDeg)?.toFloat() ?: Float.NaN)

                val paused = !monitoringEnabled && warningsMask == 0
                if (paused) {
                    distanceHistory.clear()
                    eyeOpenHistory.clear()
                    postureHistory.clear()
                    lyingHistory.clear()
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(EyeHealthAccessibilityService.ACTION_LIVE_METRICS),
            RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onRequestPermission?.invoke()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    val monitoringReady = hasCameraPermission && hasCalibrated && hasNotificationPermission

    LaunchedEffect(monitoringEnabled, monitoringReady, context) {
        if (monitoringEnabled && monitoringReady) {
            if (!MonitoringForegroundService.start(context)) {
                monitoringEnabled = false
                prefs.edit().putBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, false).apply()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (isDarkTheme) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF111111),
                            Color(0xFF111111)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFC8FFF5),
                            Color(0xFFC8FFF5)
                        )
                    )
                }
            )
    ) {
        val alertsReady = monitoringReady && isServiceEnabled
        val setMonitoringEnabled: (Boolean) -> Unit = { enabled ->
            if (enabled && !hasNotificationPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                monitoringEnabled = enabled
                prefs.edit().putBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, enabled).apply()
                if (enabled && monitoringReady) {
                    if (!MonitoringForegroundService.start(context)) {
                        monitoringEnabled = false
                        prefs.edit().putBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, false).apply()
                    }
                } else if (!enabled) {
                    MonitoringForegroundService.stop(context)
                }
                val intent = Intent(EyeHealthAccessibilityService.ACTION_SET_MONITORING).apply {
                    setPackage(context.packageName)
                    putExtra(EyeHealthAccessibilityService.EXTRA_MONITORING_ENABLED, enabled)
                }
                context.sendBroadcast(intent)
            }
        }
        val requestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                hasNotificationPermission = true
            }
        }

        var heroNowUptime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                heroNowUptime = SystemClock.uptimeMillis()
                delay(1000)
            }
        }
        val heroState = remember(
            monitoringReady,
            monitoringEnabled,
            alertsReady,
            warningsMask,
            liveTs,
            faceSeenUptimeMs,
            faceDetected,
            faceMatchedActiveProfile,
            identityPaused,
            poseDetected,
            faceError,
            poseError,
            heroNowUptime
        ) {
            buildDashboardHeroState(
                monitoringReady = monitoringReady,
                monitoringEnabled = monitoringEnabled,
                alertsReady = alertsReady,
                warningsMask = warningsMask,
                liveTsUptimeMs = liveTs,
                faceSeenUptimeMs = faceSeenUptimeMs,
                faceDetected = faceDetected,
                faceMatchedActiveProfile = faceMatchedActiveProfile,
                identityPaused = identityPaused,
                poseDetected = poseDetected,
                faceError = faceError,
                poseError = poseError,
                nowUptimeMs = heroNowUptime
            )
        }

        val dashboardListState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            state = dashboardListState,
            contentPadding = PaddingValues(top = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFC8FFF5))
                        .padding(horizontal = spacing.lg)
                        .padding(bottom = spacing.xs)
                ) {
                    DashboardHeader(
                        heroState = heroState,
                        monitoringEnabled = monitoringEnabled,
                        monitoringReady = monitoringReady,
                        toggleEnabled = monitoringReady,
                        onToggleMonitoring = setMonitoringEnabled
                    )
                }
            }

            item {
                MascotCoachCard(
                    monitoringReady = monitoringReady,
                    monitoringEnabled = monitoringEnabled,
                    alertsReady = alertsReady,
                    warningsMask = warningsMask,
                    faceDetected = faceDetected,
                    identityPaused = identityPaused,
                    faceError = faceError,
                    poseError = poseError
                )
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF55D7CB))
                        .padding(horizontal = spacing.lg)
                        .padding(top = spacing.sm, bottom = spacing.sm)
                ) {
                    ScoreStreakCard(
                        heroState = heroState,
                        monitoringReady = monitoringReady
                    )
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF55D7CB))
                        .padding(horizontal = spacing.lg)
                        .padding(bottom = spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.widthIn(max = DashboardContentMaxWidth)) {
                        SetupCard(
                            hasCameraPermission = hasCameraPermission,
                            hasCalibrated = hasCalibrated,
                            hasNotificationPermission = hasNotificationPermission,
                            isServiceEnabled = isServiceEnabled,
                            monitoringEnabled = if (monitoringReady) monitoringEnabled else false,
                            onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onRequestNotifications = requestNotificationPermission,
                            onOpenCalibration = onReCalibrate,
                            onOpenAccessibilitySettings = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            onEnableMonitoring = if (monitoringReady) ({ setMonitoringEnabled(true) }) else null
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF55D7CB))
                        .padding(horizontal = spacing.lg)
                        .padding(bottom = spacing.sm)
                ) {
                    MetricOverviewCard(
                        irisNorm = irisNorm,
                        eyeOpenMin = eyeOpenMin,
                        postureRatio = slouchScore,
                        tiltDeg = tiltDeg,
                        pitchDeg = pitchDeg,
                        rollDeg = rollDeg,
                        warningsMask = warningsMask,
                        irisThreshold = irisThreshold,
                        eyeOpenThreshold = eyeOpenThreshold,
                        postureThreshold = slouchThreshold,
                        faceDetected = faceDetected,
                        poseDetected = poseDetected,
                        faceError = faceError,
                        poseError = poseError,
                        distanceTrend = distanceHistory,
                        eyeTrend = eyeOpenHistory,
                        postureTrend = postureHistory,
                        lyingTrend = lyingHistory,
                        expandedMetric = expandedMetric,
                        onToggleMetric = { metric ->
                            expandedMetric = if (expandedMetric == metric) null else metric
                        }
                    )
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF55D7CB))
                        .padding(horizontal = spacing.lg)
                        .padding(bottom = spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.widthIn(max = DashboardContentMaxWidth)) {
                        ExpandableMonitoringStatusCard(
                            expanded = monitoringDetailsOpen,
                            onToggle = { monitoringDetailsOpen = !monitoringDetailsOpen },
                            monitoringEnabled = monitoringEnabled && monitoringReady,
                            alertsReady = alertsReady,
                            liveTsUptimeMs = liveTs,
                            faceSeenUptimeMs = faceSeenUptimeMs,
                            lastWasCameraFrame = lastWasCameraFrame,
                            faceDetected = faceDetected,
                            identityPaused = identityPaused,
                            poseDetected = poseDetected,
                            faceError = faceError,
                            poseError = poseError,
                            pitchDeg = pitchDeg,
                            rollDeg = rollDeg,
                            tiltDeg = tiltDeg
                        )
                    }
                }
            }

        }
    }
}

private enum class MascotMood {
    SETUP,
    OFFLINE,
    WARNING,
    LIMITED,
    ERROR,
    STEADY
}

private data class MascotDialogue(
    val mood: MascotMood,
    val badge: String,
    val headline: String,
    val tips: List<String>,
    val accent: Color,
    val body: Color,
    val eyeOffsetX: Float,
    val eyeOffsetY: Float = 0f
)

private data class DashboardHeroState(
    val score: Int,
    val headline: String,
    val subtitle: String,
    val ageSec: Int?,
    val faceAgeSec: Int?,
    val stale: Boolean,
    val warning: Boolean
)

private fun buildDashboardHeroState(
    monitoringReady: Boolean,
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    warningsMask: Int,
    liveTsUptimeMs: Long,
    faceSeenUptimeMs: Long,
    faceDetected: Boolean,
    faceMatchedActiveProfile: Boolean,
    identityPaused: Boolean,
    poseDetected: Boolean,
    faceError: Boolean,
    poseError: Boolean,
    nowUptimeMs: Long
): DashboardHeroState {
    val ageSec = if (liveTsUptimeMs > 0L) ((nowUptimeMs - liveTsUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val faceAgeSec =
        if (faceSeenUptimeMs > 0L) ((nowUptimeMs - faceSeenUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val stale = monitoringEnabled && ageSec != null && ageSec >= 6
    val warningCount = listOf(1, 2, 4, 8).count { warningsMask and it != 0 }
    val score = when {
        !monitoringReady -> 0
        !monitoringEnabled -> 20
        stale -> 35
        else -> (100 - warningCount * 18 - if (!alertsReady) 8 else 0).coerceIn(15, 100)
    }
    val headline = when {
        !monitoringReady -> "先完成設定"
        !monitoringEnabled -> "監測已暫停"
        identityPaused -> "偵測到其他人"
        faceError || poseError -> "偵測器回報錯誤"
        stale -> "資料可能中斷"
        !faceDetected -> "等待臉部入鏡"
        warningCount > 0 -> "需要注意"
        !alertsReady -> "正在收集，提醒受限"
        else -> "狀態穩定"
    }
    val subtitle = when {
        !monitoringReady -> "相機權限與個人校正完成後，首頁才會開始顯示可靠數據。"
        !monitoringEnabled -> "開啟監測後，前景服務會開始收集即時資料。"
        identityPaused -> "目前鏡頭前不是已選擇的人臉，提醒與監測已暫停，等本人回來會自動恢復。"
        faceError || poseError -> detectorErrorText(faceError, poseError)
        stale -> "最近沒有收到新數據，請檢查前景服務、相機或省電限制。"
        warningCount > 0 -> activeWarningText(warningsMask)
        !faceDetected -> "請讓臉部進入前鏡頭畫面，距離與睜眼指標才會更新。"
        !faceMatchedActiveProfile -> "請切換到正確的人臉設定，或讓目前選中的使用者回到鏡頭前。"
        !poseDetected -> "臉部資料正常；姿勢指標需要肩膀或耳朵一起入鏡。"
        !alertsReady -> "數據仍會更新；開啟無障礙服務後，跨 app 提醒才會完整。"
        else -> "即時資料更新正常，暫時沒有警告。"
    }
    return DashboardHeroState(
        score = score,
        headline = headline,
        subtitle = subtitle,
        ageSec = ageSec,
        faceAgeSec = faceAgeSec,
        stale = stale,
        warning = warningCount > 0 || stale
    )
}

@Composable
private fun MascotCoachCard(
    monitoringReady: Boolean,
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    warningsMask: Int,
    faceDetected: Boolean,
    identityPaused: Boolean,
    faceError: Boolean,
    poseError: Boolean
) {
    val dialogue = remember(
        monitoringReady,
        monitoringEnabled,
        alertsReady,
        warningsMask,
        faceDetected,
        identityPaused,
        faceError,
        poseError
    ) {
        buildMascotDialogue(
            monitoringReady = monitoringReady,
            monitoringEnabled = monitoringEnabled,
            alertsReady = alertsReady,
            warningsMask = warningsMask,
            faceDetected = faceDetected,
            identityPaused = identityPaused,
            faceError = faceError,
            poseError = poseError
        )
    }
    val bubbleMessages = remember(dialogue) { listOf(dialogue.headline) + dialogue.tips }
    var messageIndex by remember(dialogue) { mutableIntStateOf(0) }
    val activeMessage = bubbleMessages[messageIndex % bubbleMessages.size]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(328.dp)
    ) {
        MascotRoomBackdrop(modifier = Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpeechBubble(
                text = activeMessage,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 264.dp)
            )
            EyeMascotAvatar(
                modifier = Modifier.clickable { messageIndex = (messageIndex + 1) % bubbleMessages.size },
                dialogue = dialogue
            )
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

@Composable
private fun MascotRoomBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val wall = Color(0xFFC8FFF5)
        val floor = Color(0xFF55D7CB)
        drawRect(color = wall)
        val floorPath = Path().apply {
            moveTo(0f, size.height * 0.64f)
            quadraticBezierTo(size.width * 0.50f, size.height * 0.56f, size.width, size.height * 0.64f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(floorPath, floor)

        val windowLeft = size.width * 0.37f
        val windowTop = size.height * 0.11f
        val windowWidth = size.width * 0.26f
        val windowHeight = size.height * 0.30f
        drawRoundRect(
            color = Color(0xFFD7A156),
            topLeft = Offset(windowLeft - 8f, windowTop - 8f),
            size = Size(windowWidth + 16f, windowHeight + 16f),
            cornerRadius = CornerRadius(5f, 5f)
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFEFF7FA), Color(0xFFFFF3D7)),
                startY = windowTop,
                endY = windowTop + windowHeight
            ),
            topLeft = Offset(windowLeft, windowTop),
            size = Size(windowWidth, windowHeight),
            cornerRadius = CornerRadius(2f, 2f)
        )
        drawLine(
            color = Color(0xFFD7A156),
            start = Offset(windowLeft + windowWidth / 2f, windowTop),
            end = Offset(windowLeft + windowWidth / 2f, windowTop + windowHeight),
            strokeWidth = 7f
        )
        drawLine(
            color = Color(0xFFD7A156),
            start = Offset(windowLeft, windowTop),
            end = Offset(windowLeft + windowWidth, windowTop),
            strokeWidth = 7f
        )

        val shelfY = size.height * 0.58f
        val shelfX = size.width * 0.05f
        drawRoundRect(Color(0xFFC58F45), Offset(shelfX, shelfY), Size(size.width * 0.18f, 10f), CornerRadius(4f, 4f))
        drawRoundRect(Color(0xFFC58F45), Offset(shelfX + 8f, shelfY), Size(8f, size.height * 0.17f), CornerRadius(4f, 4f))
        drawRoundRect(Color(0xFFC58F45), Offset(shelfX + size.width * 0.16f, shelfY), Size(8f, size.height * 0.17f), CornerRadius(4f, 4f))
        drawRoundRect(Color(0xFFE4872F), Offset(shelfX + 28f, shelfY - 42f), Size(34f, 34f), CornerRadius(3f, 3f))
        drawOval(Color(0xFF3C8F35), Offset(shelfX + 18f, shelfY - 74f), Size(24f, 48f))
        drawOval(Color(0xFF4FAA41), Offset(shelfX + 48f, shelfY - 74f), Size(24f, 48f))
        drawRoundRect(Color(0xFF77B861), Offset(shelfX + 26f, shelfY + 34f), Size(52f, 8f), CornerRadius(3f, 3f))
        drawRoundRect(Color(0xFFEAC96E), Offset(shelfX + 32f, shelfY + 24f), Size(42f, 8f), CornerRadius(3f, 3f))

        val standX = size.width * 0.82f
        val standY = size.height * 0.61f
        drawLine(Color(0xFFC58F45), Offset(standX + 18f, standY + 26f), Offset(standX + 8f, standY + 90f), 8f, cap = StrokeCap.Round)
        drawLine(Color(0xFFC58F45), Offset(standX + 52f, standY + 26f), Offset(standX + 64f, standY + 90f), 8f, cap = StrokeCap.Round)
        drawRoundRect(Color(0xFFC58F45), Offset(standX + 8f, standY + 20f), Size(60f, 12f), CornerRadius(8f, 8f))
        drawRoundRect(Color(0xFFE4872F), Offset(standX + 18f, standY - 18f), Size(40f, 42f), CornerRadius(4f, 4f))
        drawOval(Color(0xFF2F7E35), Offset(standX + 10f, standY - 88f), Size(20f, 78f))
        drawOval(Color(0xFF348D40), Offset(standX + 34f, standY - 94f), Size(20f, 86f))
        drawOval(Color(0xFF4EA74C), Offset(standX + 52f, standY - 70f), Size(18f, 60f))

        drawRoundRect(
            color = Color(0xFF80E0D9).copy(alpha = 0.85f),
            topLeft = Offset(size.width * 0.34f, size.height * 0.76f),
            size = Size(size.width * 0.32f, 20f),
            cornerRadius = CornerRadius(10f, 10f)
        )
    }
}

private fun buildMascotDialogue(
    monitoringReady: Boolean,
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    warningsMask: Int,
    faceDetected: Boolean,
    identityPaused: Boolean,
    faceError: Boolean,
    poseError: Boolean
): MascotDialogue {
    val warningCount = listOf(1, 2, 4, 8).count { warningsMask and it != 0 }
    return when {
        faceError || poseError -> MascotDialogue(
            mood = MascotMood.ERROR,
            badge = "需要排查",
            headline = "我現在看資料有點怪，先幫我檢查感測器。",
            tips = listOf(
                "可以先確認前鏡頭是不是被遮到，或是系統把相機權限收回了。",
                "如果你剛切過 app，回首頁一次通常能讓即時狀態重新同步。",
                "省電模式有時會中斷背景偵測，這種情況我會先提醒你看服務狀態。",
                "我舉起求救牌的時候，代表不是你做錯，是資料來源需要修一下。",
                "修好後我會把貼布收起來，回到正常陪你看姿勢。"
            ),
            accent = Color(0xFFFF8E72),
            body = Color(0xFFFFD8CB),
            eyeOffsetX = 0.16f,
            eyeOffsetY = -0.08f
        )
        !monitoringReady -> MascotDialogue(
            mood = MascotMood.SETUP,
            badge = "待設定",
            headline = "把相機、校正和通知補齊，我就能開始盯著你的用眼節奏。",
            tips = listOf(
                "先完成個人校正，後面的距離和姿勢提醒才會比較像你的真實習慣。",
                "通知權限不是裝飾，它會讓你更清楚知道監測服務是不是還活著。",
                "我手上的小工具代表還在整備，設定完成就會換成守護狀態。",
                "可以把這一步想成幫我戴上工作證：校正、相機、提醒都要到位。",
                "完成設定後，我會開始用比較精準的標準提醒你，不會亂喊。"
            ),
            accent = Color(0xFFFFB66E),
            body = Color(0xFFFFE0B7),
            eyeOffsetX = -0.18f
        )
        !monitoringEnabled -> MascotDialogue(
            mood = MascotMood.OFFLINE,
            badge = "待命中",
            headline = "我先在旁邊等你，打開監測後會立刻回報今天的狀態。",
            tips = listOf(
                "現在的首頁還能看流程，但不會持續長出新的即時數據。",
                "你一打開監測，我會先幫你盯距離、瞇眼、姿勢和躺姿這四件事。",
                "我戴睡帽抱枕頭的時候就是待命中，不會偷偷在背景監測。",
                "想重新開始就把監測打開，我會從最近一筆資料接著看。",
                "暫停也沒關係，等你準備好我再醒來。"
            ),
            accent = Color(0xFF8FA7FF),
            body = Color(0xFFDCE3FF),
            eyeOffsetX = 0f
        )
        identityPaused -> MascotDialogue(
            mood = MascotMood.OFFLINE,
            badge = "自動暫停",
            headline = "我看到現在拿手機的人不是目前選中的那位，所以先安靜待命。",
            tips = listOf(
                "這時候我不會再跳提醒，避免打擾臨時借手機的人。",
                "只要原本那位回到鏡頭前，我就會自動恢復監測，不用手動重開。",
                "如果現在真的換人使用，可以去設定把人臉槽位切到對應的人。",
                "你可以把它想成我先辨認值班對象，再決定要不要開始碎念。",
                "等我再次看到正確的人臉，才會把距離、姿勢和瞇眼提醒接回來。"
            ),
            accent = Color(0xFF74C2A8),
            body = Color(0xFFDDF5EA),
            eyeOffsetX = -0.1f
        )
        warningCount > 0 -> MascotDialogue(
            mood = MascotMood.WARNING,
            badge = "立刻調整",
            headline = activeWarningText(warningsMask),
            tips = listOf(
                "你現在只要先修正最明顯的一項，我的整體分數就會很快回升。",
                "如果是距離或瞇眼，先把手機往外推一點，通常是最快的緩解方式。",
                "如果是姿勢或躺姿，試著把肩膀打開、脖子拉長，我會再幫你看有沒有改善。",
                "看到紅色警示牌就先停三秒，調整比硬撐更快回穩。",
                "我不是在罵你，是在幫你把壞姿勢早一點攔下來。",
                "修正成功後，警示牌會消失，我會換回比較放鬆的表情。"
            ),
            accent = Color(0xFFFF7C70),
            body = Color(0xFFFFD5D1),
            eyeOffsetX = 0.22f,
            eyeOffsetY = -0.02f
        )
        !alertsReady -> MascotDialogue(
            mood = MascotMood.LIMITED,
            badge = "提醒受限",
            headline = "數據有在進來，但跨 app 的提醒能力還沒完全打開。",
            tips = listOf(
                "目前首頁可以更新，但你離開 app 之後，我的提醒存在感會弱很多。",
                "打開無障礙提醒後，這個角色才會真正像教練一樣主動出聲。",
                "我戴著眼鏡看得到首頁資料，但小鈴鐺還沒完全響起來。",
                "如果你常切到其他 app，建議先把提醒權限補齊。",
                "權限補完後，我才能在你滑太近或姿勢歪掉時主動叫你一下。"
            ),
            accent = Color(0xFFFFD166),
            body = Color(0xFFFFE9AF),
            eyeOffsetX = -0.10f
        )
        !faceDetected -> MascotDialogue(
            mood = MascotMood.STEADY,
            badge = "等你入鏡",
            headline = "我還沒看到你的臉，先把前鏡頭和你對齊，我才看得到距離與瞇眼。",
            tips = listOf(
                "姿勢資料可能還會動，但臉部沒有入鏡時，距離和睜眼指標會停住。",
                "把手機抬高一點或坐回中線，通常很快就會重新抓到你。",
                "你一入鏡，我會馬上回到正常監測節奏。",
                "我冒出問號時，就是在找你的臉，不是在裝神祕。",
                "光線太暗或鏡頭太偏，都可能讓我看不清楚。"
            ),
            accent = Color(0xFF7EC8FF),
            body = Color(0xFFDDF2FF),
            eyeOffsetX = -0.24f
        )
        else -> MascotDialogue(
            mood = MascotMood.STEADY,
            badge = "表現穩定",
            headline = "今天狀態不錯，我在旁邊幫你守著，先繼續保持。",
            tips = listOf(
                "如果你要長時間用機，記得偶爾眨眼和往遠處看，讓眼表先休息一下。",
                "你現在的數據很乾淨，正是最適合做成成就感回饋的時候。",
                "盾牌出現代表目前沒有明顯警告，我會繼續守著。",
                "維持這個距離和姿勢，眼睛會輕鬆很多。",
                "如果待會開始瞇眼或靠太近，我會立刻切換成提醒造型。"
            ),
            accent = Color(0xFF7ACB8C),
            body = Color(0xFFD7F3DE),
            eyeOffsetX = 0.10f
        )
    }
}

@Composable
private fun EyeMascotAvatar(
    dialogue: MascotDialogue,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mascot")
    val bobOffset by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mascotBob"
    )
    val blinkScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                1f at 0
                1f at 2350
                0.2f at 2460
                1f at 2580
                1f at 3200
            }
        ),
        label = "mascotBlink"
    )
    val outline = Color(0xFFA7C4E5)
    val limb = Color(0xFF0E73BC)
    val iris = Color(0xFF2F9EDB)
    val pupil = Color(0xFF22256E)
    val cheek = Color(0xFFFFC1CB)
    val smile = Color(0xFF2B201F)
    val sclera = when (dialogue.mood) {
        MascotMood.ERROR -> Color(0xFFFFF4E9)
        MascotMood.WARNING -> Color(0xFFFFFAF4)
        else -> Color.White
    }
    val armLift = when (dialogue.mood) {
        MascotMood.WARNING -> 0.34f
        MascotMood.ERROR -> -0.10f
        MascotMood.SETUP -> 0.16f
        MascotMood.LIMITED -> 0.08f
        else -> 0f
    }

    Box(
        modifier = modifier
            .size(width = 136.dp, height = 146.dp)
            .graphicsLayer {
                translationY = bobOffset
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bodyCenter = Offset(size.width * 0.52f, size.height * 0.43f)
            val bodyRadius = size.width * 0.28f
            val irisRadius = bodyRadius * 0.58f
            val pupilRadius = irisRadius * 0.40f
            val cheekRadius = bodyRadius * 0.18f
            val outlineStroke = size.width * 0.036f
            val limbStroke = size.width * 0.07f
            val handRadius = bodyRadius * 0.17f
            val blinkHeight = (irisRadius * 2f * blinkScale).coerceAtLeast(outlineStroke * 0.8f)

            val leftShoulder = Offset(bodyCenter.x - bodyRadius * 1.00f, bodyCenter.y + bodyRadius * 0.32f)
            val rightShoulder = Offset(bodyCenter.x + bodyRadius * 1.02f, bodyCenter.y + bodyRadius * 0.32f)
            val leftElbow = Offset(
                bodyCenter.x - bodyRadius * 1.56f,
                bodyCenter.y + bodyRadius * (0.82f - armLift)
            )
            val leftHand = Offset(
                bodyCenter.x - bodyRadius * 1.30f,
                bodyCenter.y + bodyRadius * (1.14f - armLift * 1.2f)
            )
            val rightElbow = Offset(
                bodyCenter.x + bodyRadius * 1.44f,
                bodyCenter.y + bodyRadius * (0.62f - armLift * 0.75f)
            )
            val rightHand = Offset(
                bodyCenter.x + bodyRadius * 1.16f,
                bodyCenter.y + bodyRadius * (0.96f - armLift)
            )

            val leftArm = Path().apply {
                moveTo(leftShoulder.x, leftShoulder.y)
                quadraticBezierTo(leftElbow.x, leftElbow.y, leftHand.x, leftHand.y)
            }
            val rightArm = Path().apply {
                moveTo(rightShoulder.x, rightShoulder.y)
                quadraticBezierTo(rightElbow.x, rightElbow.y, rightHand.x, rightHand.y)
            }

            drawCircle(
                color = dialogue.accent.copy(alpha = 0.12f),
                radius = bodyRadius * 1.72f,
                center = Offset(bodyCenter.x, bodyCenter.y + bodyRadius * 0.05f)
            )

            drawPath(leftArm, color = outline, style = Stroke(width = limbStroke, cap = StrokeCap.Round))
            drawPath(leftArm, color = Color.White, style = Stroke(width = limbStroke * 0.58f, cap = StrokeCap.Round))
            drawPath(rightArm, color = outline, style = Stroke(width = limbStroke, cap = StrokeCap.Round))
            drawPath(rightArm, color = Color.White, style = Stroke(width = limbStroke * 0.58f, cap = StrokeCap.Round))

            drawCircle(color = Color.White, radius = handRadius, center = leftHand)
            drawCircle(color = outline, radius = handRadius, center = leftHand, style = Stroke(width = outlineStroke))
            drawCircle(color = Color.White, radius = handRadius, center = rightHand)
            drawCircle(color = outline, radius = handRadius, center = rightHand, style = Stroke(width = outlineStroke))

            drawLine(
                color = limb,
                start = Offset(bodyCenter.x - bodyRadius * 0.16f, bodyCenter.y + bodyRadius * 1.04f),
                end = Offset(bodyCenter.x - bodyRadius * 0.22f, bodyCenter.y + bodyRadius * 1.76f),
                strokeWidth = outlineStroke * 1.3f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = limb,
                start = Offset(bodyCenter.x + bodyRadius * 0.18f, bodyCenter.y + bodyRadius * 1.04f),
                end = Offset(bodyCenter.x + bodyRadius * 0.25f, bodyCenter.y + bodyRadius * 1.80f),
                strokeWidth = outlineStroke * 1.3f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = limb,
                start = Offset(bodyCenter.x - bodyRadius * 0.22f, bodyCenter.y + bodyRadius * 1.76f),
                end = Offset(bodyCenter.x - bodyRadius * 0.34f, bodyCenter.y + bodyRadius * 1.82f),
                strokeWidth = outlineStroke * 1.1f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = limb,
                start = Offset(bodyCenter.x + bodyRadius * 0.25f, bodyCenter.y + bodyRadius * 1.80f),
                end = Offset(bodyCenter.x + bodyRadius * 0.39f, bodyCenter.y + bodyRadius * 1.86f),
                strokeWidth = outlineStroke * 1.1f,
                cap = StrokeCap.Round
            )

            drawCircle(color = sclera, radius = bodyRadius, center = bodyCenter)
            drawCircle(color = outline, radius = bodyRadius, center = bodyCenter, style = Stroke(width = outlineStroke))

            drawOval(
                color = iris,
                topLeft = Offset(bodyCenter.x - irisRadius, bodyCenter.y - blinkHeight / 2f),
                size = Size(irisRadius * 2f, blinkHeight)
            )

            val pupilCenter = Offset(
                bodyCenter.x + dialogue.eyeOffsetX * irisRadius * 0.52f,
                bodyCenter.y + dialogue.eyeOffsetY * irisRadius * 0.30f
            )
            val pupilHeight = (pupilRadius * 2f * blinkScale).coerceAtLeast(outlineStroke * 0.7f)
            drawOval(
                color = pupil,
                topLeft = Offset(pupilCenter.x - pupilRadius, pupilCenter.y - pupilHeight / 2f),
                size = Size(pupilRadius * 2f, pupilHeight)
            )

            drawOval(
                color = dialogue.body.copy(alpha = 0.55f),
                topLeft = Offset(bodyCenter.x + irisRadius * 0.52f, bodyCenter.y - irisRadius * 0.96f),
                size = Size(irisRadius * 0.54f, blinkHeight * 0.64f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bodyCenter.x + irisRadius * 0.30f, bodyCenter.y - irisRadius * 0.50f),
                size = Size(irisRadius * 0.52f, blinkHeight * 0.28f),
                cornerRadius = CornerRadius(irisRadius * 0.10f, irisRadius * 0.10f)
            )
            drawCircle(
                color = Color.White,
                radius = irisRadius * 0.30f,
                center = Offset(bodyCenter.x - irisRadius * 0.26f, bodyCenter.y - irisRadius * 0.26f)
            )
            drawCircle(
                color = Color.White,
                radius = irisRadius * 0.14f,
                center = Offset(bodyCenter.x + irisRadius * 0.22f, bodyCenter.y + irisRadius * 0.14f)
            )

            drawOval(
                color = cheek,
                topLeft = Offset(bodyCenter.x - bodyRadius * 1.03f, bodyCenter.y + bodyRadius * 0.08f),
                size = Size(cheekRadius * 2.1f, cheekRadius * 1.55f)
            )
            drawOval(
                color = cheek,
                topLeft = Offset(bodyCenter.x + bodyRadius * 0.33f, bodyCenter.y + bodyRadius * 0.12f),
                size = Size(cheekRadius * 2.1f, cheekRadius * 1.55f)
            )

            when (dialogue.mood) {
                MascotMood.ERROR -> {
                    drawArc(
                        color = smile,
                        startAngle = 205f,
                        sweepAngle = 130f,
                        useCenter = false,
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.40f, bodyCenter.y + bodyRadius * 0.52f),
                        size = Size(bodyRadius * 0.78f, bodyRadius * 0.46f),
                        style = Stroke(width = outlineStroke * 0.95f, cap = StrokeCap.Round)
                    )
                }
                MascotMood.WARNING -> {
                    drawRoundRect(
                        color = smile,
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.28f, bodyCenter.y + bodyRadius * 0.44f),
                        size = Size(bodyRadius * 0.56f, bodyRadius * 0.28f),
                        cornerRadius = CornerRadius(bodyRadius * 0.18f, bodyRadius * 0.18f)
                    )
                    drawArc(
                        color = iris,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.22f, bodyCenter.y + bodyRadius * 0.48f),
                        size = Size(bodyRadius * 0.44f, bodyRadius * 0.18f),
                        style = Stroke(width = outlineStroke * 0.7f, cap = StrokeCap.Round)
                    )
                }
                else -> {
                    drawArc(
                        color = smile,
                        startAngle = 8f,
                        sweepAngle = 164f,
                        useCenter = false,
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.30f, bodyCenter.y + bodyRadius * 0.42f),
                        size = Size(bodyRadius * 0.60f, bodyRadius * 0.40f),
                        style = Stroke(width = outlineStroke * 0.92f, cap = StrokeCap.Round)
                    )
                }
            }

            if (dialogue.mood == MascotMood.STEADY || dialogue.mood == MascotMood.SETUP) {
                val sparklePath = Path().apply {
                    moveTo(bodyCenter.x - bodyRadius * 1.56f, bodyCenter.y - bodyRadius * 1.00f)
                    lineTo(bodyCenter.x - bodyRadius * 1.44f, bodyCenter.y - bodyRadius * 0.74f)
                    lineTo(bodyCenter.x - bodyRadius * 1.18f, bodyCenter.y - bodyRadius * 0.62f)
                    lineTo(bodyCenter.x - bodyRadius * 1.44f, bodyCenter.y - bodyRadius * 0.50f)
                    lineTo(bodyCenter.x - bodyRadius * 1.56f, bodyCenter.y - bodyRadius * 0.24f)
                    lineTo(bodyCenter.x - bodyRadius * 1.68f, bodyCenter.y - bodyRadius * 0.50f)
                    lineTo(bodyCenter.x - bodyRadius * 1.94f, bodyCenter.y - bodyRadius * 0.62f)
                    lineTo(bodyCenter.x - bodyRadius * 1.68f, bodyCenter.y - bodyRadius * 0.74f)
                    close()
                }
                drawPath(sparklePath, color = Color(0xFFFFC94F))
            }

            if (dialogue.mood == MascotMood.ERROR) {
                drawRoundRect(
                    color = Color(0xFFFFE0D2),
                    topLeft = Offset(bodyCenter.x - bodyRadius * 0.96f, bodyCenter.y - bodyRadius * 0.28f),
                    size = Size(bodyRadius * 0.34f, bodyRadius * 0.22f),
                    cornerRadius = CornerRadius(bodyRadius * 0.08f, bodyRadius * 0.08f)
                )
                drawLine(
                    color = Color.White,
                    start = Offset(bodyCenter.x - bodyRadius * 0.91f, bodyCenter.y - bodyRadius * 0.24f),
                    end = Offset(bodyCenter.x - bodyRadius * 0.69f, bodyCenter.y - bodyRadius * 0.07f),
                    strokeWidth = outlineStroke * 0.8f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(bodyCenter.x - bodyRadius * 0.69f, bodyCenter.y - bodyRadius * 0.24f),
                    end = Offset(bodyCenter.x - bodyRadius * 0.91f, bodyCenter.y - bodyRadius * 0.07f),
                    strokeWidth = outlineStroke * 0.8f,
                    cap = StrokeCap.Round
                )
            }

            when (dialogue.mood) {
                MascotMood.STEADY -> {
                    val shieldTop = Offset(bodyCenter.x - bodyRadius * 1.74f, bodyCenter.y - bodyRadius * 0.74f)
                    val shieldPath = Path().apply {
                        moveTo(shieldTop.x + bodyRadius * 0.42f, shieldTop.y)
                        quadraticBezierTo(shieldTop.x + bodyRadius * 0.12f, shieldTop.y + bodyRadius * 0.18f, shieldTop.x, shieldTop.y + bodyRadius * 0.10f)
                        lineTo(shieldTop.x + bodyRadius * 0.04f, shieldTop.y + bodyRadius * 0.88f)
                        quadraticBezierTo(shieldTop.x + bodyRadius * 0.12f, shieldTop.y + bodyRadius * 1.36f, shieldTop.x + bodyRadius * 0.42f, shieldTop.y + bodyRadius * 1.58f)
                        quadraticBezierTo(shieldTop.x + bodyRadius * 0.72f, shieldTop.y + bodyRadius * 1.36f, shieldTop.x + bodyRadius * 0.80f, shieldTop.y + bodyRadius * 0.88f)
                        lineTo(shieldTop.x + bodyRadius * 0.84f, shieldTop.y + bodyRadius * 0.10f)
                        quadraticBezierTo(shieldTop.x + bodyRadius * 0.62f, shieldTop.y + bodyRadius * 0.18f, shieldTop.x + bodyRadius * 0.42f, shieldTop.y)
                        close()
                    }
                    drawPath(shieldPath, Color(0xFF0E73BC))
                    drawPath(shieldPath, Color(0xFF49C7EE).copy(alpha = 0.30f))
                    drawPath(shieldPath, Color(0xFF9EE5FF).copy(alpha = 0.62f), style = Stroke(width = outlineStroke * 1.15f))
                    drawLine(
                        color = Color(0xFF7DDAF7),
                        start = Offset(shieldTop.x + bodyRadius * 0.42f, shieldTop.y + bodyRadius * 0.18f),
                        end = Offset(shieldTop.x + bodyRadius * 0.42f, shieldTop.y + bodyRadius * 1.32f),
                        strokeWidth = outlineStroke * 0.72f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(shieldTop.x + bodyRadius * 0.22f, shieldTop.y + bodyRadius * 0.78f),
                        end = Offset(shieldTop.x + bodyRadius * 0.38f, shieldTop.y + bodyRadius * 0.98f),
                        strokeWidth = outlineStroke * 1.7f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(shieldTop.x + bodyRadius * 0.38f, shieldTop.y + bodyRadius * 0.98f),
                        end = Offset(shieldTop.x + bodyRadius * 0.66f, shieldTop.y + bodyRadius * 0.50f),
                        strokeWidth = outlineStroke * 1.7f,
                        cap = StrokeCap.Round
                    )
                }
                MascotMood.SETUP -> {
                    val toolCenter = Offset(bodyCenter.x - bodyRadius * 1.64f, bodyCenter.y - bodyRadius * 0.78f)
                    drawRoundRect(
                        color = Color(0xFFFFC65A),
                        topLeft = Offset(toolCenter.x - bodyRadius * 0.16f, toolCenter.y - bodyRadius * 0.06f),
                        size = Size(bodyRadius * 0.32f, bodyRadius * 0.92f),
                        cornerRadius = CornerRadius(bodyRadius * 0.12f, bodyRadius * 0.12f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = bodyRadius * 0.20f,
                        center = Offset(toolCenter.x, toolCenter.y - bodyRadius * 0.06f)
                    )
                    drawCircle(
                        color = Color(0xFF0E73BC),
                        radius = bodyRadius * 0.20f,
                        center = Offset(toolCenter.x, toolCenter.y - bodyRadius * 0.06f),
                        style = Stroke(width = outlineStroke * 1.1f)
                    )
                    drawLine(
                        color = Color(0xFF0E73BC),
                        start = Offset(toolCenter.x - bodyRadius * 0.20f, toolCenter.y + bodyRadius * 0.42f),
                        end = Offset(toolCenter.x + bodyRadius * 0.20f, toolCenter.y + bodyRadius * 0.42f),
                        strokeWidth = outlineStroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF0E73BC),
                        start = Offset(toolCenter.x - bodyRadius * 0.18f, toolCenter.y + bodyRadius * 0.64f),
                        end = Offset(toolCenter.x + bodyRadius * 0.18f, toolCenter.y + bodyRadius * 0.64f),
                        strokeWidth = outlineStroke,
                        cap = StrokeCap.Round
                    )
                }
                MascotMood.OFFLINE -> {
                    val hat = Path().apply {
                        moveTo(bodyCenter.x - bodyRadius * 0.70f, bodyCenter.y - bodyRadius * 1.10f)
                        quadraticBezierTo(bodyCenter.x - bodyRadius * 0.30f, bodyCenter.y - bodyRadius * 1.92f, bodyCenter.x + bodyRadius * 0.55f, bodyCenter.y - bodyRadius * 1.14f)
                        close()
                    }
                    drawPath(hat, Color(0xFF0B5EA8))
                    drawLine(
                        color = Color(0xFF0B5EA8),
                        start = Offset(bodyCenter.x - bodyRadius * 0.44f, bodyCenter.y - bodyRadius * 1.12f),
                        end = Offset(bodyCenter.x + bodyRadius * 0.66f, bodyCenter.y - bodyRadius * 1.12f),
                        strokeWidth = outlineStroke * 1.3f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = Color(0xFF0B5EA8),
                        radius = bodyRadius * 0.16f,
                        center = Offset(bodyCenter.x - bodyRadius * 0.74f, bodyCenter.y - bodyRadius * 1.14f)
                    )
                    drawRoundRect(
                        color = Color(0xFFFFFF62),
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.82f, bodyCenter.y + bodyRadius * 1.00f),
                        size = Size(bodyRadius * 1.16f, bodyRadius * 0.48f),
                        cornerRadius = CornerRadius(bodyRadius * 0.18f, bodyRadius * 0.18f)
                    )
                    drawRoundRect(
                        color = Color(0xFFFFE889),
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.82f, bodyCenter.y + bodyRadius * 1.00f),
                        size = Size(bodyRadius * 1.16f, bodyRadius * 0.48f),
                        cornerRadius = CornerRadius(bodyRadius * 0.18f, bodyRadius * 0.18f),
                        style = Stroke(width = outlineStroke * 0.7f)
                    )
                    drawArc(
                        color = Color(0xFFFFF6A6),
                        startAngle = 210f,
                        sweepAngle = 92f,
                        useCenter = false,
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.70f, bodyCenter.y + bodyRadius * 1.07f),
                        size = Size(bodyRadius * 0.30f, bodyRadius * 0.24f),
                        style = Stroke(width = outlineStroke * 0.65f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0xFFFFF6A6),
                        startAngle = 236f,
                        sweepAngle = 88f,
                        useCenter = false,
                        topLeft = Offset(bodyCenter.x + bodyRadius * 0.08f, bodyCenter.y + bodyRadius * 1.07f),
                        size = Size(bodyRadius * 0.30f, bodyRadius * 0.24f),
                        style = Stroke(width = outlineStroke * 0.65f, cap = StrokeCap.Round)
                    )
                }
                MascotMood.WARNING -> {
                    val signTop = Offset(bodyCenter.x + bodyRadius * 0.98f, bodyCenter.y - bodyRadius * 1.28f)
                    drawRoundRect(
                        color = Color(0xFFFFD166),
                        topLeft = signTop,
                        size = Size(bodyRadius * 1.04f, bodyRadius * 0.62f),
                        cornerRadius = CornerRadius(bodyRadius * 0.12f, bodyRadius * 0.12f)
                    )
                    drawRoundRect(
                        color = Color(0xFF2B201F),
                        topLeft = signTop,
                        size = Size(bodyRadius * 1.04f, bodyRadius * 0.62f),
                        cornerRadius = CornerRadius(bodyRadius * 0.12f, bodyRadius * 0.12f),
                        style = Stroke(width = outlineStroke * 1.2f)
                    )
                    drawLine(
                        color = Color(0xFF2B201F),
                        start = Offset(signTop.x + bodyRadius * 0.52f, signTop.y + bodyRadius * 0.62f),
                        end = Offset(rightHand.x, rightHand.y - bodyRadius * 0.10f),
                        strokeWidth = outlineStroke * 1.2f,
                        cap = StrokeCap.Round
                    )
                    val warningTriangle = Path().apply {
                        moveTo(signTop.x + bodyRadius * 0.52f, signTop.y + bodyRadius * 0.12f)
                        lineTo(signTop.x + bodyRadius * 0.78f, signTop.y + bodyRadius * 0.50f)
                        lineTo(signTop.x + bodyRadius * 0.26f, signTop.y + bodyRadius * 0.50f)
                        close()
                    }
                    drawPath(warningTriangle, Color(0xFFFF8E72))
                    drawLine(
                        color = Color.White,
                        start = Offset(signTop.x + bodyRadius * 0.52f, signTop.y + bodyRadius * 0.24f),
                        end = Offset(signTop.x + bodyRadius * 0.52f, signTop.y + bodyRadius * 0.38f),
                        strokeWidth = outlineStroke * 0.95f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(Color.White, bodyRadius * 0.030f, Offset(signTop.x + bodyRadius * 0.52f, signTop.y + bodyRadius * 0.45f))
                }
                MascotMood.LIMITED -> {
                    val lensY = bodyCenter.y - bodyRadius * 0.20f
                    drawRoundRect(
                        color = Color(0xFF2B201F),
                        topLeft = Offset(bodyCenter.x - bodyRadius * 0.82f, lensY - bodyRadius * 0.32f),
                        size = Size(bodyRadius * 0.62f, bodyRadius * 0.46f),
                        cornerRadius = CornerRadius(bodyRadius * 0.14f, bodyRadius * 0.14f),
                        style = Stroke(width = outlineStroke * 1.15f)
                    )
                    drawRoundRect(
                        color = Color(0xFF2B201F),
                        topLeft = Offset(bodyCenter.x + bodyRadius * 0.20f, lensY - bodyRadius * 0.32f),
                        size = Size(bodyRadius * 0.62f, bodyRadius * 0.46f),
                        cornerRadius = CornerRadius(bodyRadius * 0.14f, bodyRadius * 0.14f),
                        style = Stroke(width = outlineStroke * 1.15f)
                    )
                    drawLine(
                        color = Color(0xFF2B201F),
                        start = Offset(bodyCenter.x - bodyRadius * 0.20f, lensY - bodyRadius * 0.08f),
                        end = Offset(bodyCenter.x + bodyRadius * 0.20f, lensY - bodyRadius * 0.08f),
                        strokeWidth = outlineStroke * 1.15f,
                        cap = StrokeCap.Round
                    )
                    val bellCenter = Offset(bodyCenter.x + bodyRadius * 1.42f, bodyCenter.y - bodyRadius * 0.70f)
                    drawLine(
                        color = Color(0xFFFFB84D),
                        start = Offset(rightHand.x + bodyRadius * 0.04f, rightHand.y - bodyRadius * 0.10f),
                        end = Offset(bellCenter.x, bellCenter.y + bodyRadius * 0.24f),
                        strokeWidth = outlineStroke * 0.78f,
                        cap = StrokeCap.Round
                    )
                    val bell = Path().apply {
                        moveTo(bellCenter.x - bodyRadius * 0.22f, bellCenter.y + bodyRadius * 0.18f)
                        quadraticBezierTo(bellCenter.x - bodyRadius * 0.18f, bellCenter.y - bodyRadius * 0.20f, bellCenter.x, bellCenter.y - bodyRadius * 0.22f)
                        quadraticBezierTo(bellCenter.x + bodyRadius * 0.18f, bellCenter.y - bodyRadius * 0.20f, bellCenter.x + bodyRadius * 0.22f, bellCenter.y + bodyRadius * 0.18f)
                        close()
                    }
                    drawPath(bell, Color(0xFFFFD166))
                    drawPath(bell, Color(0xFFFFB84D), style = Stroke(width = outlineStroke * 0.75f, cap = StrokeCap.Round))
                    drawLine(
                        color = Color(0xFFFFB84D),
                        start = Offset(bellCenter.x - bodyRadius * 0.26f, bellCenter.y + bodyRadius * 0.18f),
                        end = Offset(bellCenter.x + bodyRadius * 0.26f, bellCenter.y + bodyRadius * 0.18f),
                        strokeWidth = outlineStroke,
                        cap = StrokeCap.Round
                    )
                    drawCircle(Color(0xFFFFB84D), bodyRadius * 0.055f, Offset(bellCenter.x, bellCenter.y + bodyRadius * 0.28f))
                }
                MascotMood.ERROR -> {
                    drawRoundRect(
                        color = Color(0xFFFFD166),
                        topLeft = Offset(bodyCenter.x + bodyRadius * 1.00f, bodyCenter.y - bodyRadius * 1.18f),
                        size = Size(bodyRadius * 1.00f, bodyRadius * 0.52f),
                        cornerRadius = CornerRadius(bodyRadius * 0.10f, bodyRadius * 0.10f)
                    )
                    drawRoundRect(
                        color = Color(0xFF2B201F),
                        topLeft = Offset(bodyCenter.x + bodyRadius * 1.00f, bodyCenter.y - bodyRadius * 1.18f),
                        size = Size(bodyRadius * 1.00f, bodyRadius * 0.52f),
                        cornerRadius = CornerRadius(bodyRadius * 0.10f, bodyRadius * 0.10f),
                        style = Stroke(width = outlineStroke)
                    )
                    drawLine(
                        color = Color(0xFF2B201F),
                        start = Offset(bodyCenter.x + bodyRadius * 1.20f, bodyCenter.y - bodyRadius * 0.98f),
                        end = Offset(bodyCenter.x + bodyRadius * 1.80f, bodyCenter.y - bodyRadius * 0.98f),
                        strokeWidth = outlineStroke * 1.1f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF2B201F),
                        start = Offset(bodyCenter.x + bodyRadius * 1.18f, bodyCenter.y - bodyRadius * 0.66f),
                        end = Offset(bodyCenter.x + bodyRadius * 1.02f, bodyCenter.y + bodyRadius * 0.10f),
                        strokeWidth = outlineStroke * 1.2f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = EyeDesignTokens.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.92f))
                .border(BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.8f)), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                style = EyeDesignTokens.typography.bodySmall,
                color = cardTitleTextColor(),
                lineHeight = 19.sp
            )
        }
        Box(
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = 45f }
                .background(Color.White.copy(alpha = 0.92f))
                .border(BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.8f)))
        )
    }
}

@Composable
private fun ScoreStreakCard(
    heroState: DashboardHeroState,
    monitoringReady: Boolean
) {
    val progress = (heroState.score / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "score_progress"
    )
    val cardShape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = DashboardContentMaxWidth)
                .shadow(
                    elevation = 3.dp,
                    shape = cardShape,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.04f)
                )
                .clip(cardShape)
                .background(Color(0xFF43BFAF))
                .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)), cardShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScoreFlameIcon(
                modifier = Modifier.size(56.dp),
                active = monitoringReady
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "健康分數",
                    style = EyeDesignTokens.typography.bodyStrong,
                    color = Color.White,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (heroState.warning) MaterialTheme.colorScheme.error
                                    else Color(0xFFFFC83D)
                                )
                        )
                        Text(
                            text = if (monitoringReady) "${heroState.score} 分" else "待啟用",
                            modifier = Modifier.align(Alignment.Center),
                            fontSize = 13.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            color = cardTitleTextColor()
                        )
                }
            }
        }
    }
}

@Composable
private fun ScoreFlameIcon(
    modifier: Modifier = Modifier,
    active: Boolean
) {
    Canvas(modifier = modifier) {
        val outer = if (active) Color(0xFFFF9F2F) else Color(0xFFBFC3CA)
        val inner = if (active) Color(0xFFFFD858) else Color(0xFFE0E2E6)
        drawCircle(color = Color.White, radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
        val flame = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.13f)
            cubicTo(size.width * 0.35f, size.height * 0.31f, size.width * 0.20f, size.height * 0.45f, size.width * 0.24f, size.height * 0.68f)
            cubicTo(size.width * 0.28f, size.height * 0.88f, size.width * 0.44f, size.height * 0.96f, size.width * 0.58f, size.height * 0.89f)
            cubicTo(size.width * 0.76f, size.height * 0.80f, size.width * 0.82f, size.height * 0.63f, size.width * 0.76f, size.height * 0.48f)
            cubicTo(size.width * 0.70f, size.height * 0.34f, size.width * 0.58f, size.height * 0.32f, size.width * 0.50f, size.height * 0.13f)
            close()
        }
        drawPath(flame, outer)
        val smallFlame = Path().apply {
            moveTo(size.width * 0.54f, size.height * 0.43f)
            cubicTo(size.width * 0.43f, size.height * 0.55f, size.width * 0.38f, size.height * 0.65f, size.width * 0.42f, size.height * 0.76f)
            cubicTo(size.width * 0.48f, size.height * 0.89f, size.width * 0.66f, size.height * 0.84f, size.width * 0.68f, size.height * 0.68f)
            cubicTo(size.width * 0.70f, size.height * 0.57f, size.width * 0.62f, size.height * 0.53f, size.width * 0.54f, size.height * 0.43f)
            close()
        }
        drawPath(smallFlame, inner)
    }
}

@Composable
private fun DashboardSummaryCard(
    monitoringReady: Boolean,
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    warningsMask: Int,
    liveTsUptimeMs: Long,
    faceSeenUptimeMs: Long,
    faceDetected: Boolean,
    poseDetected: Boolean,
    faceError: Boolean,
    poseError: Boolean
) {
    val text = EyeDesignTokens.typography
    var nowUptime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowUptime = SystemClock.uptimeMillis()
            delay(1000)
        }
    }

    val ageSec = if (liveTsUptimeMs > 0L) ((nowUptime - liveTsUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val faceAgeSec = if (faceSeenUptimeMs > 0L) ((nowUptime - faceSeenUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val stale = monitoringEnabled && ageSec != null && ageSec >= 6
    val warningCount = listOf(1, 2, 4, 8).count { warningsMask and it != 0 }
    val score = when {
        !monitoringReady -> 0
        !monitoringEnabled -> 20
        stale -> 35
        else -> (100 - warningCount * 18 - if (!alertsReady) 8 else 0).coerceIn(15, 100)
    }

    val headline = when {
        !monitoringReady -> "先完成設定"
        !monitoringEnabled -> "監測已暫停"
        faceError || poseError -> "偵測器回報錯誤"
        stale -> "資料可能中斷"
        !faceDetected -> "等待臉部入鏡"
        warningCount > 0 -> "需要注意"
        !alertsReady -> "正在收集，提醒受限"
        else -> "狀態穩定"
    }
    val subtitle = when {
        !monitoringReady -> "相機權限與個人校正完成後，首頁才會開始顯示可靠數據。"
        !monitoringEnabled -> "開啟監測後，前景服務會開始收集即時資料。"
        faceError || poseError -> detectorErrorText(faceError, poseError)
        stale -> "最近沒有收到新數據，請檢查前景服務、相機或省電限制。"
        !faceDetected -> "請讓臉部進入前鏡頭畫面；看不到臉時，距離與睜眼指標會暫停更新。"
        warningCount > 0 -> activeWarningText(warningsMask)
        !poseDetected -> "臉部資料正常；姿勢指標需要肩膀/耳朵進入畫面才會更新。"
        !alertsReady -> "數據仍會更新；開啟無障礙服務後，跨 app 語音與遮罩提醒才會完整。"
        else -> "即時資料更新正常，暫時沒有警告。"
    }

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = text.sectionTitle,
                    color = cardTitleTextColor()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = text.bodySmall,
                    color = cardBodyTextColor(),
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (!monitoringReady) {
                    SetupSummaryLabels()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatePill(
                            text = when {
                                ageSec == null -> "資料 --"
                                ageSec <= 1 -> "剛剛更新"
                                else -> "${ageSec}s 前更新"
                            },
                            active = monitoringEnabled && !stale
                        )
                        StatePill(
                            text = when {
                                faceAgeSec == null -> "未見臉"
                                faceAgeSec <= 1 -> "剛偵測到臉"
                                else -> "${faceAgeSec}s 前見臉"
                            },
                            active = faceAgeSec != null && faceAgeSec <= 5
                        )
                    }
                }
            }

            ScoreRing(
                score = score,
                monitoringReady = monitoringReady,
                warning = warningCount > 0 || stale,
                modifier = Modifier
                    .widthIn(min = 92.dp)
                    .padding(start = 14.dp)
            )
        }
    }
}

@Composable
private fun SetupSummaryLabels() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(
                text = "資料",
                fontSize = 11.sp,
                color = cardBodyTextColor()
            )
            Text(
                text = "--",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = cardBodyTextColor()
            )
        }
        Column {
            Text(
                text = "校正",
                fontSize = 11.sp,
                color = cardBodyTextColor()
            )
            Text(
                text = "未見臉",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = cardBodyTextColor()
            )
        }
    }
}

@Composable
private fun MetricOverviewCard(
    irisNorm: Float,
    eyeOpenMin: Float,
    postureRatio: Float,
    tiltDeg: Float,
    pitchDeg: Float,
    rollDeg: Float,
    warningsMask: Int,
    irisThreshold: Float,
    eyeOpenThreshold: Float,
    postureThreshold: Float,
    faceDetected: Boolean,
    poseDetected: Boolean,
    faceError: Boolean,
    poseError: Boolean,
    distanceTrend: List<Float>,
    eyeTrend: List<Float>,
    postureTrend: List<Float>,
    lyingTrend: List<Float>,
    expandedMetric: HistoryMetric?,
    onToggleMetric: (HistoryMetric) -> Unit
) {
    val tooClose = warningsMask and 1 != 0
    val squinting = warningsMask and 2 != 0
    val slouching = warningsMask and 4 != 0
    val lying = warningsMask and 8 != 0
    val distancePct = distancePercent(irisNorm, irisThreshold)
    val eyePct = probabilityPercent(eyeOpenMin)
    val posturePct = ratioPercent(postureRatio, postureThreshold)
    val lyingPct = horizontalPercentFromTilt(tiltDeg)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = DashboardContentMaxWidth)
        ) {
            Text(
                text = "今日重點",
                style = EyeDesignTokens.typography.sectionTitle,
                color = cardTitleTextColor()
            )
            Spacer(modifier = Modifier.height(10.dp))

            SummaryMetricRow(
                metric = HistoryMetric.DISTANCE,
                title = "螢幕距離",
                value = formatPercent(distancePct),
                status = faceMetricStatus(
                    percent = distancePct,
                    warning = tooClose,
                    faceDetected = faceDetected,
                    faceError = faceError,
                    warningWhenHigh = true
                ),
                warning = tooClose,
                accent = Color(0xFF7BC8F6),
                expanded = expandedMetric == HistoryMetric.DISTANCE,
                onToggle = onToggleMetric
            ) {
                MetricDetail(
                    unit = "100% = 太近門檻",
                    progress = distancePct,
                    hint = if (irisThreshold.isNaN()) "尚未建立個人距離門檻" else "目前距離會和你的個人基準比較，越高代表越靠近螢幕。",
                    warning = tooClose,
                    accent = Color(0xFF7BC8F6),
                    trend = distanceTrend
                )
            }

            SummaryMetricRow(
                metric = HistoryMetric.EYE_OPEN,
                title = "睜眼狀態",
                value = formatPercent(eyePct),
                status = eyeOpenStatus(eyePct, squinting, faceDetected, faceError),
                warning = squinting,
                accent = Color(0xFFA6D8FF),
                expanded = expandedMetric == HistoryMetric.EYE_OPEN,
                onToggle = onToggleMetric
            ) {
                MetricDetail(
                    unit = "越低越接近瞇眼",
                    progress = eyePct,
                    hint = if (eyeOpenThreshold.isNaN()) "尚未建立睜眼基準" else "門檻 ${formatPercent(probabilityPercent(eyeOpenThreshold))}，低於門檻時會被視為疲勞或瞇眼風險。",
                    warning = squinting,
                    accent = Color(0xFFA6D8FF),
                    trend = eyeTrend
                )
            }

            SummaryMetricRow(
                metric = HistoryMetric.POSTURE,
                title = "肩頸姿勢",
                value = formatPercent(posturePct),
                status = postureMetricStatus(posturePct, slouching, poseDetected, poseError),
                warning = slouching,
                accent = Color(0xFFBDECCF),
                expanded = expandedMetric == HistoryMetric.POSTURE,
                onToggle = onToggleMetric
            ) {
                MetricDetail(
                    unit = "100% = 警戒線",
                    progress = posturePct,
                    hint = "目前姿勢會和校正時的舒適坐姿比較，越低代表越容易低頭或駝背。",
                    warning = slouching,
                    accent = Color(0xFFBDECCF),
                    trend = postureTrend
                )
            }

            SummaryMetricRow(
                metric = HistoryMetric.LYING,
                title = "躺姿使用",
                value = formatPercent(lyingPct),
                status = if (lying) "正在躺姿用機" else if (lyingPct == null) "等待感測器" else "未達警戒",
                warning = lying,
                accent = Color(0xFFFFD98A),
                expanded = expandedMetric == HistoryMetric.LYING,
                onToggle = onToggleMetric
            ) {
                MetricDetail(
                    unit = "水平度",
                    progress = lyingPct,
                    hint = formatAngleHint(pitchDeg = pitchDeg, rollDeg = rollDeg, tiltDeg = tiltDeg),
                    warning = lying,
                    accent = Color(0xFFFFD98A),
                    trend = lyingTrend
                )
            }
        }
    }
}

@Composable
private fun SummaryMetricRow(
    metric: HistoryMetric,
    title: String,
    value: String,
    status: String,
    warning: Boolean,
    accent: Color,
    expanded: Boolean,
    onToggle: (HistoryMetric) -> Unit,
    detail: @Composable () -> Unit
) {
    val spacing = EyeDesignTokens.spacing
    val cardShape = RoundedCornerShape(22.dp)
    val sectionBorder = groupedSectionBorder()
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "summaryArrowRotation"
    )

    Column(
        modifier = Modifier
            .animateContentSize(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
            .shadow(
                elevation = 3.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.03f)
            )
            .fillMaxWidth()
            .clip(cardShape)
            .clickable { onToggle(metric) }
            .background(Color.White.copy(alpha = 0.96f))
            .border(
                BorderStroke(0.5.dp, sectionBorder),
                cardShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 62.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryMetricIcon(metric = metric)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = EyeDesignTokens.typography.bodyStrong,
                    color = cardTitleTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = status,
                    style = EyeDesignTokens.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else cardBodyTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MetricValueText(
                value = value,
                fontSize = 20,
                color = cardTitleTextColor()
            )
            Text(
                text = "›",
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                fontSize = 34.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E8E93)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)) + slideInVertically(
                initialOffsetY = { -it / 6 },
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180)) + slideOutVertically(
                targetOffsetY = { -it / 8 },
                animationSpec = tween(160, easing = FastOutSlowInEasing)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                detail()
            }
        }
    }
    Spacer(modifier = Modifier.height(spacing.xs))
}

private data class SummaryMetricIconStyle(
    val background: Color,
    val tint: Color,
    val iconRes: Int
)

@Composable
private fun summaryMetricIconStyle(metric: HistoryMetric): SummaryMetricIconStyle {
    val colors = EyeDesignTokens.colors
    return when (metric) {
        HistoryMetric.DISTANCE -> SummaryMetricIconStyle(
            background = colors.metricDistanceBg,
            tint = colors.metricDistanceFg,
            iconRes = R.drawable.ic_nav_vision
        )
        HistoryMetric.EYE_OPEN -> SummaryMetricIconStyle(
            background = colors.metricBlinkBg,
            tint = colors.metricBlinkFg,
            iconRes = R.drawable.ic_nav_dashboard_eye_open
        )
        HistoryMetric.POSTURE -> SummaryMetricIconStyle(
            background = colors.metricPostureBg,
            tint = colors.metricPostureFg,
            iconRes = R.drawable.ic_nav_exercise
        )
        HistoryMetric.LYING -> SummaryMetricIconStyle(
            background = colors.metricLyingBg,
            tint = colors.metricLyingFg,
            iconRes = R.drawable.ic_warning
        )
    }
}

@Composable
private fun SummaryMetricIcon(metric: HistoryMetric) {
    val style = summaryMetricIconStyle(metric)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = style.iconRes),
            contentDescription = null,
            tint = style.tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun MetricDetail(
    unit: String,
    progress: Int?,
    hint: String?,
    warning: Boolean,
    accent: Color,
    trend: List<Float>
) {
    Text(text = unit, fontSize = 12.sp, color = cardBodyTextColor())
    Spacer(modifier = Modifier.height(8.dp))
    ProgressTrack(progress = progress, warning = warning, accent = accent)
    Spacer(modifier = Modifier.height(10.dp))
    Sparkline(
        values = trend,
        color = if (warning) MaterialTheme.colorScheme.error else accent,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "較早", fontSize = 11.sp, color = cardBodyTextColor())
        Text(text = "現在", fontSize = 11.sp, color = cardBodyTextColor())
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (hint == null) {
        SkeletonPlaceholder()
    } else {
        Text(text = hint, fontSize = 12.sp, color = cardBodyTextColor(), lineHeight = 16.sp)
    }
}

@Composable
private fun ExpandableMonitoringStatusCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    liveTsUptimeMs: Long,
    faceSeenUptimeMs: Long,
    lastWasCameraFrame: Boolean,
    faceDetected: Boolean,
    identityPaused: Boolean,
    poseDetected: Boolean,
    faceError: Boolean,
    poseError: Boolean,
    pitchDeg: Float,
    rollDeg: Float,
    tiltDeg: Float
) {
    var nowUptime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowUptime = SystemClock.uptimeMillis()
            delay(1000)
        }
    }

    val ageSec = if (liveTsUptimeMs > 0L) ((nowUptime - liveTsUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val faceAgeSec = if (faceSeenUptimeMs > 0L) ((nowUptime - faceSeenUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val stale = ageSec != null && ageSec >= 6
    val title = when {
        !monitoringEnabled -> "監測已暫停"
        identityPaused -> "偵測到其他人，監測暫停中"
        faceError || poseError -> "偵測器錯誤"
        stale -> "資料未更新"
        !lastWasCameraFrame -> "感測器更新中"
        !alertsReady -> "收集中，提醒未完整啟用"
        else -> "監測更新中"
    }
    val spacing = EyeDesignTokens.spacing
    val text = EyeDesignTokens.typography
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "monitoringArrowRotation"
    )

    GlassCard {
        Column(
            modifier = Modifier
                .animateContentSize(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
                .fillMaxWidth()
                .clickable(onClick = onToggle)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "監測細節", style = text.sectionTitle, color = cardTitleTextColor())
                    Text(text = title, style = text.bodySmall, color = if (stale) MaterialTheme.colorScheme.error else cardBodyTextColor())
                }
                Text(
                    text = "›",
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = cardBodyTextColor()
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    val lastUpdateText = when {
                        ageSec == null -> null
                        ageSec <= 1 -> "剛剛"
                        else -> "${ageSec}s 前"
                    }
                    val faceText = when {
                        faceAgeSec == null -> null
                        faceAgeSec <= 1 -> "剛剛"
                        else -> "${faceAgeSec}s 前"
                    }
                    SkeletonInfoLine(label = "最後更新：", value = lastUpdateText)
                    SkeletonInfoLine(label = "最近偵測到臉：", value = faceText)
                    Text(
                        text = "資料來源：${if (lastWasCameraFrame) "相機 + 感測器" else "感測器"}",
                        fontSize = 13.sp,
                        color = cardBodyTextColor()
                    )
                    Text(
                        text = "臉部：${qualityText(faceDetected, faceError)} / 姿勢：${qualityText(poseDetected, poseError)}",
                        fontSize = 13.sp,
                        color = cardBodyTextColor()
                    )
                    AngleInfoLine(pitchDeg = pitchDeg, rollDeg = rollDeg, tiltDeg = tiltDeg)
                    if (monitoringEnabled && !alertsReady) {
                        Text(
                            text = "資料蒐集已啟動，但若未開啟無障礙服務，跨 app 語音與遮罩提醒會受限。",
                            fontSize = 12.sp,
                            color = cardBodyTextColor(),
                            lineHeight = 16.sp
                        )
                    }
                    if (stale) {
                        Text(
                            text = "可能原因：前景服務被系統停止、相機權限/前鏡頭被占用，或系統省電限制導致背景停止。",
                            fontSize = 12.sp,
                            color = cardBodyTextColor(),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringStatusCard(
    monitoringEnabled: Boolean,
    alertsReady: Boolean,
    liveTsUptimeMs: Long,
    faceSeenUptimeMs: Long,
    pitchDeg: Float,
    rollDeg: Float,
    tiltDeg: Float
) {
    val text = EyeDesignTokens.typography
    var nowUptime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowUptime = SystemClock.uptimeMillis()
            delay(1000)
        }
    }

    val ageSec = if (liveTsUptimeMs > 0L) ((nowUptime - liveTsUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val faceAgeSec = if (faceSeenUptimeMs > 0L) ((nowUptime - faceSeenUptimeMs).coerceAtLeast(0L) / 1000L).toInt() else null
    val stale = ageSec != null && ageSec >= 6
    val title = when {
        !monitoringEnabled -> "監測狀態：已暫停"
        stale -> "監測狀態：資料未更新"
        !alertsReady -> "監測狀態：收集中，提醒未完整啟用"
        else -> "監測狀態：更新中"
    }

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = text.sectionTitle,
                color = if (stale) MaterialTheme.colorScheme.error else cardTitleTextColor()
            )
            Spacer(modifier = Modifier.height(6.dp))

            val lastUpdateText = when {
                ageSec == null -> null
                ageSec <= 1 -> "剛剛"
                else -> "${ageSec}s 前"
            }
            SkeletonInfoLine(label = "最後更新：", value = lastUpdateText)

            val faceText = when {
                faceAgeSec == null -> null
                faceAgeSec <= 1 -> "剛剛"
                else -> "${faceAgeSec}s 前"
            }
            SkeletonInfoLine(label = "最近偵測到臉：", value = faceText)

            Spacer(modifier = Modifier.height(8.dp))
            AngleInfoLine(pitchDeg = pitchDeg, rollDeg = rollDeg, tiltDeg = tiltDeg)

            if (monitoringEnabled && !alertsReady) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "資料蒐集已啟動，但若未開啟無障礙服務，跨 app 語音與遮罩提醒會受限。",
                    fontSize = 12.sp,
                    color = cardBodyTextColor(),
                    lineHeight = 16.sp
                )
            }

            if (stale) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "可能原因：前景服務被系統停止、相機權限/前鏡頭被占用，或系統省電限制導致背景停止。",
                    fontSize = 12.sp,
                    color = cardBodyTextColor(),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int, monitoringReady: Boolean, warning: Boolean, modifier: Modifier = Modifier) {
    if (!monitoringReady) {
        val setupColor = EyeDesignTokens.colors.textPrimary
        Box(
            modifier = modifier
                .size(72.dp)
                .border(3.dp, setupColor.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "0",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = setupColor
                )
                Text(
                    text = "分",
                    fontSize = 12.sp,
                    modifier = Modifier.alpha(0.6f),
                    color = cardBodyTextColor()
                )
            }
        }
        return
    }

    val accent = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 9.dp.toPx()
            drawArc(
                color = Color.Black.copy(alpha = 0.08f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * (score / 100f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = score.toString(), fontSize = 24.sp, fontWeight = FontWeight.Black, color = cardTitleTextColor())
            Text(text = "分", fontSize = 11.sp, color = cardBodyTextColor())
        }
    }
}

@Composable
private fun SkeletonPlaceholder(
    width: androidx.compose.ui.unit.Dp = 60.dp,
    height: androidx.compose.ui.unit.Dp = 12.dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color.Gray.copy(alpha = 0.2f))
    )
}

@Composable
private fun MetricValueText(value: String, fontSize: Int, color: Color) {
    if (value == "--") {
        SkeletonPlaceholder(width = 64.dp, height = 20.dp)
    } else {
        Text(
            text = value,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}

@Composable
private fun SkeletonInfoLine(label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 13.sp, color = cardBodyTextColor())
        if (value == null) {
            SkeletonPlaceholder()
        } else {
            Text(text = value, fontSize = 13.sp, color = cardBodyTextColor())
        }
    }
}

@Composable
private fun AngleInfoLine(pitchDeg: Float, rollDeg: Float, tiltDeg: Float) {
    val angleText = formatAngleHint(pitchDeg = pitchDeg, rollDeg = rollDeg, tiltDeg = tiltDeg)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "角度：", fontSize = 13.sp, color = cardBodyTextColor())
        if (angleText == null) {
            SkeletonPlaceholder()
        } else {
            Text(text = angleText, fontSize = 13.sp, color = cardBodyTextColor())
        }
    }
}

@Composable
private fun StatePill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    StatePill(label = null, value = text, active = active, modifier = modifier)
}

@Composable
private fun StatePill(
    label: String?,
    value: String?,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = EyeDesignTokens.colors
    val text = EyeDesignTokens.typography
    Box(
        modifier = modifier
            .clip(EyeDesignTokens.chipShape)
            .background(
                if (active) colors.accentPrimary.copy(alpha = 0.12f)
                else colors.surfaceSubtle
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (label != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = label,
                    style = text.caption,
                    color = cardBodyTextColor(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                if (value == null) {
                    SkeletonPlaceholder(width = 48.dp, height = 10.dp)
                } else {
                    Text(
                        text = value,
                        style = text.caption,
                        fontWeight = FontWeight.Bold,
                        color = if (active) colors.accentPrimary else cardBodyTextColor(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            if (value != null) {
                Text(
                    text = value,
                    style = text.caption,
                    fontWeight = FontWeight.Bold,
                    color = if (active) colors.accentPrimary else cardBodyTextColor(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class HistoryMetric(val label: String) {
    DISTANCE("距離"),
    EYE_OPEN("咪眼"),
    POSTURE("姿勢"),
    LYING("躺姿")
}

private enum class SetupStep {
    CAMERA,
    CALIBRATION,
    NOTIFICATION,
    MONITORING,
    ACCESSIBILITY,
    DONE
}

private fun pushHistory(target: MutableList<Float>, value: Float) {
    target.add(value)
    if (target.size > HISTORY_MAX_POINTS) target.removeAt(0)
}

private fun Context.hasPostNotificationsPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun GridBackdrop(modifier: Modifier = Modifier) {
    val backgroundBase = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    backgroundBase.copy(alpha = 0.98f),
                    backgroundBase,
                    surfaceVariant.copy(alpha = 0.72f)
                )
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primaryGlow, Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.18f),
                radius = size.minDimension * 0.65f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(secondaryGlow, Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 0.32f),
                radius = size.minDimension * 0.70f
            )
        )
    }
}

@Composable
private fun DashboardHeader(
    heroState: DashboardHeroState,
    monitoringEnabled: Boolean,
    monitoringReady: Boolean,
    toggleEnabled: Boolean,
    onToggleMonitoring: (Boolean) -> Unit
) {
    val colors = EyeDesignTokens.colors
    val headerAccent by animateColorAsState(
        targetValue = if (heroState.warning) MaterialTheme.colorScheme.error else colors.accentPrimary,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "headerStatusColor"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "眼睛健康概覽",
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = heroState.headline,
            modifier = Modifier.padding(top = 16.dp),
            style = EyeDesignTokens.typography.bodySmall,
            color = headerAccent,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Switch(
                checked = monitoringEnabled,
                onCheckedChange = onToggleMonitoring,
                enabled = toggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.navActiveContainer,
                    checkedTrackColor = colors.accentPrimary,
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surfaceSubtle,
                    disabledUncheckedTrackColor = colors.surfaceSubtle.copy(alpha = 0.7f),
                    disabledUncheckedThumbColor = colors.textTertiary.copy(alpha = 0.7f)
                )
            )
        }
    }
}

@Composable
private fun SetupCard(
    hasCameraPermission: Boolean,
    hasCalibrated: Boolean,
    hasNotificationPermission: Boolean,
    isServiceEnabled: Boolean,
    monitoringEnabled: Boolean,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenCalibration: (() -> Unit)?,
    onOpenAccessibilitySettings: () -> Unit,
    onEnableMonitoring: (() -> Unit)?
) {
    val spacing = EyeDesignTokens.spacing
    val text = EyeDesignTokens.typography
    val currentStep = when {
        !hasCameraPermission -> SetupStep.CAMERA
        !hasCalibrated -> SetupStep.CALIBRATION
        !hasNotificationPermission -> SetupStep.NOTIFICATION
        !monitoringEnabled -> SetupStep.MONITORING
        !isServiceEnabled -> SetupStep.ACCESSIBILITY
        else -> SetupStep.DONE
    }

    if (currentStep == SetupStep.DONE) return

    val (title, subtitle, primaryLabel, primaryAction) = when (currentStep) {
        SetupStep.CAMERA -> arrayOf("需要相機權限", "我們用前鏡頭計算距離、咪眼與姿勢，指標才會開始更新。", "授權相機", "") to onRequestCamera
        SetupStep.CALIBRATION -> arrayOf("完成個人校正", "校正後才會啟用提醒與門檻值（約 10 秒）。", if (onOpenCalibration != null) "開始校正" else "", "") to (onOpenCalibration ?: {})
        SetupStep.NOTIFICATION -> arrayOf("允許監測通知", "Android 13 以上需要通知權限，前景服務才有清楚的常駐狀態，不會讓你以為有在跑其實沒跑。", "允許通知", "") to onRequestNotifications
        SetupStep.MONITORING -> arrayOf("開始監測", "開啟監測後，前景服務會開始收集即時資料並更新儀表板。", if (onEnableMonitoring != null) "開始監測" else "", "") to (onEnableMonitoring ?: {})
        SetupStep.ACCESSIBILITY -> arrayOf("開啟提醒功能", "資料蒐集已可運作；開啟無障礙服務後，才能獲得跨 app 語音與遮罩提醒。", "開啟無障礙服務", "") to onOpenAccessibilitySettings
        SetupStep.DONE -> arrayOf("", "", "", "") to {}
    }.let { (meta, action) ->
        Quad(meta[0], meta[1], meta[2].ifBlank { null }, action)
    }

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, style = text.bodyStrong, color = cardTitleTextColor())
            Spacer(modifier = Modifier.height(spacing.xs - 2.dp))
            Text(text = subtitle, style = text.bodySmall, color = cardBodyTextColor(), lineHeight = 18.sp)

            Spacer(modifier = Modifier.height(spacing.sm))
            Column {
                SetupStepRow(1, "授權相機", done = hasCameraPermission, isCurrent = currentStep == SetupStep.CAMERA)
                SetupStepRow(2, "完成校正", done = hasCalibrated, isCurrent = currentStep == SetupStep.CALIBRATION)
                SetupStepRow(3, "允許通知", done = hasNotificationPermission, isCurrent = currentStep == SetupStep.NOTIFICATION)
                SetupStepRow(4, "開始監測", done = hasCameraPermission && hasCalibrated && hasNotificationPermission && monitoringEnabled, isCurrent = currentStep == SetupStep.MONITORING)
                SetupStepRow(5, "開啟無障礙提醒", done = isServiceEnabled, isCurrent = currentStep == SetupStep.ACCESSIBILITY, isLast = true)
            }

            Spacer(modifier = Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs + 2.dp)) {
                if (primaryLabel != null) {
                    if (currentStep == SetupStep.CAMERA) {
                        CameraPermissionButton(onClick = primaryAction)
                    } else {
                        PrimaryPillButton(text = primaryLabel, onClick = primaryAction)
                    }
                }
                if (hasCalibrated && onOpenCalibration != null) {
                    SecondaryPillButton(text = "重新校正", onClick = onOpenCalibration)
                }
            }
        }
    }
}

private data class Quad(
    val title: String,
    val subtitle: String,
    val primaryLabel: String?,
    val primaryAction: () -> Unit
)

@Composable
private fun SetupStepRow(index: Int, label: String, done: Boolean, isCurrent: Boolean, isLast: Boolean = false) {
    val colors = EyeDesignTokens.colors
    val activeBlue = colors.accentPrimary
    val futureGrey = colors.textTertiary
    val stepLabelColor = when {
        done || isCurrent -> colors.textPrimary
        else -> colors.textTertiary
    }
    val itemVerticalPadding = 8.dp
    val stepCircleSize = 24.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isLast) {
                    val centerX = stepCircleSize.toPx() / 2f
                    val lineStartY = itemVerticalPadding.toPx() + stepCircleSize.toPx() / 2f
                    drawLine(
                        color = futureGrey.copy(alpha = 0.5f),
                        start = Offset(centerX, lineStartY),
                        end = Offset(centerX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            .padding(vertical = itemVerticalPadding),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(stepCircleSize)
                    .then(
                        if (done || isCurrent) {
                            Modifier
                                .clip(CircleShape)
                                .background(activeBlue)
                        } else {
                            Modifier.border(1.dp, futureGrey, CircleShape)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colors.textOnInverse,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = index.toString(),
                        color = if (isCurrent) colors.textOnInverse else futureGrey,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            modifier = Modifier
                .padding(top = 2.dp),
            color = stepLabelColor,
            fontSize = 13.sp,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun MetricGrid(
    irisNorm: Float,
    eyeOpenMin: Float,
    postureRatio: Float,
    tiltDeg: Float,
    pitchDeg: Float,
    rollDeg: Float,
    warningsMask: Int,
    irisThreshold: Float,
    eyeOpenThreshold: Float,
    postureThreshold: Float,
    distanceTrend: List<Float>,
    eyeTrend: List<Float>,
    postureTrend: List<Float>,
    lyingTrend: List<Float>
) {
    val tooClose = warningsMask and 1 != 0
    val squinting = warningsMask and 2 != 0
    val slouching = warningsMask and 4 != 0
    val lying = warningsMask and 8 != 0
    val distancePct = distancePercent(irisNorm, irisThreshold)
    val eyePct = probabilityPercent(eyeOpenMin)
    val posturePct = ratioPercent(postureRatio, postureThreshold)
    val lyingPct = horizontalPercentFromTilt(tiltDeg)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "即時指標",
            style = EyeDesignTokens.typography.sectionTitle,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "距離",
                value = formatPercent(distancePct),
                unit = "100% = 太近門檻",
                status = metricStatus(distancePct, tooClose, warningWhenHigh = true),
                progress = distancePct,
                hint = if (irisThreshold.isNaN()) "尚未校正門檻" else "越大越近",
                warning = tooClose,
                accent = Color(0xFF47F1B5),
                trend = distanceTrend
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "睜眼",
                value = formatPercent(eyePct),
                unit = "越低越咪眼",
                status = eyeOpenStatus(eyePct, squinting),
                progress = eyePct,
                hint = if (eyeOpenThreshold.isNaN()) null else "門檻 ${formatPercent(probabilityPercent(eyeOpenThreshold))}",
                warning = squinting,
                accent = Color(0xFF6EE7FF),
                trend = eyeTrend
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "姿勢",
                value = formatPercent(posturePct),
                unit = "100% = 警戒線",
                status = metricStatus(posturePct, slouching, warningWhenHigh = false),
                progress = posturePct,
                hint = "越低越駝背/低頭",
                warning = slouching,
                accent = Color(0xFFA7F36B),
                trend = postureTrend
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "躺姿",
                value = formatPercent(lyingPct),
                unit = "水平度",
                status = if (lying) "正在躺姿用機" else if (lyingPct == null) "等待感測器" else "未達警戒",
                progress = lyingPct,
                hint = formatAngleHint(pitchDeg = pitchDeg, rollDeg = rollDeg, tiltDeg = tiltDeg),
                showHintSkeleton = true,
                warning = lying,
                accent = Color(0xFFFFD166),
                trend = lyingTrend
            )
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String,
    status: String,
    progress: Int?,
    hint: String?,
    showHintSkeleton: Boolean = false,
    warning: Boolean,
    accent: Color,
    trend: List<Float>
) {
    val text = EyeDesignTokens.typography
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = title, style = text.bodyStrong, color = cardTitleTextColor())
                StatusDot(active = warning, accent = accent)
            }
            Spacer(modifier = Modifier.height(8.dp))
            MetricValueText(
                value = value,
                fontSize = 30,
                color = if (warning) MaterialTheme.colorScheme.error else cardTitleTextColor()
            )
            Text(text = unit, style = text.bodySmall, color = cardBodyTextColor())
            Spacer(modifier = Modifier.height(8.dp))
            ProgressTrack(progress = progress, warning = warning, accent = accent)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = text.bodySmall,
                color = if (warning) MaterialTheme.colorScheme.error else cardBodyTextColor()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Sparkline(values = trend, color = if (warning) MaterialTheme.colorScheme.error else accent, modifier = Modifier.fillMaxWidth().height(44.dp))
            if (hint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = hint, style = text.bodySmall, color = cardBodyTextColor())
            } else if (showHintSkeleton) {
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonPlaceholder()
            }
        }
    }
}

@Composable
private fun HistoryChartCard(
    selected: HistoryMetric,
    onSelect: (HistoryMetric) -> Unit,
    distanceTrend: List<Float>,
    eyeTrend: List<Float>,
    postureTrend: List<Float>,
    lyingTrend: List<Float>
) {
    val text = EyeDesignTokens.typography
    val sectionBackground = groupedSectionBackground()
    val sectionBorder = groupedSectionBorder()
    val (values, accent) = when (selected) {
        HistoryMetric.DISTANCE -> distanceTrend to Color(0xFF47F1B5)
        HistoryMetric.EYE_OPEN -> eyeTrend to Color(0xFF6EE7FF)
        HistoryMetric.POSTURE -> postureTrend to Color(0xFFA7F36B)
        HistoryMetric.LYING -> lyingTrend to Color(0xFFFFD166)
    }

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "歷史圖表", style = text.sectionTitle, color = cardTitleTextColor())
                Text(text = "最近 ${min(values.size, HISTORY_MAX_POINTS)} 點", style = text.bodySmall, color = cardBodyTextColor())
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryMetric.values().forEach { metric ->
                    FilterChip(
                        selected = selected == metric,
                        onClick = { onSelect(metric) },
                        label = { Text(metric.label, color = cardBodyTextColor()) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Sparkline(
                values = values,
                color = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(sectionBackground)
                    .border(0.5.dp, sectionBorder, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            minV = min(minV, v)
            maxV = max(maxV, v)
        }
        if (!minV.isFinite() || !maxV.isFinite()) return@Canvas
        if (minV == maxV) {
            minV -= 1f
            maxV += 1f
        }

        val path = Path()
        val dx = if (values.size <= 1) size.width else size.width / (values.size - 1).toFloat()
        var started = false
        for ((index, v) in values.withIndex()) {
            if (v.isNaN()) {
                started = false
                continue
            }
            val t = (v - minV) / (maxV - minV)
            val x = dx * index
            val y = size.height - (t * size.height)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        drawLine(
            color = Color.Black.copy(alpha = 0.06f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f
        )
        drawPath(path = path, color = color, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cardShape = RoundedCornerShape(24.dp)
    val colors = EyeDesignTokens.colors
    Card(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.03f)
            )
            .fillMaxWidth(),
        shape = cardShape,
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        colors = CardDefaults.cardColors(
            containerColor = colors.cardContainer
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun StatusDot(active: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.75f))
    )
}

@Composable
private fun ProgressTrack(progress: Int?, warning: Boolean, accent: Color) {
    val colors = EyeDesignTokens.colors
    val clamped = ((progress ?: 0).coerceIn(0, 140) / 140f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "metric_progress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(EyeDesignTokens.chipShape)
            .background(colors.surfaceSubtle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(8.dp)
                .clip(EyeDesignTokens.chipShape)
                .background(if (warning) MaterialTheme.colorScheme.error else accent)
        )
    }
}

@Composable
private fun CameraPermissionButton(onClick: () -> Unit) {
    val colors = EyeDesignTokens.colors
    val radius = EyeDesignTokens.radius
    val spacing = EyeDesignTokens.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.md, bottom = spacing.md)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(radius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.textOnInverse
            )
        ) {
            Text(
                "授權相機",
                fontWeight = FontWeight.SemiBold,
                color = colors.textOnInverse
            )
        }
    }
}

@Composable
private fun PrimaryPillButton(text: String, onClick: () -> Unit) {
    val colors = EyeDesignTokens.colors
    Button(
        onClick = onClick,
        shape = EyeDesignTokens.chipShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentPrimary,
            contentColor = colors.textOnInverse
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            color = colors.textOnInverse
        )
    }
}

@Composable
private fun SecondaryPillButton(text: String, onClick: () -> Unit) {
    val colors = EyeDesignTokens.colors
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, colors.borderStrong),
        shape = EyeDesignTokens.chipShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.textPrimary
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary
        )
    }
}

private fun formatAngleHint(pitchDeg: Float, rollDeg: Float, tiltDeg: Float): String? {
    if (pitchDeg.isNaN() || rollDeg.isNaN() || tiltDeg.isNaN()) return null
    return "tilt ${formatDeg(tiltDeg)}° / pitch ${formatDeg(pitchDeg)}° / roll ${formatDeg(rollDeg)}°"
}

private fun formatDeg(value: Float): String = value.toInt().toString()

private fun probabilityPercent(probability: Float): Int? {
    if (probability.isNaN()) return null
    return (probability.coerceIn(0f, 1f) * 100f).roundToInt()
}

private fun distancePercent(irisNorm: Float, threshold: Float): Int? {
    if (irisNorm.isNaN() || threshold.isNaN() || threshold <= 0f) return null
    return ((irisNorm / threshold).coerceIn(0f, 2.5f) * 100f).roundToInt()
}

private fun ratioPercent(value: Float, threshold: Float): Int? {
    if (value.isNaN() || threshold.isNaN() || threshold <= 0f) return null
    return ((value / threshold).coerceIn(0f, 2.5f) * 100f).roundToInt()
}

private fun horizontalPercentFromTilt(tiltDeg: Float): Int? {
    if (tiltDeg.isNaN()) return null
    val clamped = tiltDeg.coerceIn(0f, 180f)
    val fromHorizontal = min(clamped, 180f - clamped)
    return ((1f - (fromHorizontal / 90f)).coerceIn(0f, 1f) * 100f).roundToInt()
}

private fun formatPercent(value: Int?): String = value?.toString()?.plus("%") ?: "--"

private fun activeWarningText(warningsMask: Int): String {
    val warnings = mutableListOf<String>()
    if (warningsMask and 1 != 0) warnings.add("距離過近")
    if (warningsMask and 2 != 0) warnings.add("瞇眼風險")
    if (warningsMask and 4 != 0) warnings.add("姿勢偏低")
    if (warningsMask and 8 != 0) warnings.add("躺姿使用")
    return if (warnings.isEmpty()) "暫時沒有警告。" else "目前警告：${warnings.joinToString("、")}。"
}

private fun detectorErrorText(faceError: Boolean, poseError: Boolean): String {
    return when {
        faceError && poseError -> "臉部與姿勢偵測器都回報錯誤；請先檢查相機畫面、光線與 ML Kit 是否可用。"
        faceError -> "臉部偵測器回報錯誤；距離與睜眼指標可能不可靠。"
        poseError -> "姿勢偵測器回報錯誤；肩頸姿勢指標可能不可靠。"
        else -> ""
    }
}

private fun qualityText(detected: Boolean, error: Boolean): String {
    return when {
        error -> "錯誤"
        detected -> "正常"
        else -> "未偵測"
    }
}

private fun faceMetricStatus(
    percent: Int?,
    warning: Boolean,
    faceDetected: Boolean,
    faceError: Boolean,
    warningWhenHigh: Boolean
): String {
    if (faceError) return "偵測錯誤"
    if (!faceDetected) return "未看到臉"
    return metricStatus(percent, warning, warningWhenHigh)
}

private fun postureMetricStatus(
    percent: Int?,
    warning: Boolean,
    poseDetected: Boolean,
    poseError: Boolean
): String {
    if (poseError) return "偵測錯誤"
    if (!poseDetected) return "未看到肩頸"
    return metricStatus(percent, warning, warningWhenHigh = false)
}

private fun metricStatus(percent: Int?, warning: Boolean, warningWhenHigh: Boolean): String {
    if (percent == null) return "等待資料"
    if (warning) return "已達警戒"
    return if (warningWhenHigh) {
        when {
            percent >= 90 -> "接近門檻"
            percent >= 65 -> "正常偏近"
            else -> "安全"
        }
    } else {
        when {
            percent <= 110 -> "接近門檻"
            percent <= 140 -> "正常偏低"
            else -> "穩定"
        }
    }
}

private fun eyeOpenStatus(
    percent: Int?,
    warning: Boolean,
    faceDetected: Boolean = true,
    faceError: Boolean = false
): String {
    if (faceError) return "偵測錯誤"
    if (!faceDetected) return "未看到臉"
    if (percent == null) return "等待資料"
    if (warning) return "已達警戒"
    return when {
        percent < 55 -> "睜眼偏低"
        percent < 75 -> "略低"
        else -> "睜眼正常"
    }
}
