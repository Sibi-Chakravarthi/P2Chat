package com.p2chat.bench

import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.BenchSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchCsvExporterTest {

    @Test
    fun testExportToCsvString_containsAllRequiredMetadataAndColumns() {
        val run = BenchRunEntity(
            runId = 42L,
            startedAtMs = 1700000000000L,
            finishedAtMs = 1700000010000L,
            burstCount = 3,
            intervalMs = 100L,
            payloadBytes = 350,
            initialTtl = 7,
            connectedPeers = 2,
            pdrPercent = 100f,
            rttHalfP50Ms = 12.5f,
            rttHalfP95Ms = 18.2f,
            throughputBps = 3500f,
            inferenceP50Ms = 8.1f,
            inferenceP95Ms = 14.3f,
            deviceModels = "Nothing Phone (3a) Pro"
        )

        val samples = listOf(
            BenchSampleEntity(
                runId = 42L,
                msgId = 0x1122334455667788L,
                seqNum = 1,
                initialTtl = 7,
                receivedTtl = 5,
                hopCount = 2,
                sentMonoNs = 1000000000L,
                ackMonoNs = 1025000000L,
                rttHalfMs = 12.5f,
                delivered = true
            ),
            BenchSampleEntity(
                runId = 42L,
                msgId = 0x79AABBCCDDEEFF00L,
                seqNum = 0,
                initialTtl = 7,
                receivedTtl = 5,
                hopCount = 2,
                sentMonoNs = 1000000000L,
                ackMonoNs = 1036400000L,
                rttHalfMs = 18.2f,
                delivered = true
            ),
            BenchSampleEntity(
                runId = 42L,
                msgId = 0x0011223344556677L,
                seqNum = 2,
                initialTtl = 7,
                receivedTtl = 0,
                hopCount = 0,
                sentMonoNs = 1000000000L,
                ackMonoNs = 0L,
                rttHalfMs = 0f,
                delivered = false
            )
        )

        val csv = BenchCsvExporter.exportToCsvString(run, samples, apiLevel = 34)

        // Metadata Header Checks
        assertTrue(csv.contains("# P2Chat Multi-hop Benchmark Telemetry Export (BENCH-3)"))
        assertTrue(csv.contains("# Run ID: 42"))
        assertTrue(csv.contains("# Started At (ms): 1700000000000"))
        assertTrue(csv.contains("# Device Models: Nothing Phone (3a) Pro"))
        assertTrue(csv.contains("# Android API Level: 34"))
        assertTrue(csv.contains("# Burst Count: 3"))
        assertTrue(csv.contains("# Interval (ms): 100"))
        assertTrue(csv.contains("# Payload Size (bytes): 350"))
        assertTrue(csv.contains("# Initial TTL: 7"))
        assertTrue(csv.contains("# Connected Peers: 2"))
        assertTrue(csv.contains("# PDR (%): 100.00"))
        assertTrue(csv.contains("# RTT/2 P50 (ms): 12.50"))
        assertTrue(csv.contains("# RTT/2 P95 (ms): 18.20"))
        assertTrue(csv.contains("# GATT Throughput (bytes/s): 3500.00"))
        assertTrue(csv.contains("# TFLite Inference P50 (ms): 8.10"))
        assertTrue(csv.contains("# TFLite Inference P95 (ms): 14.30"))

        // Column Header Check
        assertTrue(csv.contains("seq_num,msg_id_hex,initial_ttl,received_ttl,hop_count,rtt_half_ms,delivered"))

        // Row Checks & Sorting (seq_num 0 must precede seq_num 1)
        val lines = csv.lines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(4, lines.size) // header + 3 rows
        assertEquals("seq_num,msg_id_hex,initial_ttl,received_ttl,hop_count,rtt_half_ms,delivered", lines[0])

        // seq_num 0
        assertTrue(lines[1].startsWith("0,0x79aabbccddeeff00,7,5,2,18.20,true"))
        // seq_num 1
        assertTrue(lines[2].startsWith("1,0x1122334455667788,7,5,2,12.50,true"))
        // seq_num 2 (undelivered)
        assertTrue(lines[3].startsWith("2,0x0011223344556677,7,0,0,0.00,false"))
    }

    @Test
    fun testExportToCsvString_enforcesSec16ZeroLeakage() {
        val run = BenchRunEntity(
            runId = 1L,
            startedAtMs = 1700000000000L,
            burstCount = 1,
            intervalMs = 100L,
            payloadBytes = 64,
            initialTtl = 7
        )
        val samples = listOf(
            BenchSampleEntity(
                runId = 1L,
                msgId = 0x12345678L,
                seqNum = 0,
                initialTtl = 7,
                sentMonoNs = 1000L,
                delivered = true
            )
        )

        val csv = BenchCsvExporter.exportToCsvString(run, samples)

        // SEC-16: Zero chat plaintext, zero symmetric keys, zero private keys
        assertFalse("Must not contain chat message text", csv.contains("Hello"))
        assertFalse("Must not contain chat message text", csv.contains("message"))
        assertFalse("Must not contain AES key material", csv.contains("AES"))
        assertFalse("Must not contain wrap key alias", csv.contains("p2chat_identity_wrap_key"))
        assertFalse("Must not contain quarantine text", csv.contains("quarantine"))
    }
}
