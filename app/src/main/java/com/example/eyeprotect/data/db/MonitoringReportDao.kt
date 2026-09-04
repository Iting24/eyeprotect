package com.example.eyeprotect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoringReportDao {

    @Insert
    suspend fun insertSession(entity: MonitoringSessionEntity)

    @Insert
    suspend fun insertStatusEvent(entity: MonitoringStatusEventEntity)

    @Insert
    suspend fun insertReminderEvent(entity: MonitoringReminderEventEntity): Long

    @Query(
        """
        UPDATE monitoring_session
        SET endedAtEpochMs = :endedAtEpochMs,
            durationMs = :durationMs
        WHERE sessionId = :sessionId
        """
    )
    suspend fun completeSession(
        sessionId: String,
        endedAtEpochMs: Long,
        durationMs: Long,
    )

    @Query(
        """
        UPDATE monitoring_session
        SET tooCloseReminderCount = tooCloseReminderCount + :tooCloseDelta,
            squintReminderCount = squintReminderCount + :squintDelta,
            slouchReminderCount = slouchReminderCount + :slouchDelta,
            lyingReminderCount = lyingReminderCount + :lyingDelta
        WHERE sessionId = :sessionId
        """
    )
    suspend fun incrementReminderCounts(
        sessionId: String,
        tooCloseDelta: Int,
        squintDelta: Int,
        slouchDelta: Int,
        lyingDelta: Int,
    )

    @Query(
        """
        UPDATE monitoring_session
        SET tooCloseCorrectionCount = tooCloseCorrectionCount + :tooCloseDelta,
            squintCorrectionCount = squintCorrectionCount + :squintDelta,
            slouchCorrectionCount = slouchCorrectionCount + :slouchDelta,
            lyingCorrectionCount = lyingCorrectionCount + :lyingDelta
        WHERE sessionId = :sessionId
        """
    )
    suspend fun incrementCorrectionCounts(
        sessionId: String,
        tooCloseDelta: Int,
        squintDelta: Int,
        slouchDelta: Int,
        lyingDelta: Int,
    )

    @Query(
        """
        UPDATE monitoring_reminder_event
        SET correctedAtEpochMs = :correctedAtEpochMs,
            correctedWithinWindow = :correctedWithinWindow
        WHERE id = :reminderId
        """
    )
    suspend fun markReminderCorrected(
        reminderId: Long,
        correctedAtEpochMs: Long,
        correctedWithinWindow: Boolean,
    )

    @Query(
        """
        SELECT *
        FROM monitoring_session
        ORDER BY startedAtEpochMs DESC
        LIMIT :limit
        """
    )
    fun observeRecentSessions(limit: Int): Flow<List<MonitoringSessionEntity>>

    @Query(
        """
        SELECT *
        FROM monitoring_session
        WHERE endedAtEpochMs IS NOT NULL
        ORDER BY endedAtEpochMs DESC
        LIMIT 1
        """
    )
    fun observeLatestCompletedSession(): Flow<MonitoringSessionEntity?>

    @Query(
        """
        SELECT *
        FROM monitoring_status_event
        WHERE sessionId = :sessionId
        ORDER BY recordedAtEpochMs ASC, id ASC
        """
    )
    suspend fun getStatusEventsForSession(sessionId: String): List<MonitoringStatusEventEntity>

    @Query(
        """
        SELECT *
        FROM monitoring_reminder_event
        WHERE sessionId = :sessionId
        ORDER BY remindedAtEpochMs ASC, id ASC
        """
    )
    suspend fun getReminderEventsForSession(sessionId: String): List<MonitoringReminderEventEntity>

    @Query(
        """
        UPDATE monitoring_session
        SET tooCloseCorrectionCount = :tooCloseCorrectionCount,
            squintCorrectionCount = :squintCorrectionCount,
            slouchCorrectionCount = :slouchCorrectionCount,
            lyingCorrectionCount = :lyingCorrectionCount
        WHERE sessionId = :sessionId
        """
    )
    suspend fun replaceCorrectionSummary(
        sessionId: String,
        tooCloseCorrectionCount: Int,
        squintCorrectionCount: Int,
        slouchCorrectionCount: Int,
        lyingCorrectionCount: Int,
    )

    @Query(
        """
        UPDATE monitoring_session
        SET tooCloseReminderCount = :tooCloseReminderCount,
            squintReminderCount = :squintReminderCount,
            slouchReminderCount = :slouchReminderCount,
            tooCloseCorrectionCount = :tooCloseCorrectionCount,
            squintCorrectionCount = :squintCorrectionCount,
            slouchCorrectionCount = :slouchCorrectionCount
        WHERE sessionId = :sessionId
        """
    )
    suspend fun replaceSessionSummary(
        sessionId: String,
        tooCloseReminderCount: Int,
        squintReminderCount: Int,
        slouchReminderCount: Int,
        tooCloseCorrectionCount: Int,
        squintCorrectionCount: Int,
        slouchCorrectionCount: Int,
    )
}
