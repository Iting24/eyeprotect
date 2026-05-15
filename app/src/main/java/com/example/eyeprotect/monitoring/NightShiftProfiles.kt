package com.example.eyeprotect.monitoring

import android.content.SharedPreferences
import android.graphics.Color
import kotlin.math.roundToInt

object NightShiftProfiles {

    const val DEFAULT_MANUAL_WARMTH = 0.5f
    private const val DEFAULT_LEARNED_OFFSET = 0f

    private const val MIN_KELVIN = 2700
    private const val MAX_KELVIN = 5000

    enum class LuxBucket(val prefSuffix: String) {
        VERY_DARK("very_dark"),
        DIM_INDOOR("dim_indoor"),
        NORMAL_INDOOR("normal_indoor"),
        BRIGHT_INDOOR("bright_indoor"),
        OUTDOOR("outdoor")
    }

    fun recommendedWarmthForLux(lux: Float): Float {
        return when {
            lux < 20f -> 0.95f
            lux < 80f -> 0.78f
            lux < 200f -> 0.60f
            lux < 500f -> 0.35f
            else -> 0.12f
        }
    }

    fun bucketForLux(lux: Float): LuxBucket {
        return when {
            lux < 20f -> LuxBucket.VERY_DARK
            lux < 80f -> LuxBucket.DIM_INDOOR
            lux < 200f -> LuxBucket.NORMAL_INDOOR
            lux < 500f -> LuxBucket.BRIGHT_INDOOR
            else -> LuxBucket.OUTDOOR
        }
    }

    fun effectiveAutoWarmth(lux: Float, prefs: SharedPreferences): Float {
        val bucket = bucketForLux(lux)
        val offset = prefs.getFloat(learnedOffsetKey(bucket), DEFAULT_LEARNED_OFFSET)
        return (recommendedWarmthForLux(lux) + offset).coerceIn(0f, 1f)
    }

    fun saveLearnedOffsetForLux(
        prefs: SharedPreferences,
        lux: Float,
        manualWarmth: Float
    ) {
        val bucket = bucketForLux(lux)
        val offset = (manualWarmth - recommendedWarmthForLux(lux)).coerceIn(-0.45f, 0.45f)
        prefs.edit().putFloat(learnedOffsetKey(bucket), offset).apply()
    }

    fun resetLearnedOffsets(prefs: SharedPreferences) {
        prefs.edit()
            .remove(learnedOffsetKey(LuxBucket.VERY_DARK))
            .remove(learnedOffsetKey(LuxBucket.DIM_INDOOR))
            .remove(learnedOffsetKey(LuxBucket.NORMAL_INDOOR))
            .remove(learnedOffsetKey(LuxBucket.BRIGHT_INDOOR))
            .remove(learnedOffsetKey(LuxBucket.OUTDOOR))
            .apply()
    }

    fun learnedOffsetForLux(lux: Float, prefs: SharedPreferences): Float {
        return prefs.getFloat(learnedOffsetKey(bucketForLux(lux)), DEFAULT_LEARNED_OFFSET)
    }

    fun colorForAutoLux(lux: Float, prefs: SharedPreferences): Int {
        return colorForWarmth(effectiveAutoWarmth(lux, prefs))
    }

    fun colorForManualWarmth(warmth: Float): Int = colorForWarmth(warmth.coerceIn(0f, 1f))

    fun kelvinForWarmth(warmth: Float): Int {
        val clamped = warmth.coerceIn(0f, 1f)
        return (MAX_KELVIN - (MAX_KELVIN - MIN_KELVIN) * clamped).roundToInt()
    }

    private fun colorForWarmth(warmth: Float): Int {
        val clamped = warmth.coerceIn(0f, 1f)
        val alpha = lerp(10, 72, clamped)
        val green = lerp(248, 222, clamped)
        val blue = lerp(236, 170, clamped)
        return Color.argb(alpha, 255, green, blue)
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + (end - start) * fraction).roundToInt()
    }

    private fun learnedOffsetKey(bucket: LuxBucket): String {
        return "pref_night_shift_offset_${bucket.prefSuffix}"
    }
}
