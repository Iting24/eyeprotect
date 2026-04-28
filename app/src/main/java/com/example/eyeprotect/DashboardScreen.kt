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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Offset
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
                liveTs = intent.getLongExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TS, liveTs)
                warningsMask = intent.getIntExtra(EyeHealthAccessibilityService.EXTRA_LIVE_WARNINGS_MASK, warningsMask)
                lastWasCameraFrame = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IS_CAMERA_FRAME, lastWasCameraFrame)
                faceDetected = intent.getBooleanExtra(EyeHealthAccessibilityService.EXTRA_LIVE_FACE_DETECTED, faceDetected)
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
        } else {
            MonitoringForegroundService.stop(context)
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
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFFD4C2),
                            0.35f to Color(0xFFFFE8DF),
                            0.55f to Color(0xFFFFF5F2),
                            1.0f to Color(0xFFF2F2F7)
                        )
                    )
                }
            )
    ) {
        val alertsReady = monitoringReady && isServiceEnabled
        val statusLabel = when {
            !monitoringReady -> "尚未完成設定"
            !monitoringEnabled -> "已暫停"
            !isServiceEnabled -> "監測中，提醒功能受限"
            else -> "監測中"
        }

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

        val dashboardListState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(horizontal = spacing.lg),
            state = dashboardListState,
            contentPadding = PaddingValues(top = spacing.xs, bottom = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = spacing.xs)
                ) {
                    DashboardHeader(
                        monitoringEnabled = monitoringEnabled,
                        toggleEnabled = monitoringReady,
                        statusLabel = statusLabel,
                        onToggleMonitoring = setMonitoringEnabled
                    )
                }
            }

            item {
                DashboardSummaryCard(
                    monitoringReady = monitoringReady,
                    monitoringEnabled = monitoringEnabled,
                    alertsReady = alertsReady,
                    warningsMask = warningsMask,
                    liveTsUptimeMs = liveTs,
                    faceSeenUptimeMs = faceSeenUptimeMs,
                    faceDetected = faceDetected,
                    poseDetected = poseDetected,
                    faceError = faceError,
                    poseError = poseError
                )
            }

            item {
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

            item {
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

            item {
                ExpandableMonitoringStatusCard(
                    expanded = monitoringDetailsOpen,
                    onToggle = { monitoringDetailsOpen = !monitoringDetailsOpen },
                    monitoringEnabled = monitoringEnabled && monitoringReady,
                    alertsReady = alertsReady,
                    liveTsUptimeMs = liveTs,
                    faceSeenUptimeMs = faceSeenUptimeMs,
                    lastWasCameraFrame = lastWasCameraFrame,
                    faceDetected = faceDetected,
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

    val text = EyeDesignTokens.typography

    GlassCard {
        Text(
            text = "今日重點",
            style = text.sectionTitle,
            color = cardTitleTextColor()
        )
        Spacer(modifier = Modifier.height(12.dp))

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
    val cardShape = RoundedCornerShape(24.dp)
    val sectionBackground = groupedSectionBackground()
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
                elevation = 4.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.03f)
            )
            .fillMaxWidth()
            .clip(cardShape)
            .clickable { onToggle(metric) }
            .background(sectionBackground)
            .border(
                BorderStroke(0.5.dp, sectionBorder),
                cardShape
            )
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                SummaryMetricIcon(metric = metric)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = title, style = EyeDesignTokens.typography.bodyStrong, color = cardTitleTextColor())
                    Text(
                        text = status,
                        style = EyeDesignTokens.typography.bodySmall,
                        color = if (warning) MaterialTheme.colorScheme.error else cardBodyTextColor()
                    )
                }
            }
            MetricValueText(
                value = value,
                fontSize = 24,
                color = cardTitleTextColor()
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = "›",
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                style = EyeDesignTokens.typography.cardTitle,
                fontWeight = FontWeight.Bold,
                color = cardBodyTextColor()
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
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = style.iconRes),
            contentDescription = null,
            tint = style.tint,
            modifier = Modifier.size(22.dp)
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
private fun StatePill(text: String, active: Boolean) {
    StatePill(label = null, value = text, active = active)
}

@Composable
private fun StatePill(label: String?, value: String?, active: Boolean) {
    val colors = EyeDesignTokens.colors
    val text = EyeDesignTokens.typography
    Box(
        modifier = Modifier
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
                    color = cardBodyTextColor()
                )
                if (value == null) {
                    SkeletonPlaceholder(width = 48.dp, height = 10.dp)
                } else {
                    Text(
                        text = value,
                        style = text.caption,
                        fontWeight = FontWeight.Bold,
                        color = if (active) colors.accentPrimary else cardBodyTextColor()
                    )
                }
            }
        } else {
            if (value != null) {
                Text(
                    text = value,
                    style = text.caption,
                    fontWeight = FontWeight.Bold,
                    color = if (active) colors.accentPrimary else cardBodyTextColor()
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
    monitoringEnabled: Boolean,
    toggleEnabled: Boolean,
    statusLabel: String,
    onToggleMonitoring: (Boolean) -> Unit
) {
    val colors = EyeDesignTokens.colors
    val spacing = EyeDesignTokens.spacing
    val text = EyeDesignTokens.typography
    val statusColor by animateColorAsState(
        targetValue = if (monitoringEnabled) colors.accentSecondary else colors.textSecondary,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "headerStatusColor"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.06f)
                )
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_eye_health),
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "護眼監測與趨勢",
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "眼睛健康概覽",
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = statusLabel,
                style = text.bodySmall,
                color = statusColor,
                textAlign = TextAlign.Center
            )
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(EyeDesignTokens.chipShape)
            .background(colors.surfaceSubtle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
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
