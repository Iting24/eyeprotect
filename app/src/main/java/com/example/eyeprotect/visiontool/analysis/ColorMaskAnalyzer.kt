package com.example.eyeprotect.visiontool.analysis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.eyeprotect.visiontool.viewmodel.AssistMode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    private val modesRef = AtomicReference<Set<AssistMode>>(setOf(initialMode))

    fun setModes(modes: Set<AssistMode>) {
        modesRef.set(modes)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val modes = modesRef.get()
            if (modes.isEmpty() || modes == setOf(AssistMode.NONE)) {
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

            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride

            val hsv = FloatArray(3)
            val total = maskW * maskH

            var greenishCount = 0
            var yellowishCount = 0

            // Pre-pass: detect if green & yellow both appear in frame.
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
                    val h = hsv[0]
                    val s = hsv[1]
                    val v = hsv[2]

                    if (s >= 0.22f && v >= 0.22f && isInRange(h, 65f, 165f)) {
                        greenishCount++
                    }
                    if (s >= 0.22f && v >= 0.24f && isInRange(h, 44f, 56f)) {
                        yellowishCount++
                    }
                }
            }

            val minHits = max(20, total / 100) // 1% of pixels, at least 20
            val relaxGreenTowardYellow =
                modes.contains(AssistMode.GREEN) &&
                    modes.contains(AssistMode.YELLOW) &&
                    greenishCount >= minHits &&
                    yellowishCount >= minHits

            val srcMask = BooleanArray(total)
            val orderedModes = modes.sortedBy { modePriority(it) }

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
                    var matched = false
                    for (mode in orderedModes) {
                        if (mode == AssistMode.NONE) continue
                        if (isTargetColor(hsv, mode, r, g, b, relaxGreenTowardYellow)) {
                            matched = true
                            break
                        }
                    }
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

    private fun modePriority(mode: AssistMode): Int {
        return when (mode) {
            AssistMode.BROWN -> 0
            AssistMode.ORANGE -> 1
            AssistMode.YELLOW -> 2
            AssistMode.GREEN -> 3
            AssistMode.RED -> 4
            AssistMode.BLUE -> 5
            AssistMode.INDIGO -> 6
            AssistMode.PURPLE -> 7
            AssistMode.GRAY -> 8
            AssistMode.NONE -> 9
        }
    }

    private fun isTargetColor(
        hsv: FloatArray,
        mode: AssistMode,
        r: Int,
        g: Int,
        b: Int,
        relaxGreenTowardYellow: Boolean
    ): Boolean {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        val minS = when (mode) {
            AssistMode.YELLOW -> 0.30f
            AssistMode.GREEN -> 0.22f
            AssistMode.RED -> 0.47f
            AssistMode.BLUE -> 0.20f
            AssistMode.ORANGE -> 0.62f
            AssistMode.BROWN -> 0.48f
            AssistMode.INDIGO -> 0.22f
            AssistMode.PURPLE -> 0.18f
            AssistMode.GRAY -> 0.06f
            AssistMode.NONE -> 0.35f
        }
        val minV = when (mode) {
            AssistMode.YELLOW -> 0.42f
            AssistMode.GREEN -> 0.22f
            AssistMode.RED -> 0.47f
            AssistMode.BLUE -> 0.20f
            AssistMode.ORANGE -> 0.78f
            AssistMode.BROWN -> 0.48f
            AssistMode.INDIGO -> 0.20f
            AssistMode.PURPLE -> 0.18f
            AssistMode.GRAY -> 0.10f
            AssistMode.NONE -> 0.35f
        }
        if (s < minS || v < minV) return false

        val rgbDominant = when (mode) {
            AssistMode.RED -> r >= 165 && r - max(g, b) >= 62 && v >= 0.58f
            AssistMode.BLUE -> b >= 120 && b - max(r, g) >= 40
            AssistMode.GREEN -> g >= 100 && g >= r + 5 && g + 5 >= b
            AssistMode.YELLOW -> {
                val minRG = min(r, g)
                val rgBalanced = abs(r - g) <= 20
                val blueLow = b <= 92
                val nearYellow = r >= g - 3
                minRG >= 150 && rgBalanced && blueLow && nearYellow
            }
            AssistMode.ORANGE -> {
                val redLeads = r >= g + 22
                val greenStrong = g >= 150
                val blueLow = b <= 58
                val brightEnough = v >= 0.86f
                val saturatedEnough = s >= 0.78f
                redLeads && greenStrong && blueLow && brightEnough && saturatedEnough
            }
            AssistMode.BROWN -> {
                val redLead = r - g in 25..65
                val blueBand = b in 24..88
                val midBrightness = v in 0.50f..0.82f
                val notBrightOrange = !(v >= 0.77f && s >= 0.70f && (r - g >= 43 || h >= 41f))
                redLead && blueBand && midBrightness && notBrightOrange
            }
            AssistMode.INDIGO -> b >= 90 && r <= 120 && g <= 120
            AssistMode.PURPLE -> r >= 85 && b >= 85 && g <= 175
            AssistMode.GRAY -> abs(r - g) <= 12 && abs(g - b) <= 12
            AssistMode.NONE -> true
        }
        if (!rgbDominant) return false

        return when (mode) {
            AssistMode.YELLOW -> isInRange(h, 47f, 58f)
            AssistMode.GREEN -> {
                val minHue = if (relaxGreenTowardYellow) 62f else 68f
                isInRange(h, minHue, 165f)
            }
            AssistMode.RED -> isInRange(h, 0f, 10f) || isInRange(h, 350f, 360f)
            AssistMode.BLUE -> isInRange(h, 200f, 235f)
            AssistMode.ORANGE -> isInRange(h, 31f, 40f)
            AssistMode.BROWN -> isInRange(h, 34f, 44f)
            AssistMode.INDIGO -> isInRange(h, 235f, 265f)
            AssistMode.PURPLE -> isInRange(h, 250f, 300f)
            AssistMode.GRAY -> s <= 0.10f
            AssistMode.NONE -> false
        }
    }

    private fun isInRange(value: Float, min: Float, max: Float): Boolean {
        return value >= min && value <= max
    }
}






