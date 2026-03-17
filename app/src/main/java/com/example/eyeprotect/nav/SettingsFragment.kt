package com.example.eyeprotect.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.eyeprotect.CalibrationScreen
import com.example.eyeprotect.R
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject lateinit var faceDetector: FaceDetector
    @Inject lateinit var poseDetector: PoseDetector

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val openCalibrationArg = arguments?.getBoolean("openCalibration", false) ?: false
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme(darkTheme = true) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SettingsScreen(
                            faceDetector = faceDetector,
                            poseDetector = poseDetector,
                            openCalibrationInitially = openCalibrationArg
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    faceDetector: FaceDetector,
    poseDetector: PoseDetector,
    openCalibrationInitially: Boolean
) {
    var showCalibration by remember(openCalibrationInitially) { mutableStateOf(openCalibrationInitially) }
    if (showCalibration) {
        CalibrationScreen(
            faceDetector = faceDetector,
            poseDetector = poseDetector,
            onCalibrationComplete = { showCalibration = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(id = R.string.title_settings), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(id = R.string.settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { showCalibration = true }) { Text(stringResource(id = R.string.start_calibration)) }
    }
}
