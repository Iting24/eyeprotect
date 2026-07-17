package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.eyeprotect.monitoring.MonitoringIssueType

@Entity(tableName = "monitoring_status_event")
data class MonitoringStatusEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long = 0L,
    val issueType: MonitoringIssueType,
    val active: Boolean = false,
    val recordedAtEpochMs: Long = 0L
)
