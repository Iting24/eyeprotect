package com.example.eyeprotect.nav

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.eyeprotect.PreferenceKeys
import com.example.eyeprotect.R
import com.example.eyeprotect.monitoring.NightShiftMode
import com.example.eyeprotect.monitoring.NightShiftOverlayService
import com.example.eyeprotect.monitoring.NightShiftProfiles
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
                            onBack = { findNavController().returnToDashboard() },
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
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(com.example.eyeprotect.PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }

    var autoEyeExerciseEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, false))
    }
    var walkDetectionEnabled by remember {
        mutableStateOf(prefs.getBoolean(com.example.eyeprotect.PreferenceKeys.PREF_WALK_DETECTION_ENABLED, false))
    }
    var autoNightEnabled by remember {
        mutableStateOf(prefs.getBoolean(PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, false))
    }
    var nightShiftEnabled by remember {
        mutableStateOf(prefs.getBoolean(PreferenceKeys.PREF_NIGHT_SHIFT_ENABLED, false))
    }
    var nightShiftMode by remember {
        mutableStateOf(
            NightShiftMode.fromPref(
                prefs.getString(PreferenceKeys.PREF_NIGHT_SHIFT_MODE, NightShiftMode.AUTO.prefValue)
            )
        )
    }
    var nightShiftWarmth by remember {
        mutableStateOf(prefs.getFloat(PreferenceKeys.PREF_NIGHT_SHIFT_WARMTH, 0.5f).coerceIn(0f, 1f))
    }
    var ambientLux by remember { mutableStateOf(80f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                ambientLux = event?.values?.firstOrNull() ?: ambientLux
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    LaunchedEffect(nightShiftEnabled) {
        if (nightShiftEnabled && hasOverlayPermission(context)) {
            NightShiftOverlayService.start(context)
        } else if (!nightShiftEnabled) {
            NightShiftOverlayService.stop(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BackToDashboardButton(onBack = onBack)
        Text(stringResource(id = R.string.title_settings), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(id = R.string.settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
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
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                    title = stringResource(id = R.string.eye_settings_night_shift_title),
                    description = stringResource(id = R.string.eye_settings_night_shift_desc),
                    checked = nightShiftEnabled,
                    beta = true,
                    onCheckedChange = { enabled ->
                        nightShiftEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_NIGHT_SHIFT_ENABLED, enabled).apply()
                    }
                )

                if (nightShiftEnabled && !hasOverlayPermission(context)) {
                    Text(
                        text = stringResource(id = R.string.eye_settings_overlay_permission_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { requestOverlayPermission(context) }) {
                        Text(stringResource(id = R.string.eye_settings_open_overlay_permission))
                    }
                }

                if (nightShiftEnabled) {
                    val recommendedWarmth = NightShiftProfiles.recommendedWarmthForLux(ambientLux)
                    val recommendedKelvin = NightShiftProfiles.kelvinForWarmth(recommendedWarmth)
                    val currentKelvin = NightShiftProfiles.kelvinForWarmth(nightShiftWarmth)
                    val sliderLabel =
                        if (nightShiftMode == NightShiftMode.AUTO) {
                            stringResource(id = R.string.eye_settings_night_shift_slider_auto, currentKelvin)
                        } else {
                            stringResource(id = R.string.eye_settings_night_shift_slider_manual, currentKelvin)
                        }

                    Text(
                        text = stringResource(id = R.string.eye_settings_night_shift_mode_title),
                        style = MaterialTheme.typography.titleSmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = nightShiftMode == NightShiftMode.AUTO,
                            onClick = {
                                nightShiftMode = NightShiftMode.AUTO
                                prefs.edit()
                                    .putString(PreferenceKeys.PREF_NIGHT_SHIFT_MODE, NightShiftMode.AUTO.prefValue)
                                    .apply()
                                if (nightShiftEnabled && hasOverlayPermission(context)) {
                                    NightShiftOverlayService.start(context)
                                }
                            },
                            label = { Text(stringResource(id = R.string.eye_settings_night_shift_mode_auto)) }
                        )
                        FilterChip(
                            selected = nightShiftMode == NightShiftMode.MANUAL,
                            onClick = {
                                nightShiftMode = NightShiftMode.MANUAL
                                prefs.edit()
                                    .putString(PreferenceKeys.PREF_NIGHT_SHIFT_MODE, NightShiftMode.MANUAL.prefValue)
                                    .apply()
                                if (nightShiftEnabled && hasOverlayPermission(context)) {
                                    NightShiftOverlayService.start(context)
                                }
                            },
                            label = { Text(stringResource(id = R.string.eye_settings_night_shift_mode_manual)) }
                        )
                    }

                    Text(
                        text = stringResource(
                            id = R.string.eye_settings_night_shift_recommendation,
                            recommendedKelvin,
                            ambientLux.toInt()
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = {
                            nightShiftWarmth = recommendedWarmth
                            prefs.edit().putFloat(PreferenceKeys.PREF_NIGHT_SHIFT_WARMTH, recommendedWarmth).apply()
                            if (nightShiftEnabled && hasOverlayPermission(context)) {
                                NightShiftOverlayService.start(context)
                            }
                        }
                    ) {
                        Text(stringResource(id = R.string.eye_settings_night_shift_apply_recommendation))
                    }

                    Text(
                        text = sliderLabel,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = nightShiftWarmth,
                        onValueChange = { nightShiftWarmth = it },
                        onValueChangeFinished = {
                            prefs.edit().putFloat(PreferenceKeys.PREF_NIGHT_SHIFT_WARMTH, nightShiftWarmth).apply()
                            if (nightShiftEnabled && hasOverlayPermission(context)) {
                                NightShiftOverlayService.start(context)
                            }
                        },
                        valueRange = 0f..1f
                    )
                }

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_auto_night_title),
                    description = stringResource(id = R.string.eye_settings_auto_night_desc),
                    checked = autoNightEnabled,
                    onCheckedChange = { enabled ->
                        autoNightEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, enabled).apply()
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

private fun hasOverlayPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

// Preference keys are shared with the background monitoring service.
