package com.p2chat.data.bench

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Room entity representing an individual benchmark packet sample (T4.1, BENCH-1, BENCH-2).
 * Records transmission timestamp, return ACK timestamp, calculated half-RTT, and hop count.
 */
@Entity(
    tableName = "bench_samples",
    primaryKeys = ["run_id", "msg_id"],
    foreignKeys = [
        ForeignKey(
            entity = BenchRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["run_id"]),
        Index(value = ["run_id", "delivered"])
    ]
)
data class BenchSampleEntity(
    @ColumnInfo(name = "run_id")
    val runId: Long,

    @ColumnInfo(name = "msg_id")
    val msgId: Long,

    @ColumnInfo(name = "seq_num")
    val seqNum: Int,

    @ColumnInfo(name = "initial_ttl")
    val initialTtl: Int,

    @ColumnInfo(name = "received_ttl")
    val receivedTtl: Int = 0,

    @ColumnInfo(name = "hop_count")
    val hopCount: Int = 0,

    @ColumnInfo(name = "sent_mono_ns")
    val sentMonoNs: Long,

    @ColumnInfo(name = "ack_mono_ns")
    val ackMonoNs: Long = 0L,

    @ColumnInfo(name = "rtt_half_ms")
    val rttHalfMs: Float = 0f,

    @ColumnInfo(name = "delivered")
    val delivered: Boolean = false
)
