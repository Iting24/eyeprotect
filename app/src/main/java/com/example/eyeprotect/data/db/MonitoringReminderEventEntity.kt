package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.eyeprotect.monitoring.MonitoringIssueType

@Entity(
    tableName = "monitoring_reminder_event",
    indices = [Index("sessionId"), Index("remindedAtEpochMs")],
)
data class MonitoringReminderEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val issueType: MonitoringIssueType,
    val remindedAtEpochMs: Long,
    val correctedAtEpochMs: Long? = null,
    val correctedWithinWindow: Boolean = false,
)
