package com.example.eyeprotect.visiontool.components

import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.example.eyeprotect.visiontool.viewmodel.MaskTransform

@Composable
fun ColorMaskedPatternOverlay(
    modifier: Modifier = Modifier,
    maskBitmap: Bitmap?,
    mode: com.example.eyeprotect.visiontool.viewmodel.AssistMode,
    patternAlpha: Float,
    patternColor: Color,
    previewView: androidx.camera.view.PreviewView?,
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

        if (mode == com.example.eyeprotect.visiontool.viewmodel.AssistMode.NONE) return@Canvas

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

                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                    blendMode = BlendMode.SrcIn
                )

                drawPattern(patternAlpha, Color.Black, BlendMode.SrcAtop)

                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawPattern(alpha: Float, color: Color, blendMode: BlendMode) {
    val paintColor = color.copy(alpha = alpha.coerceIn(0f, 1f))
    val w = size.width
    val h = size.height

    val gap = 18f
    val strokeWidth = 8f
    var y = 0f
    while (y < h) {
        drawLine(
            color = paintColor,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = strokeWidth,
            blendMode = blendMode
        )
        y += gap
    }
}





