package com.example.eyeprotect.competition

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CompetitionModule {
    @Binds
    abstract fun bindCompetitionRoomRepository(
        implementation: FirebaseCompetitionRoomRepository,
    ): CompetitionRoomRepository
}
