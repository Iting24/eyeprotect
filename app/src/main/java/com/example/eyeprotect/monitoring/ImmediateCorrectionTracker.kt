package com.example.eyeprotect.monitoring

internal data class ReminderResolution(
    val reminderId: Long,
    val issueType: MonitoringIssueType,
    val correctedAtEpochMs: Long,
    val correctedWithinWindow: Boolean,
)

internal class ImmediateCorrectionTracker(
    private val correctionWindowMs: Long = DEFAULT_CORRECTION_WINDOW_MS,
) {
    private data class PendingReminder(
        val reminderId: Long,
        val issueType: MonitoringIssueType,
        val expiresAtEpochMs: Long,
    )

    private val pendingByIssue = mutableMapOf<MonitoringIssueType, ArrayDeque<PendingReminder>>()

    fun clear() {
        pendingByIssue.clear()
    }

    fun addReminder(
        reminderId: Long,
        issueType: MonitoringIssueType,
        remindedAtEpochMs: Long,
    ) {
        val queue = pendingByIssue.getOrPut(issueType) { ArrayDeque() }
        queue.addLast(
            PendingReminder(
                reminderId = reminderId,
                issueType = issueType,
                expiresAtEpochMs = remindedAtEpochMs + correctionWindowMs,
            )
        )
    }

    fun resolveFromWarnings(
        warningsMask: Int,
        nowEpochMs: Long,
    ): List<ReminderResolution> {
        val resolutions = mutableListOf<ReminderResolution>()

        for (issueType in MonitoringIssueType.values()) {
            val queue = pendingByIssue[issueType] ?: continue
            while (queue.isNotEmpty()) {
                val pending = queue.first()
                val warningStillActive = warningsMask and issueType.mask != 0
                if (!warningStillActive) {
                    queue.removeFirst()
                    resolutions += ReminderResolution(
                        reminderId = pending.reminderId,
                        issueType = pending.issueType,
                        correctedAtEpochMs = nowEpochMs,
                        correctedWithinWindow = nowEpochMs <= pending.expiresAtEpochMs,
                    )
                    continue
                }
                if (nowEpochMs > pending.expiresAtEpochMs) {
                    queue.removeFirst()
                    resolutions += ReminderResolution(
                        reminderId = pending.reminderId,
                        issueType = pending.issueType,
                        correctedAtEpochMs = pending.expiresAtEpochMs,
                        correctedWithinWindow = false,
                    )
                    continue
                }
                break
            }
            if (queue.isEmpty()) {
                pendingByIssue.remove(issueType)
            }
        }

        return resolutions
    }

    companion object {
        const val DEFAULT_CORRECTION_WINDOW_MS = 5_000L
    }
}
