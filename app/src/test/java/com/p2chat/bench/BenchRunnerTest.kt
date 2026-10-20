package com.p2chat.bench

import com.p2chat.ai.FilterDiagnosis
import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterVerdict
import com.p2chat.ble.MeshTransport
import com.p2chat.ble.PeerSink
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.bench.BenchDao
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.BenchRunWithSamples
import com.p2chat.data.bench.BenchSampleEntity
import com.p2chat.data.bench.TtlPdrStats
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class BenchRunnerTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeBenchDao : BenchDao {
        val runs = mutableMapOf<Long, BenchRunEntity>()
        val samples = mutableMapOf<Pair<Long, Long>, BenchSampleEntity>()
        var nextRunId = 1L

        override suspend fun insertRun(run: BenchRunEntity): Long {
            val id = nextRunId++
            runs[id] = run.copy(runId = id)
            return id
        }

        override suspend fun updateRun(run: BenchRunEntity) {
            runs[run.runId] = run
        }

        override suspend fun getRun(runId: Long): BenchRunEntity? = runs[runId]
        override suspend fun getAllRuns(): List<BenchRunEntity> = runs.values.toList()
        override fun getAllRunsFlow(): Flow<List<BenchRunEntity>> = flowOf(runs.values.toList())

        override suspend fun insertSample(sample: BenchSampleEntity) {
            samples[Pair(sample.runId, sample.msgId)] = sample
        }

        override suspend fun insertSamples(sampleList: List<BenchSampleEntity>) {
            sampleList.forEach { samples[Pair(it.runId, it.msgId)] = it }
        }

        override suspend fun getSamplesForRun(runId: Long): List<BenchSampleEntity> =
            samples.values.filter { it.runId == runId }

        override suspend fun recordAck(
            runId: Long,
            msgId: Long,
            ackMonoNs: Long,
            receivedTtl: Int,
            hopCount: Int,
            rttHalfMs: Float
        ): Int {
            val key = Pair(runId, msgId)
            val s = samples[key] ?: return 0
            samples[key] = s.copy(
                ackMonoNs = ackMonoNs,
                receivedTtl = receivedTtl,
                hopCount = hopCount,
                rttHalfMs = rttHalfMs,
                delivered = true
            )
            return 1
        }

        override suspend fun getTotalSampleCount(runId: Long): Int =
            samples.values.count { it.runId == runId }

        override suspend fun getDeliveredCount(runId: Long): Int =
            samples.values.count { it.runId == runId && it.delivered }

        override suspend fun getDeliveredRttsSorted(runId: Long): List<Float> =
            samples.values.filter { it.runId == runId && it.delivered }.map { it.rttHalfMs }.sorted()

        override suspend fun getTtlPdrBreakdown(runId: Long): List<TtlPdrStats> {
            return samples.values
                .filter { it.runId == runId }
                .groupBy { it.initialTtl }
                .map { (ttl, list) ->
                    TtlPdrStats(
                        initialTtl = ttl,
                        total = list.size,
                        delivered = list.count { it.delivered }
                    )
                }
                .sortedBy { it.initialTtl }
        }

        override suspend fun getRunWithSamples(runId: Long): BenchRunWithSamples? {
            val run = runs[runId] ?: return null
            val sampleList = samples.values.filter { it.runId == runId }.sortedBy { it.seqNum }
            return BenchRunWithSamples(run, sampleList)
        }

        override suspend fun deleteRun(runId: Long): Int {
            runs.remove(runId)
            samples.keys.filter { it.first == runId }.forEach { samples.remove(it) }
            return 1
        }

        override suspend fun deleteAllRuns(): Int {
            val count = runs.size
            runs.clear()
            samples.clear()
            return count
        }
    }

    private class FakeMeshTransport : MeshTransport {
        val floodedFrames = CopyOnWriteArrayList<ByteArray>()
        override suspend fun flood(frameBytes: ByteArray, excludePeerId: String?): Int {
            floodedFrames.add(frameBytes)
            return 1
        }
        override fun registerSink(sink: PeerSink) {}
        override fun unregisterSink(sink: PeerSink) {}
        override fun getSinks(): List<PeerSink> = emptyList()
    }

    private class FakeLocalIdentityDao(private var identity: LocalIdentity? = null) : LocalIdentityDao {
        override suspend fun getIdentity(): LocalIdentity? = identity
        override fun observeIdentity(): Flow<LocalIdentity?> = flowOf(identity)
        override suspend fun insertIdentity(identity: LocalIdentity): Long {
            this.identity = identity
            return identity.senderFp
        }
        override suspend fun updateAlias(senderFp: Long, newAlias: String): Int = 1
        override suspend fun hasIdentity(): Boolean = identity != null
        override suspend fun clearIdentity(): Int {
            identity = null
            return 1
        }
    }

    private lateinit var benchDao: FakeBenchDao
    private lateinit var meshTransport: FakeMeshTransport
    private lateinit var identityManager: IdentityManager
    private lateinit var keyVault: KeyVault
    private lateinit var clock: FakeClock
    private lateinit var keyPair: KeyPair
    private lateinit var runner: BenchRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        benchDao = FakeBenchDao()
        meshTransport = FakeMeshTransport()
        clock = FakeClock(1700000000000L)

        KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x42.toByte() }, "AES")
        keyVault = KeyVault()
        val identityDao = FakeLocalIdentityDao()
        identityManager = IdentityManager(identityDao, keyVault)

        val ecProvider = EcProvider.get()
        keyPair = ecProvider.generateKeyPair()
        val ecdhPair = ecProvider.generateKeyPair()
        val uncompressedSign = EcProvider.encodeUncompressedPublicKey(keyPair.public)
        val uncompressedEcdh = EcProvider.encodeUncompressedPublicKey(ecdhPair.public)
        val senderFp = EcProvider.computeSenderFp(uncompressedSign)

        val localIdentity = LocalIdentity(
            senderFp = senderFp,
            alias = "BenchNode_01",
            signingPub = uncompressedSign,
            ecdhPub = uncompressedEcdh,
            wrappedSigningPriv = keyVault.wrapPrivateKey(keyPair.private),
            wrappedEcdhPriv = keyVault.wrapPrivateKey(ecdhPair.private)
        )
        runTest(testDispatcher) {
            identityDao.insertIdentity(localIdentity)
        }

        runner = BenchRunner(
            benchDao = benchDao,
            meshTransport = meshTransport,
            identityManager = identityManager,
            filterService = null,
            clock = clock,
            scope = CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testBenchPayloadCodec_roundtrip() {
        // Test DATA payload
        val dataEncoded = BenchPayloadCodec.encodeData(seqNum = 42, totalPayloadBytes = 350)
        assertEquals(350, dataEncoded.size)
        val dataDecoded = BenchPayloadCodec.decodeData(dataEncoded)
        assertEquals(42, dataDecoded.seqNum)
        assertEquals(346, dataDecoded.marker.size)

        // Test ACK payload
        val ackEncoded = BenchPayloadCodec.encodeAck(origMsgId = 0x123456789ABCDEFL, receivedTtl = 5, initialTtl = 7)
        assertEquals(10, ackEncoded.size)
        val ackDecoded = BenchPayloadCodec.decodeAck(ackEncoded)
        assertEquals(0x123456789ABCDEFL, ackDecoded.origMsgId)
        assertEquals(5, ackDecoded.receivedTtl)
        assertEquals(7, ackDecoded.initialTtl)
    }

    @Test
    fun testBenchMetricsCalculator_calculations() {
        assertEquals(100f, BenchMetricsCalculator.pdrPercent(10, 10), 0.01f)
        assertEquals(50f, BenchMetricsCalculator.pdrPercent(5, 10), 0.01f)
        assertEquals(0f, BenchMetricsCalculator.pdrPercent(0, 10), 0.01f)

        val rtts = listOf(10f, 20f, 30f, 40f, 50f)
        assertEquals(30f, BenchMetricsCalculator.p50(rtts), 0.01f)
        assertEquals(50f, BenchMetricsCalculator.p95(rtts), 0.01f)

        val throughput = BenchMetricsCalculator.throughputBps(deliveredCount = 10, payloadBytes = 350, durationSeconds = 1.0)
        assertEquals(3500f, throughput, 0.01f)

        val samples = listOf(
            BenchSampleEntity(runId = 1L, msgId = 1L, seqNum = 0, initialTtl = 7, hopCount = 2, sentMonoNs = 1000L, delivered = true),
            BenchSampleEntity(runId = 1L, msgId = 2L, seqNum = 1, initialTtl = 7, hopCount = 2, sentMonoNs = 2000L, delivered = true),
            BenchSampleEntity(runId = 1L, msgId = 3L, seqNum = 2, initialTtl = 7, hopCount = 1, sentMonoNs = 3000L, delivered = true),
            BenchSampleEntity(runId = 1L, msgId = 4L, seqNum = 3, initialTtl = 7, hopCount = 3, sentMonoNs = 4000L, delivered = false)
        )
        val dist = BenchMetricsCalculator.hopDistribution(samples)
        assertEquals(2, dist[2])
        assertEquals(1, dist[1])
        assertEquals(null, dist[3]) // undelivered sample not counted
    }

    @Test
    fun testOnBenchData_reflectsAck() = runTest(testDispatcher) {
        val testMsgId = 0x99887766L
        val dataPayload = BenchPayloadCodec.encodeData(1, 64)

        runner.onBenchData(
            msgId = testMsgId,
            initialTtl = 7,
            receivedTtl = 5,
            payload = dataPayload
        )

        assertEquals(1, meshTransport.floodedFrames.size)
        val ackFrameBytes = meshTransport.floodedFrames.first()
        val decodedWire = WireCodec.decode(ackFrameBytes).getOrThrow()

        assertEquals(PacketType.BENCH_ACK, decodedWire.header.packetType)
        assertEquals(BleConstants.DEFAULT_TTL, decodedWire.header.ttl)
        assertEquals(BleConstants.BENCH_GROUP_ID, decodedWire.header.groupId)
        assertEquals(testMsgId, decodedWire.header.msgId)
    }

    @Test
    fun testOnBenchAck_updatesSampleTelemetry() = runTest(testDispatcher) {
        val config = BenchConfig(count = 1, intervalMs = 50, payloadBytes = 64, initialTtl = 7)
        runner.startBurst(config)
        advanceUntilIdle()

        assertEquals(1, meshTransport.floodedFrames.size)
        val sentFrame = WireCodec.decode(meshTransport.floodedFrames.first()).getOrThrow()
        val msgId = sentFrame.header.msgId

        val sampleBefore = benchDao.samples.values.first { it.msgId == msgId }
        val sentNano = sampleBefore.sentMonoNs

        // Reflection arrives at sentNano + 20_000_000 ns (20 ms round-trip -> 10 ms RTT/2)
        val ackNano = sentNano + 20_000_000L
        runner.onBenchAck(msgId = msgId, receivedTtl = 5, ackMonoNs = ackNano)
        advanceUntilIdle()

        val updated = benchDao.samples.values.first { it.msgId == msgId }
        assertNotNull(updated)
        assertTrue(updated.delivered)
        assertEquals(2, updated.hopCount) // 7 - 5 = 2 hops
        assertEquals(10f, updated.rttHalfMs, 0.1f)
    }

    @Test
    fun testLoopbackSimulation_twoNodesEndToEnd() = runTest(testDispatcher) {
        // Node A = sender (runner), Node B = target echo node
        val nodeBTransport = FakeMeshTransport()
        val nodeBIdentityDao = FakeLocalIdentityDao()
        val nodeBIdentityManager = IdentityManager(nodeBIdentityDao, keyVault)

        val ecProvider = EcProvider.get()
        val bKeyPair = ecProvider.generateKeyPair()
        val bEcdhPair = ecProvider.generateKeyPair()
        val bSignPub = EcProvider.encodeUncompressedPublicKey(bKeyPair.public)
        val bEcdhPub = EcProvider.encodeUncompressedPublicKey(bEcdhPair.public)
        val bFp = EcProvider.computeSenderFp(bSignPub)

        val bIdentity = LocalIdentity(
            senderFp = bFp,
            alias = "NodeB_Echo",
            signingPub = bSignPub,
            ecdhPub = bEcdhPub,
            wrappedSigningPriv = keyVault.wrapPrivateKey(bKeyPair.private),
            wrappedEcdhPriv = keyVault.wrapPrivateKey(bEcdhPair.private)
        )
        nodeBIdentityDao.insertIdentity(bIdentity)

        val nodeBRunner = BenchRunner(
            benchDao = FakeBenchDao(),
            meshTransport = nodeBTransport,
            identityManager = nodeBIdentityManager,
            filterService = null,
            clock = clock,
            scope = CoroutineScope(testDispatcher)
        )

        val count = 5
        val config = BenchConfig(count = count, intervalMs = 50, payloadBytes = 64, initialTtl = 7)
        runner.startBurst(config)
        advanceUntilIdle()

        assertEquals(count, meshTransport.floodedFrames.size)

        // For each frame flooded by Node A, Node B handles onBenchData (reflects BENCH_ACK)
        // Simulated 1-hop delivery: receivedTtl = 6
        for (frameBytes in meshTransport.floodedFrames) {
            val decoded = WireCodec.decode(frameBytes).getOrThrow()
            nodeBRunner.onBenchData(
                msgId = decoded.header.msgId,
                initialTtl = decoded.header.ttl,
                receivedTtl = 6,
                payload = frameBytes
            )
        }

        assertEquals(count, nodeBTransport.floodedFrames.size)

        // Now Node A receives each reflected BENCH_ACK
        for (ackFrame in nodeBTransport.floodedFrames) {
            val decodedAck = WireCodec.decode(ackFrame).getOrThrow()
            val sample = benchDao.samples.values.first { it.msgId == decodedAck.header.msgId }
            val ackTime = sample.sentMonoNs + 10_000_000L // 10 ms RTT -> 5 ms RTT/2
            runner.onBenchAck(
                msgId = decodedAck.header.msgId,
                receivedTtl = decodedAck.header.ttl - 1, // 6
                ackMonoNs = ackTime
            )
        }
        advanceUntilIdle()

        val run = benchDao.runs.values.first()
        assertEquals(count, run.burstCount)
        assertEquals(100f, run.pdrPercent, 0.01f)
        assertEquals(5f, run.rttHalfP50Ms, 0.1f)
        assertEquals(5f, run.rttHalfP95Ms, 0.1f)
        assertTrue("Throughput should be > 0 bytes/s", run.throughputBps > 0f)
    }

    @Test
    fun testSweepTtl_cyclesOneToSeven() = runTest(testDispatcher) {
        val config = BenchConfig(count = 14, intervalMs = 50, payloadBytes = 64, initialTtl = 7, isTtlSweep = true)
        runner.startBurst(config)
        advanceUntilIdle()

        assertEquals(14, meshTransport.floodedFrames.size)
        val ttls = meshTransport.floodedFrames.map { frame ->
            WireCodec.decode(frame).getOrThrow().header.ttl
        }
        val expected = listOf(1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7)
        assertEquals(expected, ttls)
    }

    @Test
    fun testTargetEchoNodeRole_listensWithoutSending() = runTest(testDispatcher) {
        val config = BenchConfig(count = 10, intervalMs = 50, payloadBytes = 64, isEchoTargetRole = true)
        runner.startBurst(config)
        advanceUntilIdle()

        // Target echo role should not flood probe bursts
        assertEquals(0, meshTransport.floodedFrames.size)
        assertTrue(runner.isEchoTargetMode)
        assertTrue(runner.progress.value.statusMessage.contains("Target Echo Node Active"))
    }
}
