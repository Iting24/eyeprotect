package com.example.eyeprotect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AppStage {
    PERMISSION,
    CALIBRATION,
    DASHBOARD
}

private const val HISTORY_MAX_POINTS = 120

@AndroidEntryPoint
class LegacyComposeActivity : ComponentActivity() {

    @Inject
    lateinit var faceDetector: FaceDetector
    
    @Inject
    lateinit var poseDetector: PoseDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EyeprotectTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier.padding(innerPadding),
                        faceDetector = faceDetector,
                        poseDetector = poseDetector
                    )
                }
            }
        }
    }
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier, faceDetector: FaceDetector, poseDetector: PoseDetector) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val prefs = remember { context.getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE) }
    
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasCalibrated by remember {
        mutableStateOf(
            prefs.contains("iris_threshold") &&
                prefs.contains("eye_open_threshold") &&
                prefs.contains("slouch_angle_threshold")
        )
    }
    var isServiceEnabled by remember { mutableStateOf(false) }

    var currentStage by remember {
        mutableStateOf(
            when {
                !hasCameraPermission -> AppStage.PERMISSION
                !hasCalibrated -> AppStage.CALIBRATION
                else -> AppStage.DASHBOARD
            }
        )
    }

    // 當 App 啟動或從背景回來時，檢查狀態與權限
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            hasCalibrated =
                prefs.contains("iris_threshold") &&
                    prefs.contains("eye_open_threshold") &&
                    prefs.contains("slouch_angle_threshold")
            isServiceEnabled = isAccessibilityServiceEnabled(context, EyeHealthAccessibilityService::class.java)
            
            // 只有在不是處於手動校正狀態時才自動切換 Stage
            if (currentStage != AppStage.CALIBRATION || hasCalibrated) {
                 currentStage = when {
                    !hasCameraPermission -> AppStage.PERMISSION
                    !hasCalibrated -> AppStage.CALIBRATION
                    else -> AppStage.DASHBOARD
                }
            }
        }
    }

    when (currentStage) {
        AppStage.PERMISSION -> {
            DashboardScreen(
                modifier = modifier,
                isServiceEnabled = isServiceEnabled,
                hasCameraPermission = hasCameraPermission,
                hasCalibrated = hasCalibrated,
                onRequestPermission = { hasCameraPermission = true }
            )
        }
        AppStage.CALIBRATION -> {
            CalibrationScreen(
                faceDetector = faceDetector,
                poseDetector = poseDetector,
                onCalibrationComplete = {
                    hasCalibrated = true
                    currentStage = AppStage.DASHBOARD
                }
            )
        }
        AppStage.DASHBOARD -> {
            DashboardScreen(
                modifier = modifier,
                isServiceEnabled = isServiceEnabled,
                hasCameraPermission = hasCameraPermission,
                hasCalibrated = hasCalibrated,
                onReCalibrate = {
                    currentStage = AppStage.CALIBRATION
                }
            )
        }
    }
}

@Composable
fun CalibrationScreen(
    faceDetector: FaceDetector,
    poseDetector: PoseDetector,
    onCalibrationComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var countdown by remember { mutableIntStateOf(3) }
    var isCalibrating by remember { mutableStateOf(false) }
    var calibrationError by remember { mutableStateOf<String?>(null) }

    val irisDistances = remember { mutableStateListOf<Float>() }
    val eyeOpenMins = remember { mutableStateListOf<Float>() }
    val slouchAngles = remember { mutableStateListOf<Double>() }

    val previewView = remember { PreviewView(context) }
    val metricDetector = remember { PostureAndEyeDetector() }

    // 初始化相機預覽與分析
    LaunchedEffect(previewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer = object : ImageAnalysis.Analyzer {
            @androidx.camera.core.ExperimentalGetImage
            override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
                val mediaImage = imageProxy.image
                if (mediaImage != null && isCalibrating && countdown > 0) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    val faceTask = faceDetector.process(image)
                    val poseTask = poseDetector.process(image)

                    Tasks.whenAllComplete(faceTask, poseTask).addOnCompleteListener {
                        if (faceTask.isSuccessful && poseTask.isSuccessful) {
                            val face = faceTask.result?.firstOrNull()
                            val pose = poseTask.result
                            val imageWidth =
                                if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height

                            if (face != null) {
                                metricDetector.computeNormalizedIrisDistance(face, imageWidth)?.let(irisDistances::add)
                                metricDetector.computeEyeOpenMin(face)?.let(eyeOpenMins::add)
                            }
                            if (pose != null) {
                                metricDetector.computePostureRatio(pose)?.let(slouchAngles::add)
                            }
                        }
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }
        }
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) {
            imageAnalysis.clearAnalyzer()
        }
    }

    // 校正倒數邏輯
    LaunchedEffect(isCalibrating) {
        if (isCalibrating) {
            calibrationError = null
            irisDistances.clear()
            eyeOpenMins.clear()
            slouchAngles.clear()

            for (i in 3 downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0
            
            val irisMedian = medianFloat(irisDistances)
            val eyeMedian = medianFloat(eyeOpenMins)
            val slouchMedian = medianDouble(slouchAngles)

            if (irisMedian == null || eyeMedian == null || slouchMedian == null) {
                calibrationError = buildString {
                    append("校正失敗：")
                    val missing = mutableListOf<String>()
                    if (irisMedian == null) missing.add("偵測不到眼睛距離")
                    if (eyeMedian == null) missing.add("偵測不到睜眼程度")
                    if (slouchMedian == null) missing.add("偵測不到肩膀/耳朵姿勢")
                    append(missing.joinToString("、"))
                    append("。請讓臉部與上半身(含肩膀)入鏡並保持不眨眼 3 秒後重試。")
                }
                isCalibrating = false
                return@LaunchedEffect
            }

            // 以使用者基準資料推導提醒門檻
            val distanceThreshold = irisMedian * 1.15f // 瞳距越大代表距離越近
            val squintThreshold = (eyeMedian * 0.7f).coerceIn(0.15f, 0.85f)
            // Posture ratio smaller => more slouching; allow some drop from baseline.
            val slouchThreshold = (slouchMedian * 0.75).coerceIn(0.15, 2.0).toFloat()

            val prefs = context.getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("iris_threshold", distanceThreshold)
                .putFloat("eye_open_threshold", squintThreshold)
                .putFloat("slouch_angle_threshold", slouchThreshold)
                .apply()

            // 通知服務更新門檻
            val intent = Intent(EyeHealthAccessibilityService.ACTION_UPDATE_THRESHOLDS).apply {
                setPackage(context.packageName)
            }
            intent.putExtra("irisDistance", distanceThreshold)
            intent.putExtra("eyeOpenThreshold", squintThreshold)
            intent.putExtra("slouchAngleThreshold", slouchThreshold)
            context.sendBroadcast(intent)

            isCalibrating = false
            onCalibrationComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "請保持舒適坐姿並注視螢幕",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(CircleShape)
                .border(
                    width = 4.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            
            if (isCalibrating) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = countdown.toString(),
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = { isCalibrating = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(30.dp),
            enabled = !isCalibrating
        ) {
            Text(
                text = if (isCalibrating) "正在校正..." else "開始校正",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        calibrationError?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun medianFloat(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}

private fun medianDouble(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    isServiceEnabled: Boolean,
    hasCameraPermission: Boolean,
    hasCalibrated: Boolean,
    onRequestPermission: (() -> Unit)? = null,
    onReCalibrate: (() -> Unit)? = null,
    onOpenEyeExercise: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE) }
    var monitoringEnabled by remember {
        mutableStateOf(prefs.getBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, true))
    }

    var liveTs by remember { mutableLongStateOf(prefs.getLong(EyeHealthAccessibilityService.PREF_LIVE_TS, 0L)) }
    var irisNorm by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_IRIS_NORM, Float.NaN)) }
    var eyeOpenMin by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_EYE_OPEN_MIN, Float.NaN)) }
    var slouchScore by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_SLOUCH_SCORE, Float.NaN)) }
    var pitchDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_PITCH_DEG, Float.NaN)) }
    var rollDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_ROLL_DEG, Float.NaN)) }
    var tiltDeg by remember { mutableFloatStateOf(prefs.getFloat(EyeHealthAccessibilityService.PREF_LIVE_TILT_DEG, Float.NaN)) }
    var warningsMask by remember { mutableIntStateOf(prefs.getInt(EyeHealthAccessibilityService.PREF_LIVE_WARNINGS_MASK, 0)) }

    val irisThreshold = prefs.getFloat("iris_threshold", Float.NaN)
    val eyeOpenThreshold = prefs.getFloat("eye_open_threshold", Float.NaN)
    val slouchThreshold = prefs.getFloat("slouch_angle_threshold", Float.NaN)

    val distanceHistory = remember { mutableStateListOf<Float>() }
    val eyeOpenHistory = remember { mutableStateListOf<Float>() }
    val postureHistory = remember { mutableStateListOf<Float>() }
    val lyingHistory = remember { mutableStateListOf<Float>() }
    var selectedHistoryMetric by remember { mutableStateOf(HistoryMetric.DISTANCE) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != EyeHealthAccessibilityService.ACTION_LIVE_METRICS) return
                liveTs = intent.getLongExtra(EyeHealthAccessibilityService.EXTRA_LIVE_TS, liveTs)
                val incomingMask = intent.getIntExtra(EyeHealthAccessibilityService.EXTRA_LIVE_WARNINGS_MASK, warningsMask)
                val isSensorOnly =
                    !intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_IRIS_NORM) &&
                        !intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_EYE_OPEN_MIN) &&
                        !intent.hasExtra(EyeHealthAccessibilityService.EXTRA_LIVE_SLOUCH_SCORE)
                warningsMask = if (isSensorOnly) {
                    // Update only LYING bit (8), keep the rest from camera.
                    (warningsMask and 0x7) or (incomingMask and 0x8)
                } else {
                    // Camera publish includes full mask.
                    incomingMask
                }
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

                // Only camera frames include the continuous series we care about.
                if (!isSensorOnly) {
                    pushHistory(distanceHistory, distancePercent(irisNorm, irisThreshold)?.toFloat() ?: Float.NaN)
                    pushHistory(eyeOpenHistory, probabilityPercent(eyeOpenMin)?.toFloat() ?: Float.NaN)
                    pushHistory(postureHistory, ratioPercent(slouchScore, slouchThreshold)?.toFloat() ?: Float.NaN)
                    pushHistory(lyingHistory, horizontalPercentFromTilt(tiltDeg)?.toFloat() ?: Float.NaN)
                }

                // When monitoring is paused, service publishes NaNs and clears warnings.
                if (!monitoringEnabled && incomingMask == 0) {
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

    Box(modifier = modifier.fillMaxSize()) {
        GridBackdrop(modifier = Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DashboardHeader(
                monitoringEnabled = monitoringEnabled,
                onToggleMonitoring = { enabled ->
                    monitoringEnabled = enabled
                    prefs.edit().putBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, enabled).apply()
                    val intent = Intent(EyeHealthAccessibilityService.ACTION_SET_MONITORING).apply {
                        setPackage(context.packageName)
                        putExtra(EyeHealthAccessibilityService.EXTRA_MONITORING_ENABLED, enabled)
                    }
                    context.sendBroadcast(intent)
                }
            )

            SetupCard(
                hasCameraPermission = hasCameraPermission,
                hasCalibrated = hasCalibrated,
                isServiceEnabled = isServiceEnabled,
                onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenAccessibilitySettings = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                onReCalibrate = onReCalibrate
            )

            EyeExerciseCard(onOpenEyeExercise = onOpenEyeExercise)

            MetricGrid(
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
                distanceTrend = distanceHistory,
                eyeTrend = eyeOpenHistory,
                postureTrend = postureHistory,
                lyingTrend = lyingHistory
            )

            HistoryChartCard(
                selected = selectedHistoryMetric,
                onSelect = { selectedHistoryMetric = it },
                distanceTrend = distanceHistory,
                eyeTrend = eyeOpenHistory,
                postureTrend = postureHistory,
                lyingTrend = lyingHistory
            )
        }
    }
}

private enum class HistoryMetric(val label: String) {
    DISTANCE("距離"),
    EYE_OPEN("咪眼"),
    POSTURE("姿勢"),
    LYING("躺姿")
}

private fun pushHistory(target: MutableList<Float>, value: Float) {
    target.add(value)
    if (target.size > HISTORY_MAX_POINTS) target.removeAt(0)
}

@Composable
private fun GridBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val bg = Brush.linearGradient(
            colors = listOf(
                Color(0xFFF7FBFF),
                Color(0xFFF2F7FF),
                Color(0xFFEEF4FF)
            )
        )
        drawRect(brush = bg)

        // Soft, friendly blobs (reference-style background).
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x334CC9F0), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.18f),
                radius = size.minDimension * 0.65f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x33A7F3D0), Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 0.32f),
                radius = size.minDimension * 0.70f
            )
        )
    }
}

@Composable
private fun DashboardHeader(
    monitoringEnabled: Boolean,
    onToggleMonitoring: (Boolean) -> Unit
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "眼睛健康概覽",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = onBackground
            )
            Text(
                text = "護眼監測與趨勢",
                fontSize = 14.sp,
                color = secondary
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (monitoringEnabled) "監測中" else "已暫停",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (monitoringEnabled) accent else secondary
            )
            Switch(
                checked = monitoringEnabled,
                onCheckedChange = onToggleMonitoring
            )
        }
    }
}

@Composable
private fun SetupCard(
    hasCameraPermission: Boolean,
    hasCalibrated: Boolean,
    isServiceEnabled: Boolean,
    onRequestCamera: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onReCalibrate: (() -> Unit)?
) {
    val title: String
    val subtitle: String
    val primaryLabel: String?
    val primaryAction: (() -> Unit)?

    when {
        !hasCameraPermission -> {
            title = "需要相機權限"
            subtitle = "我們用前鏡頭計算距離、咪眼與姿勢，指標才會開始更新。"
            primaryLabel = "授權相機"
            primaryAction = onRequestCamera
        }
        !hasCalibrated -> {
            title = "請先完成校正"
            subtitle = "校正後才會啟用提醒與門檻值。"
            primaryLabel = null
            primaryAction = null
        }
        !isServiceEnabled -> {
            title = "開啟無障礙服務"
            subtitle = "開啟後才能在背景提醒你太近、咪眼、駝背或躺著滑。"
            primaryLabel = "前往設定"
            primaryAction = onOpenAccessibilitySettings
        }
        else -> {
            title = "一切就緒"
            subtitle = "指標會持續更新；提醒會以語音與震動發出。"
            primaryLabel = null
            primaryAction = null
        }
    }

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (primaryLabel != null && primaryAction != null) {
                    PrimaryPillButton(text = primaryLabel, onClick = primaryAction)
                }
                if (hasCameraPermission && onReCalibrate != null) {
                    SecondaryPillButton(text = "重新校正", onClick = onReCalibrate)
                }
            }
        }
    }
}

@Composable
private fun EyeExerciseCard(onOpenEyeExercise: (() -> Unit)?) {
    if (onOpenEyeExercise == null) return
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "眼睛體操",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "立即啟動懸浮窗體操（之後也會支援超時自動跳出）。",
                fontSize = 13.sp,
                color = secondary,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryPillButton(text = "立即開始", onClick = onOpenEyeExercise)
            }
        }
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "距離",
                value = formatPercent(distancePercent(irisNorm, irisThreshold)),
                unit = "100% = 太近門檻",
                hint = if (irisThreshold.isNaN()) "尚未校正門檻" else "越大越近",
                warning = tooClose,
                accent = Color(0xFF47F1B5),
                trend = distanceTrend
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "睜眼",
                value = formatPercent(probabilityPercent(eyeOpenMin)),
                unit = "越低越咪眼",
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
                value = formatPercent(ratioPercent(postureRatio, postureThreshold)),
                unit = "100% = 警戒線",
                hint = "越低越駝背/低頭",
                warning = slouching,
                accent = Color(0xFFA7F36B),
                trend = postureTrend
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                title = "躺姿",
                value = formatPercent(horizontalPercentFromTilt(tiltDeg)),
                unit = "水平度",
                hint = "tilt ${formatDeg(tiltDeg)}° (越接近 0/180 越平)",
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
    hint: String?,
    warning: Boolean,
    accent: Color,
    trend: List<Float>
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val danger = MaterialTheme.colorScheme.error
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = secondary
                )
                StatusDot(active = warning, accent = accent)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = if (warning) danger else onSurface
            )
            Text(
                text = unit,
                fontSize = 12.sp,
                color = secondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            Sparkline(
                values = trend,
                color = if (warning) danger else accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            )

            if (hint != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = secondary
                )
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
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
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
                Text(
                    text = "歷史圖表",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = onSurface
                )
                Text(
                    text = "最近 ${min(values.size, HISTORY_MAX_POINTS)} 點",
                    fontSize = 12.sp,
                    color = secondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryMetric.values().forEach { metric ->
                    FilterChip(
                        selected = selected == metric,
                        onClick = { onSelect(metric) },
                        label = { Text(metric.label) }
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
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(10.dp)
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

        // Subtle baseline.
        drawLine(
            color = Color.Black.copy(alpha = 0.06f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f
        )

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusDot(active: Boolean, accent: Color) {
    val dotColor = if (active) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.75f)
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor)
    )
}

@Composable
private fun PrimaryPillButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SecondaryPillButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

private fun formatFloat(value: Float): String {
    if (value.isNaN()) return "--"
    return String.format(Locale.US, "%.3f", value)
}

private fun formatDeg(value: Float): String {
    if (value.isNaN()) return "--"
    return value.toInt().toString()
}

private fun probabilityPercent(probability: Float): Int? {
    if (probability.isNaN()) return null
    return (probability.coerceIn(0f, 1f) * 100f).roundToInt()
}

private fun distancePercent(irisNorm: Float, threshold: Float): Int? {
    if (irisNorm.isNaN() || threshold.isNaN() || threshold <= 0f) return null
    val ratio = (irisNorm / threshold).coerceIn(0f, 2.5f)
    return (ratio * 100f).roundToInt()
}

private fun ratioPercent(value: Float, threshold: Float): Int? {
    if (value.isNaN() || threshold.isNaN() || threshold <= 0f) return null
    val ratio = (value / threshold).coerceIn(0f, 2.5f)
    return (ratio * 100f).roundToInt()
}

private fun horizontalPercentFromTilt(tiltDeg: Float): Int? {
    if (tiltDeg.isNaN()) return null
    val t = tiltDeg.coerceIn(0f, 180f)
    val fromHorizontal = min(t, 180f - t) // 0..90
    val percent = (1f - (fromHorizontal / 90f)).coerceIn(0f, 1f)
    return (percent * 100f).roundToInt()
}

private fun formatPercent(value: Int?): String {
    return value?.toString()?.plus("%") ?: "--"
}

@Composable
fun EyeHealthCard(modifier: Modifier = Modifier, isServiceEnabled: Boolean, hasCameraPermission: Boolean) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isServiceEnabled && hasCameraPermission) "護眼監測中" else "需要權限",
                style = MaterialTheme.typography.headlineSmall,
                color = if (isServiceEnabled && hasCameraPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            val description = when {
                !hasCameraPermission -> "我們需要相機權限來偵測您的眼部距離。請點擊下方按鈕進行授權。"
                !isServiceEnabled -> "請開啟無障礙服務，我們將能提供即時的姿勢提醒。"
                else -> "一切準備就緒！我們正在後台守護您的眼睛，當距離過近時會發出警報。"
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun GradientBorderContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                ),
                shape = CircleShape
            )
            .padding(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val expectedComponentName = "${context.packageName}/${service.name}"
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)

    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
