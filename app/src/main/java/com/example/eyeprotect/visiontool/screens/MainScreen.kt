package com.example.eyeprotect.visiontool.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.eyeprotect.nav.BackToDashboardButton
import com.example.eyeprotect.HomeEyeBabyMascot
import com.example.eyeprotect.R
import com.example.eyeprotect.visiontool.analysis.ColorMaskAnalyzer
import com.example.eyeprotect.visiontool.components.CameraPreview
import com.example.eyeprotect.visiontool.components.ColorMaskedPatternOverlay
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import com.example.eyeprotect.visiontool.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val guidePreferences = remember(context) {
        context.getSharedPreferences("vision_tool_guide", android.content.Context.MODE_PRIVATE)
    }
    val requiresFirstUseGuide = !guidePreferences.getBoolean("guide_acknowledged", false)
    var showGuide by remember {
        mutableStateOf(requiresFirstUseGuide)
    }
    var guideRequiresAcknowledgement by remember {
        mutableStateOf(requiresFirstUseGuide)
    }
    val selectedModes by viewModel.selectedModes.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
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
        AssistMode.NONE -> MaterialTheme.colorScheme.outline
    }

    val patternColor = if (
        hasMultiple ||
            activeMode == AssistMode.BLUE ||
            activeMode == AssistMode.YELLOW ||
            activeMode == AssistMode.GREEN ||
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
            roiSizePx = roiSizePx,
            torchEnabled = torchEnabled,
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

        onBack?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 4.dp
            ) {
                BackToDashboardButton(onBack = it)
            }
        }

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

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clickable {
                        guideRequiresAcknowledgement = false
                        showGuide = true
                    },
                color = Color(0xFFC8FFF5),
                shape = RoundedCornerShape(17.dp),
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "?",
                        color = Color(0xFF247E72),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                color = Color.White.copy(alpha = 0.94f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 5.dp
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "補光燈",
                            color = Color(0xFF173B39),
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = torchEnabled,
                            onCheckedChange = { viewModel.setTorchEnabled(it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF43BFAF)
                            )
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .width(48.dp)
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter),
                color = Color(0xFFC8FFF5).copy(alpha = 0.95f),
                shape = RoundedCornerShape(999.dp),
                shadowElevation = 3.dp
            ) {
                Text(
                    text = "紋理\n強度",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = Color(0xFF247E72),
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 13.sp
                )
            }
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
            color = Color.White.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 7.dp
        ) {
            Column(modifier = Modifier.padding(top = 13.dp, bottom = 10.dp)) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "想辨認哪個色系？",
                            color = Color(0xFF173B39),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "可複選，再點一次取消",
                            color = Color(0xFF6B7D7B),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Surface(
                        color = if (activeMode == AssistMode.NONE) Color(0xFFF1F5F4) else Color(0xFFD7F3DE),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = if (activeMode == AssistMode.NONE) "尚未選擇" else "辨識中",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (activeMode == AssistMode.NONE) Color(0xFF6B7D7B) else Color(0xFF247E72),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(listOf(AssistMode.RED, AssistMode.ORANGE, AssistMode.YELLOW, AssistMode.GREEN, AssistMode.BLUE, AssistMode.PURPLE, AssistMode.BROWN)) { mode ->
                        val isSelected = selectedModes.contains(mode)
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.toggleMode(mode) },
                            label = { Text(mode.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFFF4FAF8),
                                selectedContainerColor = activeColor.copy(alpha = 0.18f),
                                selectedLabelColor = Color(0xFF173B39)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (isSelected) activeColor else Color(0xFFC9DDD9),
                                enabled = true,
                                selected = isSelected
                            )
                        )
                    }
                }
            }
        }

        if (showGuide) {
            VisionToolGuideDialog(
                requiresAcknowledgement = guideRequiresAcknowledgement,
                onDismiss = {
                    if (guideRequiresAcknowledgement) {
                        guidePreferences.edit().putBoolean("guide_acknowledged", true).apply()
                    }
                    showGuide = false
                }
            )
        }
    }
}

@Composable
private fun VisionToolGuideDialog(
    requiresAcknowledgement: Boolean,
    onDismiss: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }
    val dialogBackground = Color(0xFFFFF8F7)
    val ink = Color(0xFF2C3140)
    val mutedInk = Color(0xFF766B78)
    val blush = Color(0xFFFFD8DF)
    val teal = Color(0xFF4FB8A6)

    Dialog(
        onDismissRequest = {
            if (!requiresAcknowledgement) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !requiresAcknowledgement,
            dismissOnClickOutside = !requiresAcknowledgement
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = dialogBackground,
            border = BorderStroke(1.dp, Color(0xFFF0A4A4)),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HomeEyeBabyMascot()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "眼睛寶寶帶你認識視覺工具",
                            style = MaterialTheme.typography.titleLarge,
                            color = ink
                        )
                        Text(
                            text = "用相機幫你標記不容易分辨的顏色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mutedInk
                        )
                    }
                }

                Surface(
                    color = blush.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = "將想辨識的物品放進中央方框，選擇對應色系後，畫面會在偵測到的區域疊上 AR 紋理，協助你快速區分顏色。",
                        modifier = Modifier.padding(16.dp),
                        color = ink,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFFF0C5CC))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.color_vision_comparison),
                            contentDescription = "正常色覺與色弱色盲色覺差異示意圖",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            text = "色覺差異示意：上方是一般色覺看到的色彩；下方則示意部分色弱或色盲人士可能看到的差異。",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedInk,
                            lineHeight = 18.sp
                        )
                    }
                }

                GuideItem("1", "將目標放在中央方框內", "框內的顏色才會被偵測與標記。")
                GuideItem(
                    "2",
                    "從下方選擇需要辨別的色系",
                    "框框內偵測到的區域會以黑白條紋 AR 紋理呈現；再次點選色系即可取消選擇。"
                )
                ColorSelectionExamples()
                GuideItem("3", "調整右側滑桿", "依需要調整 AR 紋理的顯示強度。")
                GuideItem("4", "光線不足時開啟 Flashlight", "讓相機更容易辨識深色或較暗的物體。")

                if (requiresAcknowledgement) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { acknowledged = !acknowledged },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = teal,
                                checkmarkColor = Color.White
                            )
                        )
                        Text(
                            text = "我知道了",
                            color = ink
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    enabled = !requiresAcknowledgement || acknowledged,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = teal,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (requiresAcknowledgement) "開始使用" else "關閉說明")
                }
            }
        }
    }
}

@Composable
private fun ColorSelectionExamples() {
    val mutedInk = Color(0xFF766B78)

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFF0C5CC))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "黑白條紋 AR 紋理示範",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF2C3140)
            )
            Text(
                text = "選取要辨別的顏色後，對應區域會在中央框框中浮現黑白條紋，協助你快速區分相近色系。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedInk,
                lineHeight = 18.sp
            )
            Image(
                painter = painterResource(id = R.drawable.vision_tool_color_selection_1),
                contentDescription = "中央框框內以黑白條紋顯示偵測到顏色的示範",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
            Image(
                painter = painterResource(id = R.drawable.vision_tool_color_selection_3),
                contentDescription = "選擇不同色系後，中央框框內顯示黑白條紋的示範",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun GuideItem(number: String, title: String, description: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFFFB46E)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    color = Color(0xFF3A3441),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFF2C3140), style = MaterialTheme.typography.bodyLarge)
            Text(description, color = Color(0xFF766B78), style = MaterialTheme.typography.bodySmall)
        }
    }
}


