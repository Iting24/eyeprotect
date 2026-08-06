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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.eyeprotect.PreferenceKeys
import com.example.eyeprotect.R
import com.example.eyeprotect.monitoring.NightShiftMode
import com.example.eyeprotect.monitoring.NightShiftOverlayService
import com.example.eyeprotect.monitoring.NightShiftProfiles
import com.example.eyeprotect.ui.theme.EyeDesignTokens
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NeutralDarkPageBackground = Color(0xFF111111)
private val NeutralDarkCardTitleText = Color(0xFFFFFFFF)
private val NeutralDarkCardBodyText = Color(0xFF8E8E93)
private val NeutralAccentColor = Color(0xFFFF8C69)

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    val isDarkTheme = isSystemInDarkTheme()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (isDarkTheme) NeutralDarkPageBackground else MaterialTheme.colorScheme.background
                    ) {
                        SettingsScreen(
                            viewModel = viewModel,
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
    viewModel: SettingsViewModel,
    onOpenCalibration: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val colors = EyeDesignTokens.colors
    val cardContainerColor = colors.cardContainer
    val cardTitleTextColor = if (isDarkTheme) NeutralDarkCardTitleText else MaterialTheme.colorScheme.onSurface
    val cardBodyTextColor = if (isDarkTheme) NeutralDarkCardBodyText else MaterialTheme.colorScheme.onSurfaceVariant
    val switchColors = SwitchDefaults.colors(
        checkedTrackColor = NeutralAccentColor,
        checkedThumbColor = Color.White,
        checkedBorderColor = NeutralAccentColor
    )

    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    val monitoringRecordState by viewModel.monitoringRecords.collectAsState()

    var darkModeEnabled by remember {
        mutableStateOf(prefs.getBoolean(PreferenceKeys.PREF_DARK_MODE_ENABLED, false))
    }
    var autoEyeExerciseEnabled by remember {
        mutableStateOf(prefs.getBoolean(PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, false))
    }
    var walkDetectionEnabled by remember {
        mutableStateOf(prefs.getBoolean(PreferenceKeys.PREF_WALK_DETECTION_ENABLED, false))
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
    var nightShiftManualWarmth by remember {
        mutableStateOf(
            prefs.getFloat(
                PreferenceKeys.PREF_NIGHT_SHIFT_MANUAL_WARMTH,
                NightShiftProfiles.DEFAULT_MANUAL_WARMTH
            ).coerceIn(0f, 1f)
        )
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
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(id = R.string.title_settings), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(id = R.string.settings_subtitle),
            color = if (isDarkTheme) NeutralDarkCardBodyText else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0xFF000000).copy(alpha = 0.06f),
                    spotColor = Color(0xFF000000).copy(alpha = 0.04f)
                )
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.5.dp, colors.borderSubtle),
            colors = CardDefaults.cardColors(containerColor = cardContainerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(id = R.string.eye_settings_section_calibration),
                    style = MaterialTheme.typography.titleMedium,
                    color = cardTitleTextColor
                )
                Text(
                    text = "完成個人校正後，系統會用你的基準來判斷提醒與監測。",
                    color = cardBodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onOpenCalibration,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(id = R.string.start_calibration), color = Color.White)
                }
            }
        }

        Card(
            modifier = Modifier
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0xFF000000).copy(alpha = 0.06f),
                    spotColor = Color(0xFF000000).copy(alpha = 0.04f)
                )
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.5.dp, colors.borderSubtle),
            colors = CardDefaults.cardColors(containerColor = cardContainerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("設定", style = MaterialTheme.typography.titleMedium, color = cardTitleTextColor)

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_dark_mode_title),
                    description = stringResource(id = R.string.eye_settings_dark_mode_desc),
                    checked = darkModeEnabled,
                    titleColor = cardTitleTextColor,
                    descriptionColor = cardBodyTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        darkModeEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_DARK_MODE_ENABLED, enabled).apply()
                        AppCompatDelegate.setDefaultNightMode(
                            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                )

                Text(
                    stringResource(id = R.string.eye_settings_section_features),
                    style = MaterialTheme.typography.titleMedium,
                    color = cardTitleTextColor
                )

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_eye_exercise_title),
                    description = stringResource(id = R.string.eye_settings_eye_exercise_desc),
                    checked = autoEyeExerciseEnabled,
                    beta = true,
                    titleColor = cardTitleTextColor,
                    descriptionColor = cardBodyTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        autoEyeExerciseEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_AUTO_EYE_EXERCISE_ENABLED, enabled).apply()
                    }
                )

                if (autoEyeExerciseEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    Text(
                        text = stringResource(id = R.string.eye_settings_overlay_permission_hint),
                        color = cardBodyTextColor,
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
                    titleColor = cardTitleTextColor,
                    descriptionColor = cardBodyTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        walkDetectionEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_WALK_DETECTION_ENABLED, enabled).apply()
                    }
                )

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_night_shift_title),
                    description = stringResource(id = R.string.eye_settings_night_shift_desc),
                    checked = nightShiftEnabled,
                    beta = true,
                    titleColor = cardTitleTextColor,
                    descriptionColor = cardBodyTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        nightShiftEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_NIGHT_SHIFT_ENABLED, enabled).apply()
                    }
                )

                if (nightShiftEnabled && !hasOverlayPermission(context)) {
                    Text(
                        text = stringResource(id = R.string.eye_settings_overlay_permission_hint),
                        color = cardBodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { requestOverlayPermission(context) }) {
                        Text(stringResource(id = R.string.eye_settings_open_overlay_permission))
                    }
                }

                if (nightShiftEnabled) {
                    val recommendedWarmth = NightShiftProfiles.recommendedWarmthForLux(ambientLux)
                    val recommendedKelvin = NightShiftProfiles.kelvinForWarmth(recommendedWarmth)
                    val learnedOffset = NightShiftProfiles.learnedOffsetForLux(ambientLux, prefs)
                    val learnedAutoWarmth = NightShiftProfiles.effectiveAutoWarmth(ambientLux, prefs)
                    val learnedAutoKelvin = NightShiftProfiles.kelvinForWarmth(learnedAutoWarmth)
                    val manualKelvin = NightShiftProfiles.kelvinForWarmth(nightShiftManualWarmth)

                    Text(
                        text = stringResource(id = R.string.eye_settings_night_shift_mode_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = cardTitleTextColor
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
                        color = cardBodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (nightShiftMode == NightShiftMode.AUTO) {
                        Text(
                            text = stringResource(
                                id = R.string.eye_settings_night_shift_auto_applied,
                                learnedAutoKelvin
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cardTitleTextColor
                        )
                        Text(
                            text = stringResource(
                                id = R.string.eye_settings_night_shift_auto_learned_offset,
                                offsetDescription(learnedOffset)
                            ),
                            color = cardBodyTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                NightShiftProfiles.resetLearnedOffsets(prefs)
                                if (nightShiftEnabled && hasOverlayPermission(context)) {
                                    NightShiftOverlayService.start(context)
                                }
                            }
                        ) {
                            Text(stringResource(id = R.string.eye_settings_night_shift_reset_preference))
                        }
                        Text(
                            text = stringResource(id = R.string.eye_settings_night_shift_auto_hint),
                            color = cardBodyTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Button(
                            onClick = {
                                nightShiftManualWarmth = recommendedWarmth
                                prefs.edit()
                                    .putFloat(PreferenceKeys.PREF_NIGHT_SHIFT_MANUAL_WARMTH, recommendedWarmth)
                                    .apply()
                                NightShiftProfiles.saveLearnedOffsetForLux(
                                    prefs = prefs,
                                    lux = ambientLux,
                                    manualWarmth = recommendedWarmth
                                )
                                if (nightShiftEnabled && hasOverlayPermission(context)) {
                                    NightShiftOverlayService.start(context)
                                }
                            }
                        ) {
                            Text(stringResource(id = R.string.eye_settings_night_shift_apply_recommendation))
                        }

                        Text(
                            text = stringResource(
                                id = R.string.eye_settings_night_shift_slider_manual,
                                manualKelvin
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cardTitleTextColor
                        )
                        Text(
                            text = stringResource(id = R.string.eye_settings_night_shift_manual_learning_hint),
                            color = cardBodyTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = nightShiftManualWarmth,
                            onValueChange = { nightShiftManualWarmth = it },
                            onValueChangeFinished = {
                                prefs.edit()
                                    .putFloat(PreferenceKeys.PREF_NIGHT_SHIFT_MANUAL_WARMTH, nightShiftManualWarmth)
                                    .apply()
                                NightShiftProfiles.saveLearnedOffsetForLux(
                                    prefs = prefs,
                                    lux = ambientLux,
                                    manualWarmth = nightShiftManualWarmth
                                )
                                if (nightShiftEnabled && hasOverlayPermission(context)) {
                                    NightShiftOverlayService.start(context)
                                }
                            },
                            valueRange = 0f..1f
                        )
                    }
                }

                FeatureToggleRow(
                    title = stringResource(id = R.string.eye_settings_auto_night_title),
                    description = stringResource(id = R.string.eye_settings_auto_night_desc),
                    checked = autoNightEnabled,
                    titleColor = cardTitleTextColor,
                    descriptionColor = cardBodyTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        autoNightEnabled = enabled
                        prefs.edit().putBoolean(PreferenceKeys.PREF_AUTO_NIGHT_MODE_ENABLED, enabled).apply()
                    }
                )
            }
        }

        MonitoringRecordCard(
            recordState = monitoringRecordState,
            cardContainerColor = cardContainerColor,
            cardTitleTextColor = cardTitleTextColor,
            cardBodyTextColor = cardBodyTextColor,
            borderColor = colors.borderSubtle,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitoringRecordCard(
    recordState: MonitoringRecordUiState,
    cardContainerColor: Color,
    cardTitleTextColor: Color,
    cardBodyTextColor: Color,
    borderColor: Color,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val session = recordState.todaySessions.firstOrNull()
    val arrowRotation by animateFloatAsState(
        targetValue = if (detailsExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "monitoring_report_arrow"
    )
    Card(
        modifier = Modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0xFF000000).copy(alpha = 0.06f),
                spotColor = Color(0xFF000000).copy(alpha = 0.04f)
            )
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "監測摘要",
                style = MaterialTheme.typography.titleMedium,
                color = cardTitleTextColor
            )
            Text(
                text = "這裡只顯示最近一次已完成的監測結果，統計從開啟監測到關閉監測之間的提醒次數與總時長。",
                color = cardBodyTextColor,
                style = MaterialTheme.typography.bodySmall
            )

            if (session == null) {
                Text(
                    text = "目前還沒有已完成的監測摘要。先到首頁打開監測，關閉後就會在這裡看到結果。",
                    color = cardBodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                recordState.todaySummary?.let {
                    MonitoringDailySummaryCard(
                        summary = it,
                        titleColor = cardTitleTextColor,
                        bodyColor = cardBodyTextColor,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "今日各次監測紀錄",
                            style = MaterialTheme.typography.titleSmall,
                            color = cardTitleTextColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "›",
                            modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                            fontSize = 28.sp,
                            color = cardBodyTextColor
                        )
                    }
                    Text(
                        text = "共 ${recordState.todaySessions.size} 筆，點擊展開查看每次從開到關的紀錄。",
                        color = cardBodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    AnimatedVisibility(visible = detailsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            recordState.todaySessions.forEach { item ->
                                MonitoringSessionCard(
                                    session = item,
                                    titleColor = cardTitleTextColor,
                                    bodyColor = cardBodyTextColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitoringSessionCard(
    session: MonitoringSummaryUi,
    titleColor: Color,
    bodyColor: Color,
) {
    val totalReminderCount =
        session.tooCloseReminderCount + session.squintReminderCount + session.slouchReminderCount
    val totalCorrectionCount =
        session.tooCloseCorrectionCount + session.squintCorrectionCount + session.slouchCorrectionCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = formatSessionStartedAt(session.startedAtEpochMs),
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "總監測時長 ${formatDuration(session.durationMs)}",
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "總提醒 $totalReminderCount 次，提醒後立即改正 $totalCorrectionCount 次",
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MonitoringMetricChip("瞇眼", session.squintReminderCount, session.squintCorrectionCount)
            MonitoringMetricChip("駝背", session.slouchReminderCount, session.slouchCorrectionCount)
            MonitoringMetricChip("距離過近", session.tooCloseReminderCount, session.tooCloseCorrectionCount)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitoringDailySummaryCard(
    summary: MonitoringDailySummaryUi,
    titleColor: Color,
    bodyColor: Color,
) {
    val totalReminderCount =
        summary.tooCloseReminderCount + summary.squintReminderCount + summary.slouchReminderCount
    val totalCorrectionCount =
        summary.tooCloseCorrectionCount + summary.squintCorrectionCount + summary.slouchCorrectionCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "${summary.dateLabel} 今日總表",
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "今日監測總時數：${formatDuration(summary.totalDurationMs)}",
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "今日總提醒 $totalReminderCount 次，立即改善 $totalCorrectionCount 次",
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MonitoringMetricChip("瞇眼", summary.squintReminderCount, summary.squintCorrectionCount)
            MonitoringMetricChip("駝背", summary.slouchReminderCount, summary.slouchCorrectionCount)
            MonitoringMetricChip("過近", summary.tooCloseReminderCount, summary.tooCloseCorrectionCount)
        }
    }
}

@Composable
private fun MonitoringMetricChip(
    label: String,
    reminderCount: Int,
    correctedCount: Int,
) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text("$label：提醒 $reminderCount 次，立即改正 $correctedCount 次")
        }
    )
}

@Composable
private fun FeatureToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    titleColor: Color,
    descriptionColor: Color,
    switchColors: SwitchColors,
    beta: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
                if (beta) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Beta", color = descriptionColor) }
                    )
                }
            }
            Text(description, color = descriptionColor, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors
        )
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

private fun offsetDescription(offset: Float): String {
    return when {
        offset > 0.08f -> "偏暖"
        offset < -0.08f -> "偏冷"
        else -> "接近預設"
    }
}

private fun formatSessionStartedAt(epochMs: Long): String {
    val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return "開始時間 ${formatter.format(Date(epochMs))}"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> String.format(Locale.getDefault(), "%d 小時 %02d 分 %02d 秒", hours, minutes, seconds)
        minutes > 0L -> String.format(Locale.getDefault(), "%d 分 %02d 秒", minutes, seconds)
        else -> String.format(Locale.getDefault(), "%d 秒", seconds)
    }
}
