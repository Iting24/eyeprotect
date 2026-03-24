package com.example.eyeprotect.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [EyeHealthMinuteEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(EyeHealthConverters::class)
abstract class EyeHealthDatabase : RoomDatabase() {
    abstract fun eyeHealthDao(): EyeHealthDao
}

