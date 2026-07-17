package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.eyeprotect.monitoring.MonitoringIssueType

@Entity(tableName = "monitoring_reminder_event")
data class MonitoringReminderEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long = 0L,
    val issueType: MonitoringIssueType,
    val recordedAtEpochMs: Long = 0L
)
