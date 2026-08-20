package com.example.eyeprotect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.eyeprotect.nav.BackToDashboardButton
import com.example.eyeprotect.ui.theme.EyeDesignTokens
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetector
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlin.math.min

private val PortraitCalibrationContentMaxWidth = 420.dp
private val PortraitCalibrationPreviewSize = 260.dp
private val LandscapeCalibrationPreviewMaxSize = 320.dp

@Composable
fun CalibrationScreen(
    faceDetector: FaceDetector,
    poseDetector: PoseDetector,
    onBack: (() -> Unit)? = null,
    onCalibrationComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember(context) { context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    var currentOrientation by remember {
        mutableIntStateOf(CalibrationPrefs.currentDeviceOrientation(context))
    }
    val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE

    var countdown by remember { mutableIntStateOf(3) }
    var isCalibrating by remember { mutableStateOf(false) }
    var calibrationTargetOrientation by remember { mutableStateOf<Int?>(null) }
    var calibrationError by remember { mutableStateOf<String?>(null) }
    var calibrationNotice by remember { mutableStateOf<String?>(null) }
    var calibrationStatusVersion by remember { mutableIntStateOf(0) }
    val missingBuckets = remember(calibrationStatusVersion) { CalibrationPrefs.missingBuckets(prefs) }
    val requiredBucket = if (missingBuckets.size == 1) missingBuckets.first() else null
    val requiredOrientation = requiredBucket?.toConfigurationOrientation()
    val displayedOrientation = calibrationTargetOrientation ?: requiredOrientation ?: currentOrientation
    val displayedIsLandscape = displayedOrientation == Configuration.ORIENTATION_LANDSCAPE

    val irisDistances = remember { mutableStateListOf<Float>() }
    val eyeOpenMins = remember { mutableStateListOf<Float>() }
    val slouchAngles = remember { mutableStateListOf<Double>() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    DisposableEffect(context) {
        val orientationListener = object : OrientationEventListener(context.applicationContext, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentOrientation = when {
                    orientation in 45..134 || orientation in 225..314 -> Configuration.ORIENTATION_LANDSCAPE
                    else -> Configuration.ORIENTATION_PORTRAIT
                }
            }
        }

        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }

        onDispose { orientationListener.disable() }
    }

    DisposableEffect(activity, requiredOrientation) {
        val targetRequestedOrientation = when (requiredOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val previousOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = targetRequestedOrientation
        }

        onDispose {
            if (activity != null) {
                activity.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val previewView = remember { PreviewView(context) }
    val metricDetector = remember { PostureAndEyeDetector() }

    LaunchedEffect(previewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer = object : ImageAnalysis.Analyzer {
            @androidx.camera.core.ExperimentalGetImage
            override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
                val mediaImage = imageProxy.image
                if (mediaImage == null || !isCalibrating || countdown <= 0) {
                    imageProxy.close()
                    return
                }

                try {
                    val result = detectBestCalibrationFrame(
                        mediaImage = mediaImage,
                        sourceWidth = imageProxy.width,
                        sourceHeight = imageProxy.height,
                        primaryRotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        faceDetector = faceDetector,
                        poseDetector = poseDetector
                    )

                    result.face?.let { face ->
                        metricDetector.computeNormalizedIrisDistance(face, result.imageWidth)?.let(irisDistances::add)
                        metricDetector.computeEyeOpenMin(face)?.let(eyeOpenMins::add)
                    }
                    result.pose?.let { pose ->
                        metricDetector.computePostureRatio(pose)?.let(slouchAngles::add)
                    }
                } finally {
                    imageProxy.close()
                }
            }
        }

        imageAnalysis.setAnalyzer(analysisExecutor, analyzer)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (_: Exception) {
            imageAnalysis.clearAnalyzer()
        }
    }

    LaunchedEffect(isCalibrating, calibrationTargetOrientation) {
        if (!isCalibrating) return@LaunchedEffect
        val targetOrientation = calibrationTargetOrientation ?: currentOrientation

        calibrationError = null
        calibrationNotice = null
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
            val missing = buildList {
                if (irisMedian == null) add("眼距")
                if (eyeMedian == null) add("睜眼程度")
                if (slouchMedian == null) add("姿勢")
            }
            calibrationError = "這次校正還少了 ${missing.joinToString("、")} 資料，請保持臉和肩膀都在畫面內後再試一次。"
            isCalibrating = false
            return@LaunchedEffect
        }

        CalibrationPrefs.saveThresholds(
            prefs = prefs,
            orientation = targetOrientation,
            thresholds = CalibrationPrefs.Thresholds(
                irisThreshold = CalibrationPrefs.sanitizeIrisThreshold(irisMedian * 1.15f),
                eyeOpenThreshold = CalibrationPrefs.sanitizeEyeOpenThreshold(eyeMedian * 0.7f),
                slouchThreshold = CalibrationPrefs.sanitizeSlouchThreshold((slouchMedian * 0.75).toFloat())
            )
        )

        val resolvedThresholds = CalibrationPrefs.resolveThresholds(prefs, targetOrientation)
        if (resolvedThresholds != null) {
            val intent = Intent(EyeHealthAccessibilityService.ACTION_UPDATE_THRESHOLDS).apply {
                setPackage(context.packageName)
                putExtra("irisDistance", resolvedThresholds.irisThreshold)
                putExtra("eyeOpenThreshold", resolvedThresholds.eyeOpenThreshold)
                putExtra("slouchAngleThreshold", resolvedThresholds.slouchThreshold)
            }
            context.sendBroadcast(intent)
        }

        isCalibrating = false
        calibrationTargetOrientation = null
        calibrationStatusVersion += 1

        val refreshedMissingBuckets = CalibrationPrefs.missingBuckets(prefs)
        if (refreshedMissingBuckets.isEmpty()) {
            calibrationNotice = null
            onCalibrationComplete()
        } else {
            calibrationNotice = buildString {
                append("已完成")
                append(if (targetOrientation == Configuration.ORIENTATION_LANDSCAPE) "橫向" else "直向")
                append("校正，還需要完成")
                append(
                    refreshedMissingBuckets.joinToString("與") {
                        if (it == CalibrationPrefs.OrientationBucket.PORTRAIT) "直向" else "橫向"
                    }
                )
                append("校正。")
            }
        }
    }

    LaunchedEffect(currentOrientation, isCalibrating, calibrationTargetOrientation) {
        val targetOrientation = calibrationTargetOrientation ?: return@LaunchedEffect
        if (!isCalibrating) return@LaunchedEffect
        if (currentOrientation == targetOrientation) return@LaunchedEffect

        calibrationError = if (targetOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            "目前正在進行橫向校正，請把手機維持橫拿後再重新開始。"
        } else {
            "目前正在進行直向校正，請把手機維持直拿後再重新開始。"
        }
        calibrationNotice = null
        isCalibrating = false
        calibrationTargetOrientation = null
        countdown = 3
        irisDistances.clear()
        eyeOpenMins.clear()
        slouchAngles.clear()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        onBack?.let { BackToDashboardButton(onBack = it, modifier = Modifier.align(Alignment.Start)) }

        Text(
            text = "個人基準校正",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (displayedIsLandscape) "目前要完成的是橫向校正" else "目前要完成的是直向校正",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        CalibrationRequirementCard(
            currentOrientation = displayedOrientation,
            missingBuckets = missingBuckets,
            modifier = Modifier.widthIn(max = PortraitCalibrationContentMaxWidth)
        )

        CalibrationStatusCard(
            portraitDone = CalibrationPrefs.OrientationBucket.PORTRAIT !in missingBuckets,
            landscapeDone = CalibrationPrefs.OrientationBucket.LANDSCAPE !in missingBuckets,
            modifier = Modifier.widthIn(max = PortraitCalibrationContentMaxWidth)
        )

        calibrationNotice?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wideLayout = displayedIsLandscape && maxWidth >= 700.dp

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(0.95f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        CalibrationPreview(
                            previewView = previewView,
                            isCalibrating = isCalibrating,
                            countdown = countdown,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = LandscapeCalibrationPreviewMaxSize),
                            preferredSize = LandscapeCalibrationPreviewMaxSize
                        )
                        CalibrationActionButton(
                            isCalibrating = isCalibrating,
                            onClick = {
                                if (requiredOrientation != null && currentOrientation != requiredOrientation) {
                                    calibrationError =
                                        if (requiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                                            "請先把手機轉成橫向，再開始橫向校正。"
                                        } else {
                                            "請先把手機轉成直向，再開始直向校正。"
                                        }
                                    calibrationNotice = null
                                    return@CalibrationActionButton
                                }
                                calibrationTargetOrientation = requiredOrientation ?: currentOrientation
                                isCalibrating = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = LandscapeCalibrationPreviewMaxSize)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CalibrationWhyCard()
                        CalibrationProgressCard(
                            isCalibrating = isCalibrating,
                            irisCount = irisDistances.size,
                            eyeCount = eyeOpenMins.size,
                            postureCount = slouchAngles.size,
                            hasError = calibrationError != null
                        )
                        calibrationError?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Start,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(modifier = Modifier.widthIn(max = PortraitCalibrationContentMaxWidth)) {
                        CalibrationWhyCard()
                    }
                    CalibrationPreview(
                        previewView = previewView,
                        isCalibrating = isCalibrating,
                        countdown = countdown,
                        modifier = Modifier.size(PortraitCalibrationPreviewSize),
                        preferredSize = PortraitCalibrationPreviewSize
                    )
                    Box(modifier = Modifier.widthIn(max = PortraitCalibrationContentMaxWidth)) {
                        CalibrationProgressCard(
                            isCalibrating = isCalibrating,
                            irisCount = irisDistances.size,
                            eyeCount = eyeOpenMins.size,
                            postureCount = slouchAngles.size,
                            hasError = calibrationError != null
                        )
                    }
                    CalibrationActionButton(
                        isCalibrating = isCalibrating,
                        onClick = {
                            if (requiredOrientation != null && currentOrientation != requiredOrientation) {
                                calibrationError =
                                    if (requiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        "請先把手機轉成橫向，再開始橫向校正。"
                                    } else {
                                        "請先把手機轉成直向，再開始直向校正。"
                                    }
                                calibrationNotice = null
                                return@CalibrationActionButton
                            }
                            calibrationTargetOrientation = requiredOrientation ?: currentOrientation
                            isCalibrating = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = PortraitCalibrationContentMaxWidth)
                    )
                    calibrationError?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.widthIn(max = PortraitCalibrationContentMaxWidth),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationRequirementCard(
    currentOrientation: Int,
    missingBuckets: List<CalibrationPrefs.OrientationBucket>,
    modifier: Modifier = Modifier
) {
    val colors = EyeDesignTokens.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        colors = CardDefaults.cardColors(containerColor = colors.cardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "開始監測前需要完成兩套校正",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    "目前校正頁要求手機為橫向。"
                } else {
                    "目前校正頁要求手機為直向。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (missingBuckets.isNotEmpty()) {
                Text(
                    text = "還缺：${missingBuckets.joinToString("、") { if (it == CalibrationPrefs.OrientationBucket.PORTRAIT) "直向校正" else "橫向校正" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CalibrationStatusCard(
    portraitDone: Boolean,
    landscapeDone: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = EyeDesignTokens.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        colors = CardDefaults.cardColors(containerColor = colors.cardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "校正進度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            CalibrationStatusRow("直向校正", portraitDone)
            CalibrationStatusRow("橫向校正", landscapeDone)
        }
    }
}

@Composable
private fun CalibrationStatusRow(
    label: String,
    done: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (done) "已完成" else "未完成",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (done) Color(0xFF2E7D4F) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CalibrationPreview(
    previewView: PreviewView,
    isCalibrating: Boolean,
    countdown: Int,
    modifier: Modifier = Modifier,
    preferredSize: Dp = LandscapeCalibrationPreviewMaxSize
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val previewSize = if (maxWidth < preferredSize) maxWidth else preferredSize
        Box(
            modifier = Modifier
                .size(previewSize)
                .clip(CircleShape)
                .border(
                    width = 3.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF77B7FF), Color(0xFFBFE8D0), Color(0xFFFFD98A))
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
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
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
    }
}

@Composable
private fun CalibrationActionButton(
    isCalibrating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8FD3FF), contentColor = Color(0xFF083B5C)),
        shape = RoundedCornerShape(30.dp),
        enabled = !isCalibrating
    ) {
        Text(
            text = if (isCalibrating) "校正進行中..." else "開始校正",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun CalibrationPrefs.OrientationBucket.toConfigurationOrientation(): Int {
    return if (this == CalibrationPrefs.OrientationBucket.LANDSCAPE) {
        Configuration.ORIENTATION_LANDSCAPE
    } else {
        Configuration.ORIENTATION_PORTRAIT
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
private fun CalibrationWhyCard() {
    val colors = EyeDesignTokens.colors
    Card(
        modifier = Modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.04f)
            )
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        colors = CardDefaults.cardColors(containerColor = colors.cardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "讓提醒更貼近你的使用方式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "請把臉和肩膀都維持在鏡頭中，系統會記錄眼距、睜眼程度和姿勢，建立這個方向專屬的提醒基準。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CalibrationSignalPill("眼距", Color(0xFFDDEBFF), Color(0xFF2F80ED), Modifier.weight(1f))
                CalibrationSignalPill("睜眼", Color(0xFFE3F3FF), Color(0xFF2C7FB8), Modifier.weight(1f))
                CalibrationSignalPill("姿勢", Color(0xFFE7F6EB), Color(0xFF2E7D4F), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalibrationSignalPill(
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = foreground, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}

@Composable
private fun CalibrationProgressCard(
    isCalibrating: Boolean,
    irisCount: Int,
    eyeCount: Int,
    postureCount: Int,
    hasError: Boolean
) {
    val colors = EyeDesignTokens.colors
    Card(
        modifier = Modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.04f)
            )
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        colors = CardDefaults.cardColors(containerColor = colors.cardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when {
                    hasError -> "需要再調整一次"
                    isCalibrating -> "正在蒐集校正資料"
                    else -> "準備開始校正"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            CalibrationProgressRow("眼距樣本", irisCount, Color(0xFF77B7FF))
            CalibrationProgressRow("睜眼樣本", eyeCount, Color(0xFFA6D8FF))
            CalibrationProgressRow("姿勢樣本", postureCount, Color(0xFFBDECCF))
        }
    }
}

@Composable
private fun CalibrationProgressRow(label: String, count: Int, color: Color) {
    val progress = min(count / 6f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (count >= 6) "已取得" else "收集中",
                fontSize = 12.sp,
                color = if (count >= 6) Color(0xFF2E7D4F) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
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

private data class CalibrationFrameResult(
    val face: Face?,
    val pose: Pose?,
    val imageWidth: Int,
    val imageHeight: Int
)

private fun detectBestCalibrationFrame(
    mediaImage: android.media.Image,
    sourceWidth: Int,
    sourceHeight: Int,
    primaryRotationDegrees: Int,
    faceDetector: FaceDetector,
    poseDetector: PoseDetector
): CalibrationFrameResult {
    val candidateRotations = buildList {
        add(primaryRotationDegrees)
        addAll(listOf(0, 90, 270, 180))
    }.distinct()

    var firstResult: CalibrationFrameResult? = null
    for (rotationDegrees in candidateRotations) {
        val result = detectCalibrationFrame(
            mediaImage = mediaImage,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            faceDetector = faceDetector,
            poseDetector = poseDetector
        )
        if (firstResult == null) firstResult = result
        if (result.face != null) return result
    }

    return firstResult ?: CalibrationFrameResult(
        face = null,
        pose = null,
        imageWidth = sourceWidth,
        imageHeight = sourceHeight
    )
}

private fun detectCalibrationFrame(
    mediaImage: android.media.Image,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    faceDetector: FaceDetector,
    poseDetector: PoseDetector
): CalibrationFrameResult {
    val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
    val imageWidth = if (rotationDegrees % 180 == 0) sourceWidth else sourceHeight
    val imageHeight = if (rotationDegrees % 180 == 0) sourceHeight else sourceWidth

    val faceTask = faceDetector.process(image)
    val poseTask = poseDetector.process(image)
    Tasks.whenAllComplete(faceTask, poseTask)

    val face = runCatching { Tasks.await(faceTask).firstOrNull() }.getOrNull()
    val pose = runCatching { Tasks.await(poseTask) }.getOrNull()

    return CalibrationFrameResult(
        face = face,
        pose = pose,
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )
}
