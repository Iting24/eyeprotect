package com.example.eyeprotect.nav

import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.eyeprotect.R
import com.example.eyeprotect.monitoring.EyeExerciseOverlayService
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class EyeExerciseFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        EyeExerciseScreen(onStart = { seconds -> EyeExerciseOverlayService.start(requireContext(), seconds) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EyeExerciseScreen(onStart: (Int) -> Unit) {
    val context = LocalContext.current
    var seconds by remember { mutableIntStateOf(30) }
    var needsOverlayPermission by remember { mutableStateOf(false) }

    LaunchedEffect(needsOverlayPermission) {
        if (!needsOverlayPermission) return@LaunchedEffect
        needsOverlayPermission = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(id = R.string.title_eye_exercise), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(id = R.string.eye_exercise_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { seconds = (seconds - 10).coerceAtLeast(10) }) { Text("-10s") }
            Button(onClick = { seconds = (seconds + 10).coerceAtMost(180) }) { Text("+10s") }
        }
        Text(stringResource(id = R.string.eye_exercise_timer, seconds))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                needsOverlayPermission = true
            } else {
                onStart(seconds)
            }
        }) { Text(stringResource(id = R.string.eye_exercise_start)) }
    }
}
