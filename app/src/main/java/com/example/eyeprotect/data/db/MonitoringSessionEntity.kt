package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitoring_session")
data class MonitoringSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val durationMs: Long = 0L,
    val tooCloseReminderCount: Int = 0,
    val squintReminderCount: Int = 0,
    val slouchReminderCount: Int = 0,
    val lyingReminderCount: Int = 0,
    val tooCloseCorrectionCount: Int = 0,
    val squintCorrectionCount: Int = 0,
    val slouchCorrectionCount: Int = 0,
    val lyingCorrectionCount: Int = 0,
)
