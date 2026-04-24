package com.example.eyeprotect

import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.eyeprotect.nav.BackToDashboardButton
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun CalibrationScreen(
    faceDetector: FaceDetector,
    poseDetector: PoseDetector,
    onBack: (() -> Unit)? = null,
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
        } catch (_: Exception) {
            imageAnalysis.clearAnalyzer()
        }
    }

    LaunchedEffect(isCalibrating) {
        if (!isCalibrating) return@LaunchedEffect

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

        val distanceThreshold = CalibrationPrefs.sanitizeIrisThreshold(irisMedian * 1.15f)
        val squintThreshold = CalibrationPrefs.sanitizeEyeOpenThreshold(eyeMedian * 0.7f)
        val slouchThreshold = CalibrationPrefs.sanitizeSlouchThreshold((slouchMedian * 0.75).toFloat())

        val prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(CalibrationPrefs.KEY_IRIS_THRESHOLD, distanceThreshold)
            .putFloat(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD, squintThreshold)
            .putFloat(CalibrationPrefs.KEY_SLOUCH_THRESHOLD, slouchThreshold)
            .apply()

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

        CalibrationWhyCard()

        Box(
            modifier = Modifier
                .size(260.dp)
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

        CalibrationProgressCard(
            isCalibrating = isCalibrating,
            irisCount = irisDistances.size,
            eyeCount = eyeOpenMins.size,
            postureCount = slouchAngles.size,
            hasError = calibrationError != null
        )

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

@Composable
private fun CalibrationWhyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "讓提醒變成適合你的提醒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "每個人的舒適距離、睜眼幅度和自然坐姿都不同。校正會先記住你在放鬆狀態下的基準，之後提醒才不會太敏感或太遲鈍。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CalibrationSignalPill("距離", Color(0xFFDDEBFF), Color(0xFF2F80ED), Modifier.weight(1f))
                CalibrationSignalPill("睜眼", Color(0xFFE3F3FF), Color(0xFF2C7FB8), Modifier.weight(1f))
                CalibrationSignalPill("坐姿", Color(0xFFE7F6EB), Color(0xFF2E7D4F), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalibrationSignalPill(label: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when {
                    hasError -> "需要再調整一次"
                    isCalibrating -> "正在建立個人基準"
                    else -> "請保持舒適坐姿並注視螢幕"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            CalibrationProgressRow("眼睛距離基準", irisCount, Color(0xFF77B7FF))
            CalibrationProgressRow("睜眼程度基準", eyeCount, Color(0xFFA6D8FF))
            CalibrationProgressRow("肩頸姿勢基準", postureCount, Color(0xFFBDECCF))
        }
    }
}

@Composable
private fun CalibrationProgressRow(label: String, count: Int, color: Color) {
    val progress = min(count / 6f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
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
