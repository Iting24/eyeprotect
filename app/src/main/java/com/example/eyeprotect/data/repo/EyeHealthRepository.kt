package com.example.eyeprotect.data.repo

import com.example.eyeprotect.data.db.EyeHealthDao
import com.example.eyeprotect.data.sampling.EyeHealthDownsampler
import com.example.eyeprotect.data.sampling.RealtimeEyeHealthSample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EyeHealthRepository @Inject constructor(
    private val eyeHealthDao: EyeHealthDao,
) {
    private val downsampler = EyeHealthDownsampler()

    suspend fun onRealtimeSample(sample: RealtimeEyeHealthSample) {
        val toInsert = downsampler.add(sample)
        if (toInsert.isNotEmpty()) {
            eyeHealthDao.upsertAll(toInsert)
        }
    }

    suspend fun flush(nowMillis: Long = System.currentTimeMillis()) {
        val toInsert = downsampler.flushExpired(nowMillis)
        if (toInsert.isNotEmpty()) {
            eyeHealthDao.upsertAll(toInsert)
        }
    }
}

