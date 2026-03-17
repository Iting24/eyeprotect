package com.example.eyeprotect.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import com.example.eyeprotect.DashboardScreen
import com.example.eyeprotect.EyeHealthAccessibilityService
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DashboardFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DashboardRoute(
                    onReCalibrate = {
                        findNavController().navigate(
                            com.example.eyeprotect.R.id.settingsFragment,
                            bundleOf("openCalibration" to true)
                        )
                    },
                    onOpenEyeExercise = {
                        findNavController().navigate(com.example.eyeprotect.R.id.eyeExerciseFragment)
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardRoute(
    onReCalibrate: () -> Unit,
    onOpenEyeExercise: () -> Unit
) {
    EyeprotectTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val prefs = remember(context) { context.getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE) }

            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasCalibrated by remember { mutableStateOf(false) }

            var isServiceEnabled by remember { mutableStateOf(false) }
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    hasCameraPermission =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    hasCalibrated =
                        prefs.contains("iris_threshold") &&
                            prefs.contains("eye_open_threshold") &&
                            prefs.contains("slouch_angle_threshold")
                    isServiceEnabled = isAccessibilityServiceEnabled(context, EyeHealthAccessibilityService::class.java)
                }
            }

            DashboardScreen(
                isServiceEnabled = isServiceEnabled,
                hasCameraPermission = hasCameraPermission,
                hasCalibrated = hasCalibrated,
                onRequestPermission = { hasCameraPermission = true },
                onReCalibrate = onReCalibrate,
                onOpenEyeExercise = onOpenEyeExercise
            )
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val expectedComponentName = "${context.packageName}/${service.name}"
    val enabledServicesSetting =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) return true
    }
    return false
}
