package com.example.eyeprotect.nav

import androidx.lifecycle.ViewModel
import com.example.eyeprotect.monitoring.MonitoringRepository
import com.example.eyeprotect.monitoring.MonitoringUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repo: MonitoringRepository
) : ViewModel() {
    val state: StateFlow<MonitoringUiState> = repo.state
}

