package com.example.eyeprotect.monitoring

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MonitoringModule {
    @Binds
    abstract fun bindMonitoringRepository(impl: InMemoryMonitoringRepository): MonitoringRepository
}

