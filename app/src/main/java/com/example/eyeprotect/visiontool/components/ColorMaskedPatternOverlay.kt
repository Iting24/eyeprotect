package com.example.eyeprotect.visiontool.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import com.example.eyeprotect.visiontool.viewmodel.MaskTransform

@Composable
fun ColorMaskedPatternOverlay(
    modifier: Modifier = Modifier,
    maskBitmap: Bitmap?,
    mode: AssistMode,
    patternAlpha: Float,
    patternColor: Color,
    previewView: PreviewView?,
    maskTransform: MaskTransform?,
    roiSizePx: Float? = null
) {
    val maskImage = remember(maskBitmap) { maskBitmap?.asImageBitmap() }

    Canvas(modifier = modifier) {
        val roi = roiSizePx
        val hasRoi = roi != null && roi > 0f
        val left = if (hasRoi) (size.width - roi) / 2f else 0f
        val top = if (hasRoi) (size.height - roi) / 2f else 0f
        val right = if (hasRoi) left + roi else size.width
        val bottom = if (hasRoi) top + roi else size.height

        if (mode == AssistMode.NONE) return@Canvas

        clipRect(left, top, right, bottom) {
            val rect = Rect(left, top, right, bottom)
            val paint = Paint()

            drawIntoCanvas { canvas ->
                canvas.saveLayer(rect, paint)

                if (maskBitmap != null && maskTransform != null) {
                    val native = canvas.nativeCanvas
                    val m = Matrix(maskTransform.matrix)

                    val scaleX = maskTransform.imageWidth.toFloat() / maskBitmap.width.toFloat()
                    val scaleY = maskTransform.imageHeight.toFloat() / maskBitmap.height.toFloat()
                    m.preScale(scaleX, scaleY)

                    native.save()
                    native.concat(m)
                    native.drawBitmap(maskBitmap, 0f, 0f, null)
                    native.restore()
                } else if (maskImage != null) {
                    drawImage(maskImage)
                }

                drawPattern(mode, 1f, Color.White, BlendMode.SrcIn)

                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawPattern(
    mode: AssistMode,
    alpha: Float,
    color: Color,
    blendMode: BlendMode
) {
    val paintColor = color.copy(alpha = alpha.coerceIn(0f, 1f))
    val w = size.width
    val h = size.height

    when (mode) {
        AssistMode.RED -> {
            val gap = 14f
            var x = -h
            while (x < w) {
                drawLine(
                    color = paintColor,
                    start = Offset(x, 0f),
                    end = Offset(x + h, h),
                    strokeWidth = 9f,
                    blendMode = blendMode
                )
                x += gap
            }
        }
        AssistMode.GREEN, AssistMode.GRAY -> {
            val gap = 8f
            var x = 0f
            while (x < w) {
                drawLine(
                    color = paintColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 6f,
                    blendMode = blendMode
                )
                x += gap
            }
            var y = 0f
            while (y < h) {
                drawLine(
                    color = paintColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 6f,
                    blendMode = blendMode
                )
                y += gap
            }
        }
        AssistMode.YELLOW, AssistMode.ORANGE, AssistMode.BROWN -> {
            val gap = 22f
            var x = -h
            while (x < w) {
                drawLine(
                    color = paintColor,
                    start = Offset(x, 0f),
                    end = Offset(x + h, h),
                    strokeWidth = 5f,
                    blendMode = blendMode
                )
                x += gap
            }
        }
        AssistMode.BLUE, AssistMode.INDIGO, AssistMode.PURPLE -> {
            val gap = 12f
            var y = 0f
            while (y < h) {
                var x = 0f
                while (x < w) {
                    drawCircle(
                        color = paintColor,
                        radius = 9f,
                        center = Offset(x, y),
                        blendMode = blendMode
                    )
                    x += gap
                }
                y += gap
            }
        }
        AssistMode.NONE -> {
            // No-op
        }
    }
}





