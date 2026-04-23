package com.example.eyeprotect.visiontool.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.eyeprotect.visiontool.analysis.ColorMaskAnalyzer
import com.example.eyeprotect.visiontool.components.CameraPreview
import com.example.eyeprotect.visiontool.components.ColorMaskedPatternOverlay
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import com.example.eyeprotect.visiontool.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val selectedModes by viewModel.selectedModes.collectAsState()
    val alpha by viewModel.textureAlpha.collectAsState()
    val maskBitmap by viewModel.maskBitmap.collectAsState()
    val maskTransform by viewModel.maskTransform.collectAsState()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val roiSizePx = with(LocalDensity.current) { 250.dp.toPx() }

    val activeMode = selectedModes.firstOrNull { it != AssistMode.NONE } ?: AssistMode.NONE
    val hasMultiple = selectedModes.count { it != AssistMode.NONE } > 1

    val activeColor = when (activeMode) {
        AssistMode.RED -> Color(0xFFFF3B30)
        AssistMode.GREEN -> Color(0xFF43A047)
        AssistMode.BLUE -> Color(0xFF1E88E5)
        AssistMode.YELLOW -> Color(0xFFFFE600)
        AssistMode.ORANGE -> Color(0xFFFF9800)
        AssistMode.BROWN -> Color(0xFF6D4C41)
        AssistMode.INDIGO -> Color(0xFF3949AB)
        AssistMode.PURPLE -> Color(0xFF8E24AA)
        AssistMode.GRAY -> Color(0xFF757575)
        AssistMode.NONE -> MaterialTheme.colorScheme.outline
    }

    val patternColor = if (
        hasMultiple ||
            activeMode == AssistMode.BLUE ||
            activeMode == AssistMode.YELLOW ||
            activeMode == AssistMode.GREEN ||
            activeMode == AssistMode.GRAY ||
            activeMode == AssistMode.RED ||
            activeMode == AssistMode.INDIGO ||
            activeMode == AssistMode.PURPLE ||
            activeMode == AssistMode.ORANGE ||
            activeMode == AssistMode.BROWN
    ) {
        MaterialTheme.colorScheme.onSurface
    } else {
        activeColor
    }

    val analyzer = remember {
        ColorMaskAnalyzer(
            initialMode = AssistMode.NONE,
            onMaskReady = { viewModel.setMaskBitmap(it) },
            downscaleStep = 2,
            blurPasses = 1,
            blurThreshold = 3
        )
    }

    LaunchedEffect(selectedModes) {
        analyzer.setModes(selectedModes)
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
            mode = activeMode,
            patternAlpha = alpha,
            patternColor = patternColor,
            previewView = previewView,
            maskTransform = maskTransform,
            roiSizePx = roiSizePx
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
                        transformOrigin = TransformOrigin.Center
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
                items(listOf(AssistMode.RED, AssistMode.ORANGE, AssistMode.YELLOW, AssistMode.GREEN, AssistMode.BLUE, AssistMode.INDIGO, AssistMode.PURPLE, AssistMode.GRAY, AssistMode.BROWN, AssistMode.NONE)) { mode ->
                    val isSelected = selectedModes.contains(mode)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.toggleMode(mode) },
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




