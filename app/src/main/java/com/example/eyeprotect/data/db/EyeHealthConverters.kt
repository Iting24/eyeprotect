package com.example.eyeprotect.data.db

import androidx.room.TypeConverter

class EyeHealthConverters {
    @TypeConverter
    fun postureStatusToString(value: PostureStatus): String = value.name

    @TypeConverter
    fun stringToPostureStatus(value: String): PostureStatus = PostureStatus.valueOf(value)
}

