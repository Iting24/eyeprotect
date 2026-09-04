package com.example.eyeprotect.monitoring

import com.example.eyeprotect.data.db.MonitoringReminderEventEntity
import com.example.eyeprotect.data.db.MonitoringReportDao
import com.example.eyeprotect.data.db.MonitoringSessionEntity
import com.example.eyeprotect.data.db.MonitoringStatusEventEntity
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RoomMonitoringReportRepository @Inject constructor(
    private val dao: MonitoringReportDao,
) : MonitoringReportRepository {

    private val serialDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutex = Mutex()
    private val correctionTracker = ImmediateCorrectionTracker()

    private var activeSessionId: String? = null
    private var activeSessionStartedAtEpochMs: Long = 0L
    private var lastRecordedWarningsMask: Int? = null

    override fun startSession() {
        runBlocking(serialDispatcher) {
            mutex.withLock {
                if (activeSessionId != null) return@withLock

                val startedAtEpochMs = System.currentTimeMillis()
                val sessionId = UUID.randomUUID().toString()
                activeSessionId = sessionId
                activeSessionStartedAtEpochMs = startedAtEpochMs
                lastRecordedWarningsMask = 0
                correctionTracker.clear()

                dao.insertSession(
                    MonitoringSessionEntity(
                        sessionId = sessionId,
                        startedAtEpochMs = startedAtEpochMs,
                    )
                )
                dao.insertStatusEvent(
                    MonitoringStatusEventEntity(
                        sessionId = sessionId,
                        recordedAtEpochMs = startedAtEpochMs,
                        warningsMask = 0,
                        isCameraFrame = false,
                        faceDetected = false,
                        poseDetected = false,
                        faceError = false,
                        poseError = false,
                    )
                )
            }
        }
    }

    override fun syncSessionSnapshot(snapshot: MonitoringSessionSnapshot) {
        runBlocking(serialDispatcher) {
            mutex.withLock {
                val sessionId = activeSessionId ?: return@withLock
                val normalizedSnapshot = snapshot.normalized()
                dao.replaceSessionSummary(
                    sessionId = sessionId,
                    tooCloseReminderCount = normalizedSnapshot.tooCloseReminderCount,
                    squintReminderCount = normalizedSnapshot.squintReminderCount,
                    slouchReminderCount = normalizedSnapshot.slouchReminderCount,
                    tooCloseCorrectionCount = normalizedSnapshot.tooCloseCorrectionCount,
                    squintCorrectionCount = normalizedSnapshot.squintCorrectionCount,
                    slouchCorrectionCount = normalizedSnapshot.slouchCorrectionCount,
                )
            }
        }
    }

    override fun stopSession() {
        runBlocking(serialDispatcher) {
            mutex.withLock {
                val sessionId = activeSessionId ?: return@withLock
                val endedAtEpochMs = System.currentTimeMillis()
                val durationMs = (endedAtEpochMs - activeSessionStartedAtEpochMs).coerceAtLeast(0L)

                dao.completeSession(
                    sessionId = sessionId,
                    endedAtEpochMs = endedAtEpochMs,
                    durationMs = durationMs,
                )

                activeSessionId = null
                activeSessionStartedAtEpochMs = 0L
                lastRecordedWarningsMask = null
                correctionTracker.clear()
            }
        }
    }

    override fun recordMetrics(metrics: MonitoringMetrics) {
        runBlocking(serialDispatcher) {
            mutex.withLock {
                val sessionId = activeSessionId ?: return@withLock
                val nowEpochMs = System.currentTimeMillis()

                correctionTracker.resolveFromWarnings(
                    warningsMask = metrics.warningsMask,
                    nowEpochMs = nowEpochMs,
                ).forEach { resolution ->
                    if (resolution.correctedWithinWindow) {
                        dao.markReminderCorrected(
                            reminderId = resolution.reminderId,
                            correctedAtEpochMs = resolution.correctedAtEpochMs,
                            correctedWithinWindow = true,
                        )
                        dao.incrementCorrectionCounts(
                            sessionId = sessionId,
                            tooCloseDelta = if (resolution.issueType == MonitoringIssueType.TOO_CLOSE) 1 else 0,
                            squintDelta = if (resolution.issueType == MonitoringIssueType.SQUINTING) 1 else 0,
                            slouchDelta = if (resolution.issueType == MonitoringIssueType.SLOUCHING) 1 else 0,
                            lyingDelta = if (resolution.issueType == MonitoringIssueType.LYING) 1 else 0,
                        )
                    }
                }

                if (lastRecordedWarningsMask == metrics.warningsMask) return@withLock

                lastRecordedWarningsMask = metrics.warningsMask
                dao.insertStatusEvent(
                    MonitoringStatusEventEntity(
                        sessionId = sessionId,
                        recordedAtEpochMs = nowEpochMs,
                        warningsMask = metrics.warningsMask,
                        isCameraFrame = metrics.isCameraFrame,
                        faceDetected = metrics.faceDetected,
                        poseDetected = metrics.poseDetected,
                        faceError = metrics.faceError,
                        poseError = metrics.poseError,
                    )
                )
            }
        }
    }

    override fun recordReminder(issueType: MonitoringIssueType) {
        runBlocking(serialDispatcher) {
            mutex.withLock {
                val sessionId = activeSessionId ?: return@withLock
                val remindedAtEpochMs = System.currentTimeMillis()
                val reminderId = dao.insertReminderEvent(
                    MonitoringReminderEventEntity(
                        sessionId = sessionId,
                        issueType = issueType,
                        remindedAtEpochMs = remindedAtEpochMs,
                    )
                )
                correctionTracker.addReminder(reminderId, issueType, remindedAtEpochMs)
                dao.incrementReminderCounts(
                    sessionId = sessionId,
                    tooCloseDelta = if (issueType == MonitoringIssueType.TOO_CLOSE) 1 else 0,
                    squintDelta = if (issueType == MonitoringIssueType.SQUINTING) 1 else 0,
                    slouchDelta = if (issueType == MonitoringIssueType.SLOUCHING) 1 else 0,
                    lyingDelta = if (issueType == MonitoringIssueType.LYING) 1 else 0,
                )
            }
        }
    }

}
