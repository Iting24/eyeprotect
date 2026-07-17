package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitoring_session")
data class MonitoringSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long = 0L
)
