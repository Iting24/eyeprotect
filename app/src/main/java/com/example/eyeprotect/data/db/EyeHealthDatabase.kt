package com.example.eyeprotect.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        EyeHealthMinuteEntity::class,
        MonitoringSessionEntity::class,
        MonitoringStatusEventEntity::class,
        MonitoringReminderEventEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(EyeHealthConverters::class)
abstract class EyeHealthDatabase : RoomDatabase() {
    abstract fun eyeHealthDao(): EyeHealthDao
    abstract fun monitoringReportDao(): MonitoringReportDao
}

