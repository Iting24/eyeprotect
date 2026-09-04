package com.example.eyeprotect.monitoring

data class MonitoringSessionSnapshot(
    val tooCloseReminderCount: Int,
    val squintReminderCount: Int,
    val slouchReminderCount: Int,
    val tooCloseCorrectionCount: Int,
    val squintCorrectionCount: Int,
    val slouchCorrectionCount: Int,
) {
    fun normalized(): MonitoringSessionSnapshot = copy(
        tooCloseReminderCount = tooCloseReminderCount.coerceAtLeast(0),
        squintReminderCount = squintReminderCount.coerceAtLeast(0),
        slouchReminderCount = slouchReminderCount.coerceAtLeast(0),
        tooCloseCorrectionCount = tooCloseCorrectionCount.coerceIn(0, tooCloseReminderCount.coerceAtLeast(0)),
        squintCorrectionCount = squintCorrectionCount.coerceIn(0, squintReminderCount.coerceAtLeast(0)),
        slouchCorrectionCount = slouchCorrectionCount.coerceIn(0, slouchReminderCount.coerceAtLeast(0)),
    )
}

interface MonitoringReportRepository {
    fun startSession()
    fun syncSessionSnapshot(snapshot: MonitoringSessionSnapshot)
    fun stopSession()
    fun recordMetrics(metrics: MonitoringMetrics)
    fun recordReminder(issueType: MonitoringIssueType)
}
