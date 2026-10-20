package com.p2chat.bench

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.BenchSampleEntity
import java.io.File
import java.io.FileWriter

/**
 * Benchmark telemetry CSV serializer and system share sheet dispatcher (T4.11, BENCH-3, SEC-16).
 *
 * Enforces SEC-16: Never touches chat message tables, quarantine logs, or cryptographic keys.
 * Contains zero plaintext message content beyond benchmark sequence and timing metadata.
 */
object BenchCsvExporter {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val BENCHMARKS_DIR = "benchmarks"

    fun exportToCsvString(
        run: BenchRunEntity,
        samples: List<BenchSampleEntity>,
        apiLevel: Int = try { Build.VERSION.SDK_INT } catch (_: Throwable) { 0 }
    ): String {
        val sb = StringBuilder()
        // Metadata Header
        sb.appendLine("# P2Chat Multi-hop Benchmark Telemetry Export (BENCH-3)")
        sb.appendLine("# Run ID: ${run.runId}")
        sb.appendLine("# Started At (ms): ${run.startedAtMs}")
        sb.appendLine("# Finished At (ms): ${run.finishedAtMs}")
        sb.appendLine("# Device Models: ${run.deviceModels.ifBlank { "Unknown" }}")
        sb.appendLine("# Android API Level: $apiLevel")
        sb.appendLine("# Burst Count: ${run.burstCount}")
        sb.appendLine("# Interval (ms): ${run.intervalMs}")
        sb.appendLine("# Payload Size (bytes): ${run.payloadBytes}")
        sb.appendLine("# Initial TTL: ${if (run.initialTtl == 0) "Sweep (1..7)" else run.initialTtl.toString()}")
        sb.appendLine("# Connected Peers: ${run.connectedPeers}")
        sb.appendLine("# PDR (%): ${"%.2f".format(run.pdrPercent)}")
        sb.appendLine("# RTT/2 P50 (ms): ${"%.2f".format(run.rttHalfP50Ms)}")
        sb.appendLine("# RTT/2 P95 (ms): ${"%.2f".format(run.rttHalfP95Ms)}")
        sb.appendLine("# GATT Throughput (bytes/s): ${"%.2f".format(run.throughputBps)}")
        sb.appendLine("# TFLite Inference P50 (ms): ${"%.2f".format(run.inferenceP50Ms)}")
        sb.appendLine("# TFLite Inference P95 (ms): ${"%.2f".format(run.inferenceP95Ms)}")
        sb.appendLine("#")

        // Column Header
        sb.appendLine("seq_num,msg_id_hex,initial_ttl,received_ttl,hop_count,rtt_half_ms,delivered")

        // Per-sample rows sorted by seq_num
        val sortedSamples = samples.sortedBy { it.seqNum }
        for (s in sortedSamples) {
            val msgIdHex = "0x" + s.msgId.toULong().toString(16).padStart(16, '0')
            sb.appendLine("${s.seqNum},$msgIdHex,${s.initialTtl},${s.receivedTtl},${s.hopCount},${"%.2f".format(s.rttHalfMs)},${s.delivered}")
        }

        return sb.toString()
    }

    fun exportToFile(
        context: Context,
        run: BenchRunEntity,
        samples: List<BenchSampleEntity>
    ): File {
        val dir = File(context.filesDir, BENCHMARKS_DIR).apply { mkdirs() }
        val file = File(dir, "bench_${run.runId}.csv")
        val csvContent = exportToCsvString(run, samples)
        FileWriter(file).use { writer ->
            writer.write(csvContent)
        }
        return file
    }

    fun createShareIntent(context: Context, csvFile: File): Intent {
        val authority = "${context.packageName}$AUTHORITY_SUFFIX"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, csvFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "P2Chat Benchmark Telemetry - ${csvFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(shareIntent, "Share Benchmark Telemetry CSV")
    }
}
