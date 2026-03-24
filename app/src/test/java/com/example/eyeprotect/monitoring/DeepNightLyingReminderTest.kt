package com.example.eyeprotect.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DeepNightLyingReminderTest {

    private val zoneId = ZoneId.of("Asia/Taipei")

    private class FakeSpeaker : DeepNightLyingReminder.Speaker {
        data class Utterance(val text: String, val id: String)

        val utterances = mutableListOf<Utterance>()

        override var isSpeaking: Boolean = false

        override fun speak(text: String, utteranceId: String) {
            utterances += Utterance(text = text, id = utteranceId)
        }
    }

    @Before
    fun setUp() {
        DeepNightLyingReminder.resetForTest()
    }

    @Test
    fun noSpeakOutsideDeepNight() {
        val speaker = FakeSpeaker()
        val noon = wallClock(2026, 3, 24, 12, 0)

        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = 1_000L),
            speaker = speaker,
            nowUptime = 200_000L,
            nowWallClock = noon,
            userName = "Susan",
            zoneId = zoneId,
        )

        assertTrue(speaker.utterances.isEmpty())
    }

    @Test
    fun speakAfter3MinutesContinuousLyingDuringDeepNight() {
        val speaker = FakeSpeaker()
        val night = wallClock(2026, 3, 24, 23, 30)

        val baseUptime = 1_000_000L
        val faceSeen = baseUptime

        // Just under 3 minutes: no speak.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen),
            speaker = speaker,
            nowUptime = baseUptime,
            nowWallClock = night,
            userName = "Susan",
            zoneId = zoneId,
        )
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 170_000L),
            speaker = speaker,
            nowUptime = baseUptime + 179_999L,
            nowWallClock = night,
            userName = "Susan",
            zoneId = zoneId,
        )
        assertTrue(speaker.utterances.isEmpty())

        // At 3 minutes: speak once.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 180_000L),
            speaker = speaker,
            nowUptime = baseUptime + 180_000L,
            nowWallClock = night,
            userName = "Susan",
            zoneId = zoneId,
        )

        assertEquals(1, speaker.utterances.size)
        val spoken = speaker.utterances.first()
        assertEquals("deep_night_lying", spoken.id)
        assertTrue(spoken.text.isNotBlank())
        val expectedMessages = DeepNightLyingReminder.messageTemplates.map { template ->
            if (template.contains("{name}")) template.replace("{name}", "Susan") else template
        }
        assertTrue(spoken.text in expectedMessages)
    }

    @Test
    fun doesNotAccumulateWhenFaceNotRecent() {
        val speaker = FakeSpeaker()
        val night = wallClock(2026, 3, 24, 23, 10)

        val baseUptime = 10_000L

        // Face seen too long ago; should reset (never reach 3 min).
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = 0L),
            speaker = speaker,
            nowUptime = baseUptime,
            nowWallClock = night,
            zoneId = zoneId,
        )
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = 0L),
            speaker = speaker,
            nowUptime = baseUptime + 400_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )

        assertTrue(speaker.utterances.isEmpty())
    }

    @Test
    fun cooldownPreventsRepeatWithin15Minutes() {
        val speaker = FakeSpeaker()
        val night = wallClock(2026, 3, 24, 23, 40)

        val baseUptime = 50_000L
        val faceSeen = baseUptime

        // Trigger first time.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen),
            speaker = speaker,
            nowUptime = baseUptime,
            nowWallClock = night,
            zoneId = zoneId,
        )
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 180_000L),
            speaker = speaker,
            nowUptime = baseUptime + 180_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )
        assertEquals(1, speaker.utterances.size)

        // Still lying, but only +10 min: should not speak again.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 780_000L),
            speaker = speaker,
            nowUptime = baseUptime + 780_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )
        assertEquals(1, speaker.utterances.size)

        // +15 min: allow again.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 1_080_000L),
            speaker = speaker,
            nowUptime = baseUptime + 1_080_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )
        assertEquals(2, speaker.utterances.size)
    }

    @Test
    fun shortMisDetectionResetsHoldTimer() {
        val speaker = FakeSpeaker()
        val night = wallClock(2026, 3, 24, 23, 50)

        val baseUptime = 100_000L
        val faceSeen = baseUptime

        // Lying 2 min.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen),
            speaker = speaker,
            nowUptime = baseUptime,
            nowWallClock = night,
            zoneId = zoneId,
        )
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 120_000L),
            speaker = speaker,
            nowUptime = baseUptime + 120_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )
        assertTrue(speaker.utterances.isEmpty())

        // Not lying -> reset.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = false, lastFaceDetectedUptime = faceSeen + 121_000L),
            speaker = speaker,
            nowUptime = baseUptime + 121_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )

        // Lying again 2 min -> still should not trigger.
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 241_000L),
            speaker = speaker,
            nowUptime = baseUptime + 241_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )
        assertTrue(speaker.utterances.isEmpty())
    }

    @Test
    fun speakerIsSpeakingSkipsSpeaking() {
        val speaker = FakeSpeaker().apply { isSpeaking = true }
        val night = wallClock(2026, 3, 24, 23, 15)

        val baseUptime = 1_000_000L
        val faceSeen = baseUptime

        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 180_000L),
            speaker = speaker,
            nowUptime = baseUptime + 180_000L,
            nowWallClock = night,
            zoneId = zoneId,
        )

        assertTrue(speaker.utterances.isEmpty())
    }

    @Test
    fun messageNameReplacement() {
        val speaker = FakeSpeaker()
        val night = wallClock(2026, 3, 24, 23, 5)

        val baseUptime = 777_000L
        val faceSeen = baseUptime

        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen),
            speaker = speaker,
            nowUptime = baseUptime,
            nowWallClock = night,
            userName = "Susan",
            zoneId = zoneId,
        )
        DeepNightLyingReminder.update(
            metrics = metrics(isLying = true, lastFaceDetectedUptime = faceSeen + 180_000L),
            speaker = speaker,
            nowUptime = baseUptime + 180_000L,
            nowWallClock = night,
            userName = "Susan",
            zoneId = zoneId,
        )

        assertEquals(1, speaker.utterances.size)
        val text = speaker.utterances.single().text
        assertFalse(text.contains("{name}"))
        assertTrue(text.isNotBlank())
        val expectedMessages = DeepNightLyingReminder.messageTemplates.map { template ->
            if (template.contains("{name}")) template.replace("{name}", "Susan") else template
        }
        assertTrue(text in expectedMessages)
    }

    private fun metrics(isLying: Boolean, lastFaceDetectedUptime: Long): MonitoringMetrics {
        return MonitoringMetrics(
            ts = 0L,
            warningsMask = 0,
            isLyingActive = isLying,
            lastFaceDetectedTime = lastFaceDetectedUptime,
            irisNorm = null,
            eyeOpenMin = null,
            slouchScore = null,
            pitchDeg = null,
            rollDeg = null,
            tiltDeg = null,
        )
    }

    private fun wallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zoneId).toInstant().toEpochMilli()
    }
}
