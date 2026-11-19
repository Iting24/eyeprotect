package com.example.eyeprotect.monitoring

import com.example.eyeprotect.data.db.MonitoringStatusEventEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringRankCalculatorTest {
    private fun event(
        id: Long,
        session: String,
        at: Long,
        mask: Int = 0,
        valid: Boolean = true,
    ) = MonitoringStatusEventEntity(
        id = id, sessionId = session, recordedAtEpochMs = at, warningsMask = mask,
        isCameraFrame = true, faceDetected = valid, poseDetected = valid,
        faceError = false, poseError = false,
    )

    @Test fun splitsAtLocalMidnightAndIgnoresPausedSession() {
        val zone = ZoneId.of("Asia/Taipei")
        val midnight = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val events = listOf(
            event(1, "a", midnight - 4_000, 4),
            event(2, "a", midnight, 4),
            event(3, "a", midnight + 4_000, 4),
            event(4, "b", midnight + 10_000),
            event(5, "b", midnight + 15_000),
        )
        val previousDay = MonitoringRankCalculator.totals(events, ReportWindow(midnight - 86_400_000, midnight))
        val nextDay = MonitoringRankCalculator.totals(events, ReportWindow(midnight, midnight + 86_400_000))
        assertEquals(4_000L, previousDay.effectiveMs)
        assertEquals(9_000L, nextDay.effectiveMs)
        assertEquals(4_000L, nextDay.slouchMs)
    }

    @Test fun countsRisingEdgesWithCooldownAndCapsCameraGap() {
        val events = listOf(
            event(1, "a", 1_000, 0),
            event(2, "a", 2_000, 2),
            event(3, "a", 3_000, 0),
            event(4, "a", 4_000, 2),
            event(5, "a", 40_000, 0),
            event(6, "a", 41_000, 2),
            event(7, "a", 42_000, 3),
            event(8, "a", 43_000, 1),
        )
        val totals = MonitoringRankCalculator.totals(events, ReportWindow(0, 60_000))
        assertEquals(2, totals.squintEvents)
        assertEquals(1, totals.tooCloseEvents)
        assertEquals(13_000L, totals.effectiveMs)
    }

    @Test fun periodUsesRawTotalsAndRankingRequiresThirtyMinutes() {
        val first = RankTotals(3_600_000, 900_000, 2, 4)
        val second = RankTotals(1_800_000, 0, 4, 2)
        val total = first + second
        assertEquals(1.0 / 6.0, total.slouchRatio!!, 0.00001)
        assertEquals(4.0, total.squintPerHour!!, 0.00001)
        val short = RoomMemberScore("short", "短", RankTotals(5_000, 0, 0, 0))
        val long = RoomMemberScore("long", "長", total)
        assertEquals(listOf(long), CompetitionRanking.ranked(listOf(short, long), RankMetric.SLOUCH_RATIO))
        assertFalse(CompetitionRanking.ranked(listOf(short), RankMetric.EFFECTIVE_TIME).isNotEmpty())
        assertTrue(total.effectiveMs > MonitoringRankCalculator.MIN_RANK_MS)
    }

    @Test fun exactlyThirtyMinutesIsEligibleForEveryMetric() {
        val member = RoomMemberScore(
            "exact",
            "剛好三十分鐘",
            RankTotals(MonitoringRankCalculator.MIN_RANK_MS, 0, 0, 0),
        )

        RankMetric.entries.forEach { metric ->
            assertEquals(listOf(member), CompetitionRanking.ranked(listOf(member), metric))
        }
    }
}
