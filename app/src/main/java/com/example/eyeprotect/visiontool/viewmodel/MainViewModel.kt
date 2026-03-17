package com.example.eyeprotect.visiontool.viewmodel

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AssistMode(val title: String) {
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue"),
    YELLOW("Yellow"),
    ALL("All"),
    NONE("Off")
}

data class MaskTransform(
    val matrix: Matrix,
    val imageWidth: Int,
    val imageHeight: Int
)

class MainViewModel : ViewModel() {
    private val _currentMode = MutableStateFlow(AssistMode.NONE)
    val currentMode: StateFlow<AssistMode> = _currentMode.asStateFlow()

    private val _textureAlpha = MutableStateFlow(0.5f)
    val textureAlpha: StateFlow<Float> = _textureAlpha.asStateFlow()

    private val _maskBitmap = MutableStateFlow<Bitmap?>(null)
    val maskBitmap: StateFlow<Bitmap?> = _maskBitmap.asStateFlow()

    private val _maskTransform = MutableStateFlow<MaskTransform?>(null)
    val maskTransform: StateFlow<MaskTransform?> = _maskTransform.asStateFlow()

    fun setMode(mode: AssistMode) {
        _currentMode.value = mode
    }

    fun setAlpha(alpha: Float) {
        _textureAlpha.value = alpha
    }

    fun setMaskBitmap(bitmap: Bitmap?) {
        _maskBitmap.value = bitmap
    }

    fun setMaskTransform(matrix: Matrix, imageWidth: Int, imageHeight: Int) {
        _maskTransform.value = MaskTransform(Matrix(matrix), imageWidth, imageHeight)
    }
}
