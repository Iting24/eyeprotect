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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

enum class AppStage {
    PERMISSION,
    CALIBRATION,
    DASHBOARD
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var faceDetector: FaceDetector
    
    @Inject
    lateinit var poseDetector: PoseDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EyeprotectTheme(darkTheme = true) {
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

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
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
            val intent = Intent(EyeHealthAccessibilityService.ACTION_UPDATE_THRESHOLDS)
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
    onReCalibrate: (() -> Unit)? = null
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GradientBorderContainer(
            modifier = Modifier.size(120.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_eye_health),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Unspecified
            )
        }

        EyeHealthCard(isServiceEnabled = isServiceEnabled, hasCameraPermission = hasCameraPermission)

        MonitoringToggleCard(
            enabled = monitoringEnabled,
            onToggle = { enabled ->
                monitoringEnabled = enabled
                prefs.edit().putBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, enabled).apply()
                val intent = Intent(EyeHealthAccessibilityService.ACTION_SET_MONITORING).apply {
                    setPackage(context.packageName)
                    putExtra(EyeHealthAccessibilityService.EXTRA_MONITORING_ENABLED, enabled)
                }
                context.sendBroadcast(intent)
            }
        )

        LiveMetricsCard(
            isServiceEnabled = isServiceEnabled,
            hasCameraPermission = hasCameraPermission,
            liveTs = liveTs,
            irisNorm = irisNorm,
            eyeOpenMin = eyeOpenMin,
            slouchScore = slouchScore,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            tiltDeg = tiltDeg,
            warningsMask = warningsMask,
            irisThreshold = irisThreshold,
            eyeOpenThreshold = eyeOpenThreshold,
            slouchThreshold = slouchThreshold
        )

        if (!hasCameraPermission) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_enable),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "授權相機權限",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (hasCalibrated) {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondary,
                    contentColor = if (isServiceEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Black
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_enable),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (isServiceEnabled) "服務運作中" else stringResource(id = R.string.enable_service_button),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (hasCameraPermission && onReCalibrate != null) {
            OutlinedButton(
                onClick = onReCalibrate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "重新校正",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MonitoringToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (enabled) "監測中" else "已暫停監測",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (enabled) "會更新指標並提醒" else "不更新指標，也不提醒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LiveMetricsCard(
    isServiceEnabled: Boolean,
    hasCameraPermission: Boolean,
    liveTs: Long,
    irisNorm: Float,
    eyeOpenMin: Float,
    slouchScore: Float,
    pitchDeg: Float,
    rollDeg: Float,
    tiltDeg: Float,
    warningsMask: Int,
    irisThreshold: Float,
    eyeOpenThreshold: Float,
    slouchThreshold: Float
) {
    val hasLive = liveTs > 0L
    val tooClose = warningsMask and 1 != 0
    val squinting = warningsMask and 2 != 0
    val slouching = warningsMask and 4 != 0
    val lying = warningsMask and 8 != 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "即時指標",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!hasCameraPermission) {
                Text(
                    text = "尚未授權相機，無法取得即時數據。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                return@Column
            }
            if (!isServiceEnabled) {
                Text(
                    text = "尚未開啟無障礙服務，背景偵測不會更新數據。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                return@Column
            }
            if (!hasLive) {
                Text(
                    text = "等待偵測資料中。請回到主畫面停留幾秒，並確保臉部入鏡。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(8.dp))

            MetricsRow(
                label = "距離(瞳距/寬)",
                value = formatFloat(irisNorm),
                threshold = if (irisThreshold.isNaN()) null else formatFloat(irisThreshold),
                warning = tooClose
            )
            MetricsRow(
                label = "咪眼(最小睜眼)",
                value = formatFloat(eyeOpenMin),
                threshold = if (eyeOpenThreshold.isNaN()) null else formatFloat(eyeOpenThreshold),
                warning = squinting
            )
            MetricsRow(
                label = "姿勢(耳肩/肩寬)",
                value = formatFloat(slouchScore),
                threshold = if (slouchThreshold.isNaN()) null else formatFloat(slouchThreshold),
                warning = slouching
            )
            MetricsRow(
                label = "躺姿(tilt/pitch/roll)",
                value = "${formatDeg(tiltDeg)} / ${formatDeg(pitchDeg)} / ${formatDeg(rollDeg)}",
                threshold = null,
                warning = lying
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("太近", tooClose)
                StatusChip("咪眼", squinting)
                StatusChip("駝背", slouching)
                StatusChip("躺著", lying)
            }
        }
    }
}

@Composable
private fun MetricsRow(label: String, value: String, threshold: String?, warning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
        val right = buildString {
            append(value)
            if (threshold != null) append(" / ").append(threshold)
        }
        Text(
            text = right,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 20.sp,
            fontWeight = if (warning) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun StatusChip(text: String, active: Boolean) {
    val container = if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = container, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = content
        )
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
