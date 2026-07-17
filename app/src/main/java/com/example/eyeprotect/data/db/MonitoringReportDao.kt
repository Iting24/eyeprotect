package com.example.eyeprotect.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MonitoringReportDao {
    @Query("SELECT 1")
    suspend fun ping(): Int
}
