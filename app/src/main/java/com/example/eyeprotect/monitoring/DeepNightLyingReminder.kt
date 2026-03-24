package com.example.eyeprotect.monitoring

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.annotation.VisibleForTesting
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Non-AI rule-based proactive reminder:
 * - Only active between 23:00 and 05:00 (local time).
 * - Triggers when "lying posture" stays active for >= 3 minutes (debounced).
 * - Same reminder has a 15-minute cooldown.
 *
 * Call [update] once per second with the latest [MonitoringMetrics].
 */
object DeepNightLyingReminder {

    private const val REMINDER_KEY = "deep_night_lying"

    private const val LYING_HOLD_MS = 3 * 60_000L
    private const val COOLDOWN_MS = 15 * 60_000L

    // If face hasn't been detected recently, stop accumulating "lying" time to reduce false positives.
    private const val FACE_RECENCY_MS = 10_000L

    private var lyingStartUptimeMs: Long? = null
    private val lastSpokenUptimeMsByKey = mutableMapOf<String, Long>()

    @VisibleForTesting
    internal val messageTemplates: List<String> = listOf(
        "{name}，太晚了別躺著看手機喔，眼睛會很累。",
        "{name}，你現在是躺姿耶…先放下手機休息一下好嗎？",
        "眼睛在求救了，別再躺著滑了，去睡覺吧。",
        "這個時間點還在躺著用手機，明天起來會後悔的啦。",
        "我知道很想再看一下，但你真的該休息了。",
        "躺著看螢幕對眼睛不友善，起來坐好或直接去睡吧。",
        "{name}，夜深了～護眼一下：先停 1 分鐘，閉眼深呼吸。",
    )

    interface Speaker {
        val isSpeaking: Boolean
        fun speak(text: String, utteranceId: String)
    }

    fun update(
        metrics: MonitoringMetrics,
        tts: TextToSpeech,
        userName: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val nowUptime = metrics.ts.takeIf { it > 0 } ?: SystemClock.uptimeMillis()
        val nowWallClock = System.currentTimeMillis()

        val speaker = object : Speaker {
            override val isSpeaking: Boolean
                get() = tts.isSpeaking

            override fun speak(text: String, utteranceId: String) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }

        update(
            metrics = metrics,
            speaker = speaker,
            nowUptime = nowUptime,
            nowWallClock = nowWallClock,
            userName = userName,
            zoneId = zoneId,
        )
    }

    fun update(
        metrics: MonitoringMetrics,
        speaker: Speaker,
        nowUptime: Long,
        nowWallClock: Long,
        userName: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        if (nowUptime <= 0L) return
        if (nowWallClock <= 0L) return

        if (!isDeepNight(nowWallClock, zoneId)) {
            lyingStartUptimeMs = null
            return
        }

        val faceRecent = metrics.lastFaceDetectedTime > 0L && nowUptime - metrics.lastFaceDetectedTime <= FACE_RECENCY_MS
        val lyingActive = metrics.isLyingActive && faceRecent

        if (!lyingActive) {
            lyingStartUptimeMs = null
            return
        }

        val start = lyingStartUptimeMs ?: nowUptime.also { lyingStartUptimeMs = it }
        val lyingDuration = nowUptime - start
        if (lyingDuration < LYING_HOLD_MS) return

        if (!canSpeak(nowUptime, REMINDER_KEY)) return
        if (speaker.isSpeaking) return

        val text = pickMessage(userName)
        speaker.speak(text, REMINDER_KEY)
        lastSpokenUptimeMsByKey[REMINDER_KEY] = nowUptime
    }

    private fun canSpeak(nowUptimeMs: Long, key: String): Boolean {
        val last = lastSpokenUptimeMsByKey[key] ?: return true
        return nowUptimeMs - last >= COOLDOWN_MS
    }

    private fun isDeepNight(nowWallClockMs: Long, zoneId: ZoneId): Boolean {
        val localTime = Instant.ofEpochMilli(nowWallClockMs).atZone(zoneId).toLocalTime()
        return localTime >= LocalTime.of(23, 0) || localTime < LocalTime.of(5, 0)
    }

    private fun pickMessage(userName: String?): String {
        val raw = messageTemplates[Random.nextInt(messageTemplates.size)]
        val name = userName?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            raw.replace("{name}", name)
        } else {
            raw.replace("{name}，", "").replace("{name}", "")
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        lyingStartUptimeMs = null
        lastSpokenUptimeMsByKey.clear()
    }
}
