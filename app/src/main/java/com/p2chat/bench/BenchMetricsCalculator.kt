package com.p2chat.bench

import com.p2chat.data.bench.BenchSampleEntity
import kotlin.math.ceil

/**
 * Mathematical calculations and statistical summaries for benchmark runs (T4.9, BENCH-2).
 */
object BenchMetricsCalculator {

    fun pdrPercent(deliveredCount: Int, totalCount: Int): Float {
        return if (totalCount > 0) {
            (deliveredCount.toFloat() / totalCount.toFloat()) * 100f
        } else {
            0f
        }
    }

    /**
     * Calculates the p50 (median) of a sorted list of float values.
     */
    fun p50(sortedValues: List<Float>): Float {
        if (sortedValues.isEmpty()) return 0f
        val n = sortedValues.size
        return if (n % 2 == 1) {
            sortedValues[n / 2]
        } else {
            (sortedValues[(n / 2) - 1] + sortedValues[n / 2]) / 2f
        }
    }

    /**
     * Calculates the p95 percentile of a sorted list of float values.
     */
    fun p95(sortedValues: List<Float>): Float {
        if (sortedValues.isEmpty()) return 0f
        val index = (ceil(0.95 * sortedValues.size).toInt() - 1).coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    /**
     * Calculates GATT throughput in bytes per second.
     */
    fun throughputBps(deliveredCount: Int, payloadBytes: Int, durationSeconds: Double): Float {
        if (durationSeconds <= 0.0 || deliveredCount <= 0) return 0f
        val totalDeliveredBytes = deliveredCount.toDouble() * payloadBytes.toDouble()
        return (totalDeliveredBytes / durationSeconds).toFloat()
    }

    /**
     * Returns the frequency map of hop counts among delivered samples.
     */
    fun hopDistribution(samples: List<BenchSampleEntity>): Map<Int, Int> {
        return samples
            .filter { it.delivered }
            .groupBy { it.hopCount }
            .mapValues { it.value.size }
    }
}
