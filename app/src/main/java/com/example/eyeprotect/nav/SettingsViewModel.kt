package com.example.eyeprotect.nav

import androidx.lifecycle.ViewModel
import com.example.eyeprotect.monitoring.MonitoringRecordState
import com.example.eyeprotect.monitoring.MonitoringReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    reportRepository: MonitoringReportRepository
) : ViewModel() {
    val monitoringRecords: StateFlow<MonitoringRecordState> = reportRepository.monitoringRecords
}
