package com.example.eyeprotect.monitoring

import android.content.Context
import com.example.eyeprotect.PreferenceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class MonitoringIssueType(val mask: Int) {
    TOO_CLOSE(1),
    SQUINTING(2),
    SLOUCHING(4),
    LYING(8)
}

object ImmediateCorrectionTracker {
    const val DEFAULT_CORRECTION_WINDOW_MS = 60_000L
}

data class MonitoringSummaryUi(
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val tooCloseReminderCount: Int,
    val tooCloseCorrectionCount: Int,
    val squintReminderCount: Int,
    val squintCorrectionCount: Int,
    val slouchReminderCount: Int,
    val slouchCorrectionCount: Int
)

data class MonitoringRecordState(
    val latestCompletedSession: MonitoringSummaryUi? = null
)

interface MonitoringReportRepository {
    val monitoringRecords: StateFlow<MonitoringRecordState>
    fun startSession()
    fun recordMetrics(metrics: MonitoringMetrics)
    fun recordReminder(issueType: MonitoringIssueType)
    fun stopSession()
}

@Singleton
class RoomMonitoringReportRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : MonitoringReportRepository {

    private val _monitoringRecords = MutableStateFlow(MonitoringRecordState(loadLatestCompletedSession()))
    override val monitoringRecords: StateFlow<MonitoringRecordState> = _monitoringRecords.asStateFlow()

    override fun startSession() = Unit

    override fun recordMetrics(metrics: MonitoringMetrics) = Unit

    override fun recordReminder(issueType: MonitoringIssueType) = Unit

    override fun stopSession() {
        _monitoringRecords.value = MonitoringRecordState(loadLatestCompletedSession())
    }

    private fun loadLatestCompletedSession(): MonitoringSummaryUi? {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val startedAt = prefs.getLong(MonitoringForegroundService.PREF_LAST_SESSION_STARTED_AT, 0L)
        val durationMs = prefs.getLong(MonitoringForegroundService.PREF_LAST_SESSION_DURATION_MS, 0L)
        if (startedAt <= 0L || durationMs <= 0L) return null

        return MonitoringSummaryUi(
            startedAtEpochMs = startedAt,
            durationMs = durationMs,
            tooCloseReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT, 0),
            tooCloseCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_CORRECTION_COUNT, 0),
            squintReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SQUINT_COUNT, 0),
            squintCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SQUINT_CORRECTION_COUNT, 0),
            slouchReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT, 0),
            slouchCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SLOUCH_CORRECTION_COUNT, 0),
        )
    }
}
