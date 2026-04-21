package com.example.eyeprotect.monitoring

data class MonitoringMetrics(
    val ts: Long = 0L,
    val warningsMask: Int = 0,
    val isLyingActive: Boolean = false,
    val lastFaceDetectedTime: Long = 0L,
    val isCameraFrame: Boolean = false,
    val faceDetected: Boolean = false,
    val poseDetected: Boolean = false,
    val faceError: Boolean = false,
    val poseError: Boolean = false,
    val irisNorm: Float? = null,
    val eyeOpenMin: Float? = null,
    val slouchScore: Float? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val tiltDeg: Float? = null
)

data class MonitoringUiState(
    val isRunning: Boolean = false,
    val metrics: MonitoringMetrics = MonitoringMetrics()
)
