package com.example.eyeprotect.monitoring

import kotlinx.coroutines.flow.StateFlow

interface MonitoringRepository {
    val state: StateFlow<MonitoringUiState>
    fun setRunning(running: Boolean)
    fun updateMetrics(metrics: MonitoringMetrics)
}

