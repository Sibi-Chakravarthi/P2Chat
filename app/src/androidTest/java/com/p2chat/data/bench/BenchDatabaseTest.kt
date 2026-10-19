package com.p2chat.data.bench

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var benchDao: BenchDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.createInMemory(context)
        benchDao = db.benchDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun test100SampleRun_records96AcksAndComputesExactMetrics() = runBlocking {
        // 1. Insert Run
        val run = BenchRunEntity(
            runId = 1L,
            startedAtMs = 1_000_000L,
            burstCount = 100,
            intervalMs = 100L,
            payloadBytes = 350,
            initialTtl = 7,
            connectedPeers = 2,
            deviceModels = "Device A (Nothing A065); Device B"
        )
        val runId = benchDao.insertRun(run)
        assertEquals(1L, runId)

        // 2. Insert 100 samples across TTL 1..7
        val samples = (1..100).map { seq ->
            val ttl = ((seq - 1) % 7) + 1
            BenchSampleEntity(
                runId = runId,
                msgId = 10000L + seq,
                seqNum = seq,
                initialTtl = ttl,
                sentMonoNs = 10_000_000L * seq
            )
        }
        benchDao.insertSamples(samples)
        assertEquals(100, benchDao.getTotalSampleCount(runId))
        assertEquals(0, benchDao.getDeliveredCount(runId))

        // 3. Record 96 ACKs (samples 1..96 delivered, 97..100 lost)
        for (seq in 1..96) {
            val s = samples[seq - 1]
            val hops = if (s.initialTtl > 1) 2 else 1
            val receivedTtl = s.initialTtl - hops
            val rttHalfMs = 20.0f + (seq * 0.5f) // deterministic RTTs
            val ackMonoNs = s.sentMonoNs + (rttHalfMs * 2_000_000L).toLong()

            val rows = benchDao.recordAck(
                runId = runId,
                msgId = s.msgId,
                ackMonoNs = ackMonoNs,
                receivedTtl = receivedTtl,
                hopCount = s.initialTtl - receivedTtl,
                rttHalfMs = rttHalfMs
            )
            assertEquals(1, rows)
        }

        // 4. Verify DAO aggregation queries
        val total = benchDao.getTotalSampleCount(runId)
        val delivered = benchDao.getDeliveredCount(runId)
        assertEquals(100, total)
        assertEquals(96, delivered)

        val pdrPercent = (delivered.toFloat() / total.toFloat()) * 100f
        assertEquals("PDR must be exactly 96.0%", 96.0f, pdrPercent, 0.001f)

        val rtts = benchDao.getDeliveredRttsSorted(runId)
        assertEquals(96, rtts.size)
        // Verify sorted order
        for (i in 0 until rtts.size - 1) {
            assertTrue(rtts[i] <= rtts[i + 1])
        }

        val p50 = rtts[(rtts.size * 0.50).toInt()]
        val p95 = rtts[(rtts.size * 0.95).toInt()]
        assertTrue("p50 RTT must be non-null and positive", p50 > 0f)
        assertTrue("p95 RTT must be non-null and positive", p95 >= p50)

        // Verify hop counts: initial_ttl - received_ttl
        val updatedSamples = benchDao.getSamplesForRun(runId)
        for (s in updatedSamples) {
            if (s.delivered) {
                assertEquals(s.initialTtl - s.receivedTtl, s.hopCount)
                assertTrue(s.ackMonoNs > s.sentMonoNs)
                assertTrue(s.rttHalfMs > 0f)
            } else {
                assertFalse(s.delivered)
                assertEquals(0L, s.ackMonoNs)
            }
        }

        // Verify TTL PDR breakdown
        val ttlStats = benchDao.getTtlPdrBreakdown(runId)
        assertEquals(7, ttlStats.size)
        val totalAcrossTtls = ttlStats.sumOf { it.total }
        val deliveredAcrossTtls = ttlStats.sumOf { it.delivered }
        assertEquals(100, totalAcrossTtls)
        assertEquals(96, deliveredAcrossTtls)

        // Verify Run with Samples
        val runWithSamples = benchDao.getRunWithSamples(runId)
        assertNotNull(runWithSamples)
        assertEquals(100, runWithSamples!!.samples.size)

        // Verify cascade deletion
        val deleted = benchDao.deleteRun(runId)
        assertEquals(1, deleted)
        assertEquals(0, benchDao.getTotalSampleCount(runId))
    }
}
