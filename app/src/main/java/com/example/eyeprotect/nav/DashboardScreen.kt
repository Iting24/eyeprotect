package com.example.eyeprotect.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.eyeprotect.monitoring.MonitoringForegroundService

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.state.collectAsState()
    val isRunning = uiState.isRunning
    val metrics = uiState.metrics

    var pendingStart by remember { mutableStateOf(false) }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingStart = true
    }
    val requestPostNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingStart = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = "VisionGuard AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            HealthRing(progress = computeHealthIndex(metrics.warningsMask, isRunning))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("視覺健康指數", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = statusText(isRunning, metrics.warningsMask),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("背景監測（前景服務）", fontWeight = FontWeight.Medium)
            Switch(
                checked = isRunning,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        startMonitoringWithPermissions(
                            context = context,
                            onRequestCamera = { requestCameraPermission.launch(Manifest.permission.CAMERA) },
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    pendingStart = true
                                }
                            },
                            onStart = { pendingStart = true }
                        )
                    } else {
                        MonitoringForegroundService.stop(context)
                    }
                }
            )
        }

        LaunchedEffect(pendingStart) {
            if (pendingStart) {
                pendingStart = false
                MonitoringForegroundService.start(context)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Text(
                text = "提示：眼球體操需要「在其他應用程式上層顯示」權限。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { requestOverlayPermission(context) }) {
                Text("前往開啟懸浮窗權限")
            }
        }
    }
}

@Composable
private fun HealthRing(progress: Float, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0f, 1f)
    val ringColor = if (clamped >= 0.75f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.size(92.dp)) {
        val stroke = 10.dp.toPx()
        val diameter = size.minDimension
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * clamped,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

private fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun startMonitoringWithPermissions(
    context: Context,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStart: () -> Unit
) {
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (!hasCamera) {
        onRequestCamera()
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val hasNotif =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasNotif) {
            onRequestNotifications()
            return
        }
    }

    onStart()
}

private fun computeHealthIndex(warningsMask: Int, running: Boolean): Float {
    if (!running) return 0.25f
    val bits = listOf(1, 2, 4, 8).count { (warningsMask and it) != 0 }
    return (1.0f - bits * 0.18f).coerceIn(0.1f, 1.0f)
}

private fun statusText(running: Boolean, warningsMask: Int): String {
    if (!running) return "未監測"
    val warnings = mutableListOf<String>()
    if ((warningsMask and 1) != 0) warnings.add("距離過近")
    if ((warningsMask and 2) != 0) warnings.add("瞇眼")
    if ((warningsMask and 4) != 0) warnings.add("駝背")
    if ((warningsMask and 8) != 0) warnings.add("躺姿")
    return if (warnings.isEmpty()) "監測中：姿勢良好" else "監測中：${warnings.joinToString("，")}"
}

