package com.p2chat.pipeline

/**
 * Listener for routing BENCH_DATA and BENCH_ACK packets directly to BenchRunner (T4.2, BENCH-5).
 * Guarantees zero leakage into chat messages feed or content filter.
 */
interface BenchPacketListener {
    suspend fun onBenchData(msgId: Long, initialTtl: Int, receivedTtl: Int, payload: ByteArray)
    suspend fun onBenchAck(msgId: Long, receivedTtl: Int, ackMonoNs: Long)
}
