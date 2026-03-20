package com.example.eyeprotect.visiontool.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.camera.view.PreviewView
import com.example.eyeprotect.visiontool.analysis.ColorMaskAnalyzer
import com.example.eyeprotect.visiontool.components.CameraPreview
import com.example.eyeprotect.visiontool.components.ColorMaskedPatternOverlay
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import com.example.eyeprotect.visiontool.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val currentMode by viewModel.currentMode.collectAsState()
    val alpha by viewModel.textureAlpha.collectAsState()
    val maskBitmap by viewModel.maskBitmap.collectAsState()
    val maskTransform by viewModel.maskTransform.collectAsState()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val activeColor = when (currentMode) {
        AssistMode.RED -> Color(0xFFE53935)
        AssistMode.GREEN -> Color(0xFF43A047)
        AssistMode.BLUE -> Color(0xFF1E88E5)
        AssistMode.YELLOW -> Color(0xFFFDD835)
        AssistMode.ALL -> MaterialTheme.colorScheme.primary
        AssistMode.NONE -> MaterialTheme.colorScheme.outline
    }

    val analyzer = remember {
        ColorMaskAnalyzer(
            initialMode = currentMode,
            onMaskReady = { viewModel.setMaskBitmap(it) },
            downscaleStep = 2,
            blurPasses = 1,
            blurThreshold = 4
        )
    }

    LaunchedEffect(currentMode) {
        analyzer.setMode(currentMode)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            analyzer = analyzer,
            onPreviewViewReady = { previewView = it },
            onMaskTransform = { matrix, w, h -> viewModel.setMaskTransform(matrix, w, h) }
        )

        ColorMaskedPatternOverlay(
            modifier = Modifier.fillMaxSize(),
            maskBitmap = maskBitmap,
            mode = currentMode,
            patternAlpha = alpha,
            patternColor = activeColor,
            previewView = previewView,
            maskTransform = maskTransform
        )

        Box(
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.Center)
                .border(
                    width = 2.dp,
                    color = activeColor.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(16.dp)
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .width(48.dp)
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = alpha,
                onValueChange = { viewModel.setAlpha(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = activeColor,
                    activeTrackColor = activeColor.copy(alpha = 0.7f)
                ),
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = 270f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    }
                    .width(300.dp)
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp
        ) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(AssistMode.values()) { mode ->
                    val isSelected = currentMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(mode.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (mode == AssistMode.NONE) MaterialTheme.colorScheme.secondaryContainer else activeColor.copy(alpha = 0.2f),
                            selectedLabelColor = if (mode == AssistMode.NONE) MaterialTheme.colorScheme.onSecondaryContainer else activeColor
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (isSelected) activeColor else MaterialTheme.colorScheme.outline,
                            enabled = true,
                            selected = isSelected
                        )
                    )
                }
            }
        }
    }
}
