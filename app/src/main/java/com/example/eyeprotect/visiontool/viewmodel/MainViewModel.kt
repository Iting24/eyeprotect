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
    ORANGE("Orange"),
    BROWN("Brown"),
    INDIGO("Indigo"),
    PURPLE("Purple"),
    GRAY("Gray"),
    NONE("Off")
}

data class MaskTransform(
    val matrix: Matrix,
    val imageWidth: Int,
    val imageHeight: Int
)

class MainViewModel : ViewModel() {
    private val _selectedModes = MutableStateFlow<Set<AssistMode>>(setOf(AssistMode.NONE))
    val selectedModes: StateFlow<Set<AssistMode>> = _selectedModes.asStateFlow()

    private val _textureAlpha = MutableStateFlow(0.5f)
    val textureAlpha: StateFlow<Float> = _textureAlpha.asStateFlow()

    private val _maskBitmap = MutableStateFlow<Bitmap?>(null)
    val maskBitmap: StateFlow<Bitmap?> = _maskBitmap.asStateFlow()

    private val _maskTransform = MutableStateFlow<MaskTransform?>(null)
    val maskTransform: StateFlow<MaskTransform?> = _maskTransform.asStateFlow()

    fun toggleMode(mode: AssistMode) {
        val current = _selectedModes.value
        val next = when (mode) {
            AssistMode.NONE -> setOf(AssistMode.NONE)
            else -> {
                val withoutNone = current - AssistMode.NONE
                if (withoutNone.contains(mode)) {
                    val reduced = withoutNone - mode
                    if (reduced.isEmpty()) setOf(AssistMode.NONE) else reduced
                } else {
                    withoutNone + mode
                }
            }
        }
        _selectedModes.value = next
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
