package com.example.eyeprotect

import android.content.Context
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EyeHealthModule {

    @Provides
    @Singleton
    fun provideFaceDetectorOptions(): FaceDetectorOptions =
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()

    @Provides
    @Singleton
    fun provideFaceDetector(options: FaceDetectorOptions): FaceDetector =
        FaceDetection.getClient(options)

    @Provides
    fun provideTextToSpeech(@ApplicationContext context: Context): android.speech.tts.TextToSpeech =
        android.speech.tts.TextToSpeech(context, null)
}
