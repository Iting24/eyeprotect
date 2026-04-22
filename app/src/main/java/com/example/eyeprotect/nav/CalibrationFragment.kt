package com.example.eyeprotect.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.eyeprotect.CalibrationScreen
import com.example.eyeprotect.R
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.pose.PoseDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CalibrationFragment : Fragment() {

    @Inject lateinit var faceDetector: FaceDetector
    @Inject lateinit var poseDetector: PoseDetector

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CalibrationScreen(
                            faceDetector = faceDetector,
                            poseDetector = poseDetector,
                            onBack = { findNavController().returnToDashboard() },
                            onCalibrationComplete = {
                                findNavController().popBackStack(R.id.dashboardFragment, false)
                            }
                        )
                    }
                }
            }
        }
    }
}
