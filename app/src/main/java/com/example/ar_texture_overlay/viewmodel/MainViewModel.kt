package com.example.ar_texture_overlay.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AssistMode(val title: String) {
    RED("紅"),
    GREEN("綠"),
    BLUE("藍"),
    YELLOW("黃"),
    ALL("全色域"),
    NONE("重置")
}

class MainViewModel : ViewModel() {
    // 目前選擇的輔助模式
    private val _currentMode = MutableStateFlow(AssistMode.NONE)
    val currentMode: StateFlow<AssistMode> = _currentMode.asStateFlow()

    // 紋理透明度 (0.0f - 1.0f)
    private val _textureAlpha = MutableStateFlow(0.5f)
    val textureAlpha: StateFlow<Float> = _textureAlpha.asStateFlow()

    fun setMode(mode: AssistMode) {
        _currentMode.value = mode
    }

    fun setAlpha(alpha: Float) {
        _textureAlpha.value = alpha
    }
}