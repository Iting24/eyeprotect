package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "monitoring_status_event",
    indices = [Index("sessionId"), Index("recordedAtEpochMs")],
)
data class MonitoringStatusEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val recordedAtEpochMs: Long,
    val warningsMask: Int,
    val isCameraFrame: Boolean,
    val faceDetected: Boolean,
    val poseDetected: Boolean,
    val faceError: Boolean,
    val poseError: Boolean,
)
