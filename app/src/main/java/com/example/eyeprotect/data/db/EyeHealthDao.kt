package com.example.eyeprotect.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EyeHealthDao {

    @Upsert
    suspend fun upsert(entity: EyeHealthMinuteEntity)

    @Upsert
    suspend fun upsertAll(entities: List<EyeHealthMinuteEntity>)

    @Query(
        """
        SELECT
            :localDate AS localDate,
            SUM(totalDurationMs) AS totalDurationMs,
            SUM(normalDurationMs) AS normalDurationMs,
            SUM(lyingDurationMs) AS lyingDurationMs,
            SUM(slouchingDurationMs) AS slouchingDurationMs,
            SUM(sampleCount) AS totalSamples,
            COUNT(*) AS minuteBuckets,
            (
                SUM(squintProbabilityAvg * totalDurationMs) / NULLIF(SUM(totalDurationMs), 0)
            ) AS squintProbabilityAvg,
            (
                SUM(interpupillaryDistanceAvg * totalDurationMs) / NULLIF(SUM(totalDurationMs), 0)
            ) AS interpupillaryDistanceAvg,
            AVG(fatigueScore) AS fatigueScoreAvg
        FROM eye_health_minute
        WHERE localDate = :localDate
        """,
    )
    fun observeDailySummary(localDate: String): Flow<DailySummary?>

    @Query(
        """
        SELECT
            localDate AS localDate,
            SUM(totalDurationMs) AS totalDurationMs,
            SUM(normalDurationMs) AS normalDurationMs,
            SUM(lyingDurationMs) AS lyingDurationMs,
            SUM(slouchingDurationMs) AS slouchingDurationMs,
            SUM(sampleCount) AS totalSamples,
            COUNT(*) AS minuteBuckets,
            (
                SUM(squintProbabilityAvg * totalDurationMs) / NULLIF(SUM(totalDurationMs), 0)
            ) AS squintProbabilityAvg,
            (
                SUM(interpupillaryDistanceAvg * totalDurationMs) / NULLIF(SUM(totalDurationMs), 0)
            ) AS interpupillaryDistanceAvg,
            AVG(fatigueScore) AS fatigueScoreAvg
        FROM eye_health_minute
        WHERE localDate BETWEEN :startLocalDate AND :endLocalDate
        GROUP BY localDate
        ORDER BY localDate ASC
        """,
    )
    fun observeDailyTrend(
        startLocalDate: String,
        endLocalDate: String,
    ): Flow<List<DailyTrendRow>>
}

data class DailySummary(
    val localDate: String,
    val totalDurationMs: Long,
    val normalDurationMs: Long,
    val lyingDurationMs: Long,
    val slouchingDurationMs: Long,
    val totalSamples: Long,
    val minuteBuckets: Long,
    val squintProbabilityAvg: Double?,
    val interpupillaryDistanceAvg: Double?,
    val fatigueScoreAvg: Double?,
)

data class DailyTrendRow(
    val localDate: String,
    val totalDurationMs: Long,
    val normalDurationMs: Long,
    val lyingDurationMs: Long,
    val slouchingDurationMs: Long,
    val totalSamples: Long,
    val minuteBuckets: Long,
    val squintProbabilityAvg: Double?,
    val interpupillaryDistanceAvg: Double?,
    val fatigueScoreAvg: Double?,
)

