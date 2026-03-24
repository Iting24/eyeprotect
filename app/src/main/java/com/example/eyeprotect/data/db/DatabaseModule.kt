package com.example.eyeprotect.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideEyeHealthDatabase(
        @ApplicationContext context: Context,
    ): EyeHealthDatabase =
        Room.databaseBuilder(context, EyeHealthDatabase::class.java, "eye_health.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideEyeHealthDao(
        database: EyeHealthDatabase,
    ): EyeHealthDao = database.eyeHealthDao()
}

