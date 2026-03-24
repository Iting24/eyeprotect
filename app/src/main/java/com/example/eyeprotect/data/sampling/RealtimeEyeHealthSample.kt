package com.example.eyeprotect.data.sampling

import com.example.eyeprotect.data.db.PostureStatus

data class RealtimeEyeHealthSample(
    val timestampMillis: Long,
    val postureStatus: PostureStatus,
    val squintProbability: Double,
    val interpupillaryDistance: Double,
    val packageName: String?,
)

