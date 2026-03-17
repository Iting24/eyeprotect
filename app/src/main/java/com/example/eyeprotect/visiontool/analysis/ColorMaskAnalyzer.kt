package com.example.eyeprotect.visiontool.analysis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Lightweight HSV color mask analyzer.
 * - Requires OUTPUT_IMAGE_FORMAT_RGBA_8888
 * - Downscale by step to keep FPS
 * - Outputs ARGB_8888 mask: white = hit, transparent = miss
 * - Optional 3x3 box blur + threshold to reduce noise
 */
class ColorMaskAnalyzer(
    initialMode: AssistMode = AssistMode.NONE,
    private val onMaskReady: (Bitmap?) -> Unit,
    private val downscaleStep: Int = 4,
    private val blurPasses: Int = 1,
    private val blurThreshold: Int = 3
) : ImageAnalysis.Analyzer {

    private val modeRef = AtomicReference(initialMode)

    fun setMode(mode: AssistMode) {
        modeRef.set(mode)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val mode = modeRef.get()
            if (mode == AssistMode.NONE) {
                onMaskReady(null)
                return
            }

            val crop = image.cropRect
            val cropW = crop.width()
            val cropH = crop.height()
            val step = max(1, downscaleStep)
            val maskW = max(1, cropW / step)
            val maskH = max(1, cropH / step)

            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer == null) {
                onMaskReady(null)
                return
            }

            if (mode == AssistMode.ALL) {
                val full = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
                full.eraseColor(0xFFFFFFFF.toInt())
                onMaskReady(full)
                return
            }

            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride

            val hsv = FloatArray(3)
            val srcMask = BooleanArray(maskW * maskH)

            for (y in 0 until maskH) {
                val srcY = crop.top + y * step
                val rowOffset = srcY * rowStride
                for (x in 0 until maskW) {
                    val srcX = crop.left + x * step
                    val offset = rowOffset + srcX * pixelStride
                    val r = getByteAsInt(buffer, offset)
                    val g = getByteAsInt(buffer, offset + 1)
                    val b = getByteAsInt(buffer, offset + 2)

                    Color.RGBToHSV(r, g, b, hsv)
                    val matched = isTargetColor(hsv, mode)
                    srcMask[y * maskW + x] = matched
                }
            }

            val smoothed = if (blurPasses > 0) {
                blurAndThreshold(srcMask, maskW, maskH, blurPasses, blurThreshold)
            } else {
                srcMask
            }

            val maskBitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
            for (i in smoothed.indices) {
                val color = if (smoothed[i]) 0xFFFFFFFF.toInt() else 0x00FFFFFF
                maskBitmap.setPixel(i % maskW, i / maskW, color)
            }

            onMaskReady(maskBitmap)
        } finally {
            image.close()
        }
    }

    private fun blurAndThreshold(
        src: BooleanArray,
        width: Int,
        height: Int,
        passes: Int,
        threshold: Int
    ): BooleanArray {
        var current = src
        repeat(passes) {
            val next = BooleanArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var count = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= width) continue
                            if (current[ny * width + nx]) count++
                        }
                    }
                    next[y * width + x] = count >= threshold
                }
            }
            current = next
        }
        return current
    }

    private fun getByteAsInt(buffer: ByteBuffer, index: Int): Int {
        return buffer.get(index).toInt() and 0xFF
    }

    private fun isTargetColor(hsv: FloatArray, mode: AssistMode): Boolean {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        val minS = if (mode == AssistMode.YELLOW) 0.45f else 0.35f
        val minV = if (mode == AssistMode.YELLOW) 0.45f else 0.35f
        if (s < minS || v < minV) return false

        return when (mode) {
            AssistMode.YELLOW -> isInRange(h, 48f, 65f)
            AssistMode.RED -> isInRange(h, 0f, 10f) || isInRange(h, 350f, 360f)
            AssistMode.GREEN -> isInRange(h, 80f, 150f)
            AssistMode.BLUE -> isInRange(h, 190f, 250f)
            AssistMode.ALL -> true
            AssistMode.NONE -> false
        }
    }

    private fun isInRange(value: Float, min: Float, max: Float): Boolean {
        return value >= min && value <= max
    }
}
