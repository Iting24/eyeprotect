package com.example.eyeprotect.nav

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.eyeprotect.R
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SettingsScreen(
                            onOpenCalibration = {
                                findNavController().navigate(R.id.calibrationFragment)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onOpenCalibration: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(com.example.eyeprotect.PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var darkModeEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_DARK_MODE_ENABLED, false))
    }
    var autoEyeExerciseEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, false))
    }
    var walkDetectionEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_WALK_DETECTION_ENABLED, false))
    }
    var autoNightEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(id = R.string.title_settings), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(id = R.string.settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(id = R.string.eye_settings_section_calibration), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "更新你的眼睛距離、睜眼程度與坐姿基準。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onOpenCalibration) { Text(stringResource(id = R.string.start_calibration)) }
            }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("外觀", style = MaterialTheme.typography.titleMedium)

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_dark_mode_title),
                    description = stringResource(id = R.string.eye_settings_dark_mode_desc),
                    checked = darkModeEnabled,
                    onCheckedChange = { enabled ->
                        darkModeEnabled = enabled
                        prefs.edit().putBoolean(com.example.eyeprotect.PreferenceKeys.PREF_DARK_MODE_ENABLED, enabled).apply()
                        AppCompatDelegate.setDefaultNightMode(
                            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                )

                Text(stringResource(id = R.string.eye_settings_section_features), style = MaterialTheme.typography.titleMedium)

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_eye_exercise_title),
                    description = stringResource(id = R.string.eye_settings_eye_exercise_desc),
                    checked = autoEyeExerciseEnabled,
                    beta = true,
                    onCheckedChange = { enabled ->
                        autoEyeExerciseEnabled = enabled
                        prefs.edit().putBoolean(com.example.eyeprotect.PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, enabled).apply()
                    }
                )

                if (autoEyeExerciseEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    Text(
                        text = stringResource(id = R.string.eye_settings_overlay_permission_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { requestOverlayPermission(context) }) {
                        Text(stringResource(id = R.string.eye_settings_open_overlay_permission))
                    }
                }

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_walk_detection_title),
                    description = stringResource(id = R.string.eye_settings_walk_detection_desc),
                    checked = walkDetectionEnabled,
                    beta = true,
                    onCheckedChange = { enabled ->
                        walkDetectionEnabled = enabled
                        prefs.edit().putBoolean(com.example.eyeprotect.PreferenceKeys.PREF_WALK_DETECTION_ENABLED, enabled).apply()
                    }
                )

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_auto_night_title),
                    description = stringResource(id = R.string.eye_settings_auto_night_desc),
                    checked = autoNightEnabled,
                    onCheckedChange = { enabled ->
                        autoNightEnabled = enabled
                        prefs.edit().putBoolean(com.example.eyeprotect.PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, enabled).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun FeatureToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    beta: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (beta) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Beta") }
                    )
                }
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

// Preference keys are shared with the background monitoring service.
