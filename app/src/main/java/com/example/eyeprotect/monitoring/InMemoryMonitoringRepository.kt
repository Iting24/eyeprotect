package com.example.eyeprotect.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryMonitoringRepository @Inject constructor() : MonitoringRepository {

    private val _state = MutableStateFlow(MonitoringUiState())
    override val state: StateFlow<MonitoringUiState> = _state

    override fun setRunning(running: Boolean) {
        _state.update { it.copy(isRunning = running) }
    }

    override fun updateMetrics(metrics: MonitoringMetrics) {
        _state.update { it.copy(metrics = metrics) }
    }
}

