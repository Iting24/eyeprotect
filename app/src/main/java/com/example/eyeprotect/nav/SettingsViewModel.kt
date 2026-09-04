package com.example.eyeprotect.nav

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyeprotect.EyeHealthAccessibilityService
import com.example.eyeprotect.data.db.MonitoringReportDao
import com.example.eyeprotect.data.db.MonitoringSessionEntity
import com.example.eyeprotect.monitoring.MonitoringForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MonitoringSummaryUi(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val durationMs: Long,
    val tooCloseReminderCount: Int,
    val squintReminderCount: Int,
    val slouchReminderCount: Int,
    val tooCloseCorrectionCount: Int,
    val squintCorrectionCount: Int,
    val slouchCorrectionCount: Int,
    val isOngoing: Boolean = false,
)

data class MonitoringDailySummaryUi(
    val dateLabel: String,
    val totalDurationMs: Long,
    val tooCloseReminderCount: Int,
    val squintReminderCount: Int,
    val slouchReminderCount: Int,
    val tooCloseCorrectionCount: Int,
    val squintCorrectionCount: Int,
    val slouchCorrectionCount: Int,
)

data class MonitoringRecordUiState(
    val todaySummary: MonitoringDailySummaryUi? = null,
    val todaySessions: List<MonitoringSummaryUi> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val monitoringReportDao: MonitoringReportDao,
) : ViewModel() {
    private val _monitoringRecords = MutableStateFlow(MonitoringRecordUiState())
    val monitoringRecords: StateFlow<MonitoringRecordUiState> = _monitoringRecords.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("eyeprotect_prefs", Context.MODE_PRIVATE)
    private var durationRefreshJob: Job? = null
    private var recentSessions: List<MonitoringSessionEntity> = emptyList()
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in monitoredKeys) refresh()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        observeSessions()
        refresh()
        startDurationRefreshLoop()
    }

    fun refresh() {
        val nowEpochMs = System.currentTimeMillis()
        val monitoringEnabled = prefs.getBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, false)
        val todayRange = todayRange(nowEpochMs)
        val activeStartedAt = prefs.getLong(MonitoringForegroundService.PREF_LAST_SESSION_STARTED_AT, 0L)
        val ongoingSession = buildOngoingSession(nowEpochMs, monitoringEnabled, activeStartedAt)

        val completedTodaySessions = recentSessions
            .filter { it.startedAtEpochMs in todayRange.first..todayRange.second }
            .filter { it.endedAtEpochMs != null }
            .map { it.toUi(nowEpochMs, monitoringEnabled, activeStartedAt) }

        val todaySessions = buildList {
            if (ongoingSession != null && ongoingSession.startedAtEpochMs in todayRange.first..todayRange.second) {
                add(ongoingSession)
            }
            addAll(completedTodaySessions)
        }.sortedByDescending { it.startedAtEpochMs }

        val summarySessions = buildList {
            addAll(completedTodaySessions)
            if (ongoingSession != null && ongoingSession.startedAtEpochMs in todayRange.first..todayRange.second) {
                add(ongoingSession)
            }
        }

        val todaySummary = if (summarySessions.isNotEmpty()) {
            MonitoringDailySummaryUi(
                dateLabel = formatTodayLabel(nowEpochMs),
                totalDurationMs = summarySessions.sumOf { it.durationMs },
                tooCloseReminderCount = summarySessions.sumOf { it.tooCloseReminderCount },
                squintReminderCount = summarySessions.sumOf { it.squintReminderCount },
                slouchReminderCount = summarySessions.sumOf { it.slouchReminderCount },
                tooCloseCorrectionCount = summarySessions.sumOf { it.tooCloseCorrectionCount },
                squintCorrectionCount = summarySessions.sumOf { it.squintCorrectionCount },
                slouchCorrectionCount = summarySessions.sumOf { it.slouchCorrectionCount },
            )
        } else {
            null
        }

        _monitoringRecords.value = MonitoringRecordUiState(
            todaySummary = todaySummary,
            todaySessions = todaySessions,
        )
    }

    override fun onCleared() {
        durationRefreshJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    private fun observeSessions() {
        viewModelScope.launch {
            monitoringReportDao.observeRecentSessions(120).collect {
                recentSessions = it
                refresh()
            }
        }
    }

    private fun startDurationRefreshLoop() {
        durationRefreshJob?.cancel()
        durationRefreshJob = viewModelScope.launch {
            while (true) {
                if (prefs.getBoolean(EyeHealthAccessibilityService.PREF_MONITORING_ENABLED, false)) {
                    refresh()
                }
                delay(1000)
            }
        }
    }

    private fun MonitoringSessionEntity.toUi(
        nowEpochMs: Long,
        monitoringEnabled: Boolean,
        activeSessionStartedAtEpochMs: Long,
    ): MonitoringSummaryUi {
        val isOngoing = monitoringEnabled && endedAtEpochMs == null && startedAtEpochMs == activeSessionStartedAtEpochMs
        val effectiveDurationMs = if (isOngoing) {
            (nowEpochMs - startedAtEpochMs).coerceAtLeast(0L)
        } else {
            durationMs
        }
        return MonitoringSummaryUi(
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            durationMs = effectiveDurationMs,
            tooCloseReminderCount = tooCloseReminderCount,
            squintReminderCount = squintReminderCount,
            slouchReminderCount = slouchReminderCount,
            tooCloseCorrectionCount = tooCloseCorrectionCount.coerceIn(0, tooCloseReminderCount.coerceAtLeast(0)),
            squintCorrectionCount = squintCorrectionCount.coerceIn(0, squintReminderCount.coerceAtLeast(0)),
            slouchCorrectionCount = slouchCorrectionCount.coerceIn(0, slouchReminderCount.coerceAtLeast(0)),
            isOngoing = isOngoing,
        )
    }

    private fun buildOngoingSession(
        nowEpochMs: Long,
        monitoringEnabled: Boolean,
        activeSessionStartedAtEpochMs: Long,
    ): MonitoringSummaryUi? {
        if (!monitoringEnabled || activeSessionStartedAtEpochMs <= 0L) return null

        return MonitoringSummaryUi(
            startedAtEpochMs = activeSessionStartedAtEpochMs,
            endedAtEpochMs = null,
            durationMs = (nowEpochMs - activeSessionStartedAtEpochMs).coerceAtLeast(0L),
            tooCloseReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT, 0),
            squintReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SQUINT_COUNT, 0),
            slouchReminderCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT, 0),
            tooCloseCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_CORRECTION_COUNT, 0)
                .coerceIn(0, prefs.getInt(MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT, 0).coerceAtLeast(0)),
            squintCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SQUINT_CORRECTION_COUNT, 0)
                .coerceIn(0, prefs.getInt(MonitoringForegroundService.PREF_LAST_SQUINT_COUNT, 0).coerceAtLeast(0)),
            slouchCorrectionCount = prefs.getInt(MonitoringForegroundService.PREF_LAST_SLOUCH_CORRECTION_COUNT, 0)
                .coerceIn(0, prefs.getInt(MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT, 0).coerceAtLeast(0)),
            isOngoing = true,
        )
    }

    private fun todayRange(nowEpochMs: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        return start to (calendar.timeInMillis - 1L)
    }

    private fun formatTodayLabel(nowEpochMs: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
        return "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}"
    }

    companion object {
        private val monitoredKeys = setOf(
            EyeHealthAccessibilityService.PREF_MONITORING_ENABLED,
            MonitoringForegroundService.PREF_LAST_SESSION_STARTED_AT,
            MonitoringForegroundService.PREF_LAST_SESSION_DURATION_MS,
            MonitoringForegroundService.PREF_LAST_TOO_CLOSE_COUNT,
            MonitoringForegroundService.PREF_LAST_SQUINT_COUNT,
            MonitoringForegroundService.PREF_LAST_SLOUCH_COUNT,
            MonitoringForegroundService.PREF_LAST_TOO_CLOSE_CORRECTION_COUNT,
            MonitoringForegroundService.PREF_LAST_SQUINT_CORRECTION_COUNT,
            MonitoringForegroundService.PREF_LAST_SLOUCH_CORRECTION_COUNT,
        )
    }
}
