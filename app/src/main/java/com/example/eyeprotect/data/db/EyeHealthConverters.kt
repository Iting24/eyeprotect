package com.example.eyeprotect.data.db

import androidx.room.TypeConverter
import com.example.eyeprotect.monitoring.MonitoringIssueType

class EyeHealthConverters {
    @TypeConverter
    fun postureStatusToString(value: PostureStatus): String = value.name

    @TypeConverter
    fun stringToPostureStatus(value: String): PostureStatus = PostureStatus.valueOf(value)

    @TypeConverter
    fun monitoringIssueTypeToString(value: MonitoringIssueType): String = value.name

    @TypeConverter
    fun stringToMonitoringIssueType(value: String): MonitoringIssueType = MonitoringIssueType.valueOf(value)
}

