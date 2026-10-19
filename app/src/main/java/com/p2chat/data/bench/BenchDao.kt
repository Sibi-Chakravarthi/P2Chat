package com.p2chat.data.bench

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Breakdown of packet delivery ratio per initial TTL (BENCH-1, BENCH-2).
 */
data class TtlPdrStats(
    @ColumnInfo(name = "initial_ttl") val initialTtl: Int,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "delivered") val delivered: Int
) {
    val pdrPercent: Float
        get() = if (total > 0) (delivered.toFloat() / total.toFloat()) * 100f else 0f
}

/**
 * Composite entity linking a benchmark run header with all its packet telemetry samples (BENCH-3).
 */
data class BenchRunWithSamples(
    @Embedded val run: BenchRunEntity,
    @Relation(parentColumn = "run_id", entityColumn = "run_id")
    val samples: List<BenchSampleEntity>
)

/**
 * DAO for benchmark runs and telemetry samples (T4.1, BENCH-1, BENCH-2, BENCH-3).
 */
@Dao
interface BenchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: BenchRunEntity): Long

    @Update
    suspend fun updateRun(run: BenchRunEntity)

    @Query("SELECT * FROM bench_runs WHERE run_id = :runId LIMIT 1")
    suspend fun getRun(runId: Long): BenchRunEntity?

    @Query("SELECT * FROM bench_runs ORDER BY started_at_ms DESC")
    suspend fun getAllRuns(): List<BenchRunEntity>

    @Query("SELECT * FROM bench_runs ORDER BY started_at_ms DESC")
    fun getAllRunsFlow(): Flow<List<BenchRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: BenchSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<BenchSampleEntity>)

    @Query("SELECT * FROM bench_samples WHERE run_id = :runId ORDER BY seq_num ASC")
    suspend fun getSamplesForRun(runId: Long): List<BenchSampleEntity>

    @Query(
        """
        UPDATE bench_samples 
        SET ack_mono_ns = :ackMonoNs, 
            received_ttl = :receivedTtl, 
            hop_count = :hopCount, 
            rtt_half_ms = :rttHalfMs, 
            delivered = 1 
        WHERE run_id = :runId AND msg_id = :msgId
    """
    )
    suspend fun recordAck(
        runId: Long,
        msgId: Long,
        ackMonoNs: Long,
        receivedTtl: Int,
        hopCount: Int,
        rttHalfMs: Float
    ): Int

    @Query("SELECT COUNT(*) FROM bench_samples WHERE run_id = :runId")
    suspend fun getTotalSampleCount(runId: Long): Int

    @Query("SELECT COUNT(*) FROM bench_samples WHERE run_id = :runId AND delivered = 1")
    suspend fun getDeliveredCount(runId: Long): Int

    @Query("SELECT rtt_half_ms FROM bench_samples WHERE run_id = :runId AND delivered = 1 ORDER BY rtt_half_ms ASC")
    suspend fun getDeliveredRttsSorted(runId: Long): List<Float>

    @Query(
        """
        SELECT initial_ttl, 
               COUNT(*) AS total, 
               SUM(CASE WHEN delivered = 1 THEN 1 ELSE 0 END) AS delivered 
        FROM bench_samples 
        WHERE run_id = :runId 
        GROUP BY initial_ttl 
        ORDER BY initial_ttl ASC
    """
    )
    suspend fun getTtlPdrBreakdown(runId: Long): List<TtlPdrStats>

    @Transaction
    @Query("SELECT * FROM bench_runs WHERE run_id = :runId")
    suspend fun getRunWithSamples(runId: Long): BenchRunWithSamples?

    @Query("DELETE FROM bench_runs WHERE run_id = :runId")
    suspend fun deleteRun(runId: Long): Int

    @Query("DELETE FROM bench_runs")
    suspend fun deleteAllRuns(): Int
}
