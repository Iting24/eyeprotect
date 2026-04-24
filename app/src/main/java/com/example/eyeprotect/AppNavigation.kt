package com.example.eyeprotect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector

enum class AppStage {
    PERMISSION,
    CALIBRATION,
    DASHBOARD
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    faceDetector: FaceDetector,
    poseDetector: PoseDetector
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCalibrated by remember {
        mutableStateOf(CalibrationPrefs.hasValidCalibration(prefs))
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

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasCameraPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            hasCalibrated = CalibrationPrefs.hasValidCalibration(prefs)
            isServiceEnabled = isAccessibilityServiceEnabled(context, EyeHealthAccessibilityService::class.java)

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
                onReCalibrate = { currentStage = AppStage.CALIBRATION }
            )
        }
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
