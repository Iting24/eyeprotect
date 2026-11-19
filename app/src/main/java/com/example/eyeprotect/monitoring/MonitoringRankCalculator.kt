package com.example.eyeprotect.monitoring

import com.example.eyeprotect.data.db.MonitoringStatusEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Raw totals are kept so longer periods never average daily ratios. */
data class RankTotals(
    val effectiveMs: Long = 0L,
    val slouchMs: Long = 0L,
    val squintEvents: Int = 0,
    val tooCloseEvents: Int = 0,
) {
    val slouchRatio: Double? get() = if (effectiveMs > 0) slouchMs.toDouble() / effectiveMs else null
    val squintPerHour: Double? get() = if (effectiveMs > 0) squintEvents * 3_600_000.0 / effectiveMs else null
    val tooClosePerHour: Double? get() = if (effectiveMs > 0) tooCloseEvents * 3_600_000.0 / effectiveMs else null

    operator fun plus(other: RankTotals) = RankTotals(
        effectiveMs + other.effectiveMs,
        slouchMs + other.slouchMs,
        squintEvents + other.squintEvents,
        tooCloseEvents + other.tooCloseEvents,
    )
}
enum class ReportPeriod { DAY, WEEK, MONTH }

data class ReportWindow(val startMs: Long, val endMs: Long) {
    companion object {
        fun containing(nowMs: Long, zone: ZoneId, period: ReportPeriod): ReportWindow {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val start = when (period) {
                ReportPeriod.DAY -> today
                ReportPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                ReportPeriod.MONTH -> today.withDayOfMonth(1)
            }
            val end = when (period) {
                ReportPeriod.DAY -> start.plusDays(1)
                ReportPeriod.WEEK -> start.plusWeeks(1)
                ReportPeriod.MONTH -> start.plusMonths(1)
            }
            return ReportWindow(start.atStartOfDay(zone).toInstant().toEpochMilli(),
                end.atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }
}

/** Converts persisted camera observations into duration and incident totals. */
object MonitoringRankCalculator {
    const val MIN_RANK_MS = 30 * 60_000L
    private const val MAX_SAMPLE_GAP_MS = 7_000L
    private const val INCIDENT_COOLDOWN_MS = 30_000L

    fun totals(events: List<MonitoringStatusEventEntity>, window: ReportWindow): RankTotals {
        var effective = 0L
        var slouch = 0L
        var squints = 0
        var tooClose = 0
        events.groupBy { it.sessionId }.values.forEach { session ->
            var previous: MonitoringStatusEventEntity? = null
            var lastSquint = Long.MIN_VALUE
            var lastTooClose = Long.MIN_VALUE
            session.sortedWith(compareBy<MonitoringStatusEventEntity> { it.recordedAtEpochMs }.thenBy { it.id })
                .forEach { event ->
                    val before = previous
                    if (before != null && valid(before)) {
                        val from = maxOf(before.recordedAtEpochMs, window.startMs)
                        val to = minOf(event.recordedAtEpochMs, before.recordedAtEpochMs + MAX_SAMPLE_GAP_MS, window.endMs)
                        if (to > from) {
                            effective += to - from
                            if (before.warningsMask and MonitoringIssueType.SLOUCHING.mask != 0) slouch += to - from
                        }
                    }
                    if (valid(event)) {
                        val at = event.recordedAtEpochMs
                        val priorMask = if (before != null && valid(before)) before.warningsMask else 0
                        if (event.warningsMask and MonitoringIssueType.SQUINTING.mask != 0 &&
                            priorMask and MonitoringIssueType.SQUINTING.mask == 0 &&
                            (lastSquint == Long.MIN_VALUE || at - lastSquint >= INCIDENT_COOLDOWN_MS)
                        ) {
                            if (at >= window.startMs && at < window.endMs) squints++
                            lastSquint = at
                        }
                        if (event.warningsMask and MonitoringIssueType.TOO_CLOSE.mask != 0 &&
                            priorMask and MonitoringIssueType.TOO_CLOSE.mask == 0 &&
                            (lastTooClose == Long.MIN_VALUE || at - lastTooClose >= INCIDENT_COOLDOWN_MS)
                        ) {
                            if (at >= window.startMs && at < window.endMs) tooClose++
                            lastTooClose = at
                        }
                    }
                    previous = event
                }
        }
        return RankTotals(effective, slouch, squints, tooClose)
    }

    private fun valid(event: MonitoringStatusEventEntity) =
        event.isCameraFrame && event.faceDetected && event.poseDetected && !event.faceError && !event.poseError
}

enum class RankMetric { EFFECTIVE_TIME, SLOUCH_RATIO, SQUINT_FREQUENCY, TOO_CLOSE_FREQUENCY }

data class RoomMemberScore(val memberId: String, val displayName: String, val totals: RankTotals)

object CompetitionRanking {
    fun ranked(members: List<RoomMemberScore>, metric: RankMetric): List<RoomMemberScore> =
        members.filter { it.totals.effectiveMs >= MonitoringRankCalculator.MIN_RANK_MS }
            .sortedWith(compareBy<RoomMemberScore> {
                when (metric) {
                    RankMetric.EFFECTIVE_TIME -> -it.totals.effectiveMs.toDouble()
                    RankMetric.SLOUCH_RATIO -> it.totals.slouchRatio!!
                    RankMetric.SQUINT_FREQUENCY -> it.totals.squintPerHour!!
                    RankMetric.TOO_CLOSE_FREQUENCY -> it.totals.tooClosePerHour!!
                }
            }.thenByDescending { it.totals.effectiveMs }.thenBy { it.memberId })
}
