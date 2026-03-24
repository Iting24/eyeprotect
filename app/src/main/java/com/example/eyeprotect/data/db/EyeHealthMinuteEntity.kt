package com.example.eyeprotect.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "eye_health_minute",
    primaryKeys = ["bucketStartMillis", "packageName"],
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["bucketStartMillis"]),
        Index(value = ["packageName"]),
    ],
)
data class EyeHealthMinuteEntity(
    val bucketStartMillis: Long,
    val bucketEndMillis: Long,
    val localDate: String,
    val timezoneId: String,
    val packageName: String,
    val totalDurationMs: Long,
    val sampleCount: Int,
    val normalDurationMs: Long,
    val lyingDurationMs: Long,
    val slouchingDurationMs: Long,
    val dominantPosture: PostureStatus,
    val squintProbabilityAvg: Double,
    val interpupillaryDistanceAvg: Double,
    val fatigueScore: Double? = null,
)

