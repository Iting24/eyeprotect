package com.example.eyeprotect.data.sampling

import com.example.eyeprotect.data.db.EyeHealthMinuteEntity
import com.example.eyeprotect.data.db.PostureStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class EyeHealthDownsampler(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val accumulators = LinkedHashMap<MinuteKey, MinuteAccumulator>()

    fun add(sample: RealtimeEyeHealthSample): List<EyeHealthMinuteEntity> {
        val normalizedPackageName = sample.packageName?.ifBlank { "" } ?: ""
        val bucketStartMillis = floorToMinute(sample.timestampMillis)
        val key = MinuteKey(bucketStartMillis = bucketStartMillis, packageName = normalizedPackageName)

        val flushed = mutableListOf<EyeHealthMinuteEntity>()
        val existing = accumulators[key]
        if (existing == null) {
            flushed += flushOlderBuckets(
                bucketStartMillis = bucketStartMillis,
                packageName = normalizedPackageName,
            )
            accumulators[key] = MinuteAccumulator(
                packageName = normalizedPackageName,
                bucketStartMillis = bucketStartMillis,
                bucketEndMillis = bucketStartMillis + MILLIS_PER_MINUTE,
            )
        }

        accumulators[key]?.addSample(sample)
        return flushed
    }

    fun flushExpired(nowMillis: Long): List<EyeHealthMinuteEntity> {
        val cutoffBucketStart = floorToMinute(nowMillis) - MILLIS_PER_MINUTE
        val flushed = mutableListOf<EyeHealthMinuteEntity>()

        val iterator = accumulators.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.bucketStartMillis <= cutoffBucketStart) {
                flushed += entry.value.finalizeAndBuildEntity(
                    zoneId = zoneId,
                    dateFormatter = dateFormatter,
                    timezoneId = zoneId.id,
                )
                iterator.remove()
            }
        }
        return flushed
    }

    private fun flushOlderBuckets(bucketStartMillis: Long, packageName: String): List<EyeHealthMinuteEntity> {
        val flushed = mutableListOf<EyeHealthMinuteEntity>()
        val iterator = accumulators.entries.iterator()
        while (iterator.hasNext()) {
            val (key, accumulator) = iterator.next()
            val sameApp = key.packageName == packageName
            val olderBucket = key.bucketStartMillis < bucketStartMillis
            if (sameApp && olderBucket) {
                flushed += accumulator.finalizeAndBuildEntity(
                    zoneId = zoneId,
                    dateFormatter = dateFormatter,
                    timezoneId = zoneId.id,
                )
                iterator.remove()
            }
        }
        return flushed
    }

    private fun floorToMinute(timestampMillis: Long): Long = timestampMillis - (timestampMillis % MILLIS_PER_MINUTE)

    private data class MinuteKey(
        val bucketStartMillis: Long,
        val packageName: String,
    )

    private class MinuteAccumulator(
        private val packageName: String,
        private val bucketStartMillis: Long,
        private val bucketEndMillis: Long,
    ) {
        private var lastTimestampMillis: Long? = null
        private var lastPosture: PostureStatus? = null
        private var lastSquintProbability: Double = 0.0
        private var lastInterpupillaryDistance: Double = 0.0

        private var totalDurationMs: Long = 0L
        private var normalDurationMs: Long = 0L
        private var lyingDurationMs: Long = 0L
        private var slouchingDurationMs: Long = 0L

        private var squintIntegral: Double = 0.0
        private var interpupillaryDistanceIntegral: Double = 0.0

        private var sampleCount: Int = 0

        fun addSample(sample: RealtimeEyeHealthSample) {
            val clampedTimestamp = sample.timestampMillis.coerceIn(bucketStartMillis, bucketEndMillis)
            val previousTimestamp = lastTimestampMillis
            if (previousTimestamp != null && lastPosture != null) {
                val dt = max(0L, clampedTimestamp - previousTimestamp)
                addSegment(durationMs = dt)
            }

            lastTimestampMillis = clampedTimestamp
            lastPosture = sample.postureStatus
            lastSquintProbability = sample.squintProbability.coerceIn(0.0, 1.0)
            lastInterpupillaryDistance = sample.interpupillaryDistance
            sampleCount += 1
        }

        fun finalizeAndBuildEntity(
            zoneId: ZoneId,
            dateFormatter: DateTimeFormatter,
            timezoneId: String,
        ): EyeHealthMinuteEntity {
            val previousTimestamp = lastTimestampMillis
            if (previousTimestamp != null && lastPosture != null) {
                val dt = max(0L, bucketEndMillis - previousTimestamp)
                addSegment(durationMs = dt)
            }

            val dominant = dominantPosture(
                normalDurationMs = normalDurationMs,
                lyingDurationMs = lyingDurationMs,
                slouchingDurationMs = slouchingDurationMs,
            )

            val squintAvg = if (totalDurationMs > 0) {
                squintIntegral / totalDurationMs.toDouble()
            } else {
                lastSquintProbability
            }

            val interpupillaryAvg = if (totalDurationMs > 0) {
                interpupillaryDistanceIntegral / totalDurationMs.toDouble()
            } else {
                lastInterpupillaryDistance
            }

            val localDate = Instant.ofEpochMilli(bucketStartMillis)
                .atZone(zoneId)
                .toLocalDate()
                .format(dateFormatter)

            return EyeHealthMinuteEntity(
                bucketStartMillis = bucketStartMillis,
                bucketEndMillis = bucketEndMillis,
                localDate = localDate,
                timezoneId = timezoneId,
                packageName = packageName,
                totalDurationMs = totalDurationMs,
                sampleCount = sampleCount,
                normalDurationMs = normalDurationMs,
                lyingDurationMs = lyingDurationMs,
                slouchingDurationMs = slouchingDurationMs,
                dominantPosture = dominant,
                squintProbabilityAvg = squintAvg,
                interpupillaryDistanceAvg = interpupillaryAvg,
                fatigueScore = null,
            )
        }

        private fun addSegment(durationMs: Long) {
            if (durationMs <= 0L) return
            totalDurationMs += durationMs

            when (lastPosture) {
                PostureStatus.NORMAL -> normalDurationMs += durationMs
                PostureStatus.LYING -> lyingDurationMs += durationMs
                PostureStatus.SLOUCHING -> slouchingDurationMs += durationMs
                null -> Unit
            }

            squintIntegral += lastSquintProbability * durationMs.toDouble()
            interpupillaryDistanceIntegral += lastInterpupillaryDistance * durationMs.toDouble()
        }

        private fun dominantPosture(
            normalDurationMs: Long,
            lyingDurationMs: Long,
            slouchingDurationMs: Long,
        ): PostureStatus {
            val maxDuration = max(normalDurationMs, max(lyingDurationMs, slouchingDurationMs))
            return when {
                maxDuration == lyingDurationMs -> PostureStatus.LYING
                maxDuration == slouchingDurationMs -> PostureStatus.SLOUCHING
                else -> PostureStatus.NORMAL
            }
        }
    }

    private companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
