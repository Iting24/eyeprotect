package com.example.eyeprotect.monitoring

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepNightLyingReminderTest {

    private val speaker = RecordingSpeaker()
    private val zoneId = ZoneId.of("Asia/Taipei")

    @After
    fun tearDown() {
        DeepNightLyingReminder.resetForTest()
    }

    @Test
    fun `speaks after lying long enough during deep night`() {
        val start = 1_000L
        val deepNightWallClock = wallClockAt(hour = 1, minute = 30)
        val metrics = MonitoringMetrics(
            ts = start + 3 * 60_000L,
            isLyingActive = true,
            lastFaceDetectedTime = start + 3 * 60_000L
        )

        DeepNightLyingReminder.update(
            metrics = metrics.copy(ts = start),
            speaker = speaker,
            nowUptime = start,
            nowWallClock = deepNightWallClock,
            userName = "Yit",
            zoneId = zoneId
        )
        DeepNightLyingReminder.update(
            metrics = metrics,
            speaker = speaker,
            nowUptime = start + 3 * 60_000L,
            nowWallClock = deepNightWallClock + 3 * 60_000L,
            userName = "Yit",
            zoneId = zoneId
        )

        assertEquals(1, speaker.spoken.size)
        assertTrue(speaker.spoken.single().contains("Yit"))
    }

    @Test
    fun `does not speak outside deep night`() {
        val now = 10_000L
        val daytimeWallClock = wallClockAt(hour = 14, minute = 0)
        val metrics = MonitoringMetrics(
            ts = now,
            isLyingActive = true,
            lastFaceDetectedTime = now
        )

        DeepNightLyingReminder.update(
            metrics = metrics,
            speaker = speaker,
            nowUptime = now,
            nowWallClock = daytimeWallClock,
            zoneId = zoneId
        )

        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `respects cooldown between reminders`() {
        val start = 50_000L
        val deepNightWallClock = wallClockAt(hour = 2, minute = 0)
        val firstMetrics = MonitoringMetrics(
            ts = start + 3 * 60_000L,
            isLyingActive = true,
            lastFaceDetectedTime = start + 3 * 60_000L
        )

        DeepNightLyingReminder.update(
            metrics = firstMetrics.copy(ts = start),
            speaker = speaker,
            nowUptime = start,
            nowWallClock = deepNightWallClock,
            zoneId = zoneId
        )
        DeepNightLyingReminder.update(
            metrics = firstMetrics,
            speaker = speaker,
            nowUptime = start + 3 * 60_000L,
            nowWallClock = deepNightWallClock + 3 * 60_000L,
            zoneId = zoneId
        )
        DeepNightLyingReminder.update(
            metrics = firstMetrics.copy(ts = start + 4 * 60_000L),
            speaker = speaker,
            nowUptime = start + 4 * 60_000L,
            nowWallClock = deepNightWallClock + 4 * 60_000L,
            zoneId = zoneId
        )

        assertEquals(1, speaker.spoken.size)
    }

    private class RecordingSpeaker : DeepNightLyingReminder.Speaker {
        override var isSpeaking: Boolean = false
        val spoken = mutableListOf<String>()

        override fun speak(text: String, utteranceId: String) {
            spoken += text
        }
    }

    private fun wallClockAt(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 4, 19, hour, minute, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
