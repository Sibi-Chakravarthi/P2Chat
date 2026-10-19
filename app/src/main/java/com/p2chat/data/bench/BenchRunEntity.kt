package com.p2chat.data.bench

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a completed or active benchmark run (T4.1, BENCH-1, BENCH-2).
 * Stores test parameters, delivery ratios, latency percentiles, and device metadata.
 */
@Entity(tableName = "bench_runs")
data class BenchRunEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "run_id")
    val runId: Long = 0L,

    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,

    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long = 0L,

    @ColumnInfo(name = "burst_count")
    val burstCount: Int,

    @ColumnInfo(name = "interval_ms")
    val intervalMs: Long,

    @ColumnInfo(name = "payload_bytes")
    val payloadBytes: Int,

    @ColumnInfo(name = "initial_ttl")
    val initialTtl: Int,

    @ColumnInfo(name = "connected_peers")
    val connectedPeers: Int = 0,

    @ColumnInfo(name = "pdr_percent")
    val pdrPercent: Float = 0f,

    @ColumnInfo(name = "rtt_half_p50_ms")
    val rttHalfP50Ms: Float = 0f,

    @ColumnInfo(name = "rtt_half_p95_ms")
    val rttHalfP95Ms: Float = 0f,

    @ColumnInfo(name = "throughput_bps")
    val throughputBps: Float = 0f,

    @ColumnInfo(name = "inference_p50_ms")
    val inferenceP50Ms: Float = 0f,

    @ColumnInfo(name = "inference_p95_ms")
    val inferenceP95Ms: Float = 0f,

    @ColumnInfo(name = "device_models")
    val deviceModels: String = ""
)
