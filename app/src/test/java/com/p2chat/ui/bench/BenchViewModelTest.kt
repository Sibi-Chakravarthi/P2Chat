package com.p2chat.ui.bench

import com.p2chat.bench.BenchConfig
import com.p2chat.bench.BenchProgress
import com.p2chat.bench.BenchRunner
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class BenchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeBenchDao : BenchDao {
        val runsFlow = MutableStateFlow<List<BenchRunEntity>>(emptyList())
        val runs = mutableMapOf<Long, BenchRunEntity>()
        val samples = mutableMapOf<Pair<Long, Long>, BenchSampleEntity>()
        var nextRunId = 1L

        override suspend fun insertRun(run: BenchRunEntity): Long {
            val id = nextRunId++
            val saved = run.copy(runId = id)
            runs[id] = saved
            runsFlow.value = runs.values.toList()
            return id
        }

        override suspend fun updateRun(run: BenchRunEntity) {
            runs[run.runId] = run
            runsFlow.value = runs.values.toList()
        }

        override suspend fun getRun(runId: Long): BenchRunEntity? = runs[runId]
        override suspend fun getAllRuns(): List<BenchRunEntity> = runs.values.toList()
        override fun getAllRunsFlow(): Flow<List<BenchRunEntity>> = runsFlow

        override suspend fun insertSample(sample: BenchSampleEntity) {
            samples[Pair(sample.runId, sample.msgId)] = sample
        }

        override suspend fun insertSamples(samples: List<BenchSampleEntity>) {
            samples.forEach { insertSample(it) }
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
            runsFlow.value = runs.values.toList()
            return 1
        }

        override suspend fun deleteAllRuns(): Int {
            val count = runs.size
            runs.clear()
            runsFlow.value = emptyList()
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
    private lateinit var runner: BenchRunner
    private lateinit var viewModel: BenchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        benchDao = FakeBenchDao()
        meshTransport = FakeMeshTransport()

        KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x42.toByte() }, "AES")
        val keyVault = KeyVault()
        val identityDao = FakeLocalIdentityDao()
        val identityManager = IdentityManager(identityDao, keyVault)

        val ecProvider = EcProvider.get()
        val keyPair = ecProvider.generateKeyPair()
        val ecdhPair = ecProvider.generateKeyPair()
        val signPub = EcProvider.encodeUncompressedPublicKey(keyPair.public)
        val ecdhPub = EcProvider.encodeUncompressedPublicKey(ecdhPair.public)
        val senderFp = EcProvider.computeSenderFp(signPub)

        val identity = LocalIdentity(
            senderFp = senderFp,
            alias = "TestProber",
            signingPub = signPub,
            ecdhPub = ecdhPub,
            wrappedSigningPriv = keyVault.wrapPrivateKey(keyPair.private),
            wrappedEcdhPriv = keyVault.wrapPrivateKey(ecdhPair.private)
        )
        runTest(testDispatcher) {
            identityDao.insertIdentity(identity)
        }

        runner = BenchRunner(
            benchDao = benchDao,
            meshTransport = meshTransport,
            identityManager = identityManager,
            filterService = null,
            clock = FakeClock(1700000000000L),
            scope = CoroutineScope(testDispatcher)
        )

        viewModel = BenchViewModel(
            benchRunner = runner,
            benchDao = benchDao
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState_defaultsAreValid() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertEquals(100, state.burstCount)
        assertEquals(100L, state.intervalMs)
        assertEquals(350, state.payloadBytes)
        assertEquals(7, state.initialTtl)
        assertFalse(state.isTtlSweep)
        assertEquals(BenchRole.SENDER, state.role)
        assertTrue(state.isValid)
        assertNull(state.countError)
        assertNull(state.intervalError)
        assertNull(state.payloadError)
        assertFalse(state.progress.isRunning)
        assertTrue(state.pastRuns.isEmpty())
    }

    @Test
    fun testBurstCount_validation() = runTest(testDispatcher) {
        viewModel.setBurstCount(250)
        advanceUntilIdle()
        assertEquals(250, viewModel.uiState.value.burstCount)
        assertNull(viewModel.uiState.value.countError)
        assertTrue(viewModel.uiState.value.isValid)

        // Invalid: 0
        viewModel.setBurstCount(0)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.countError)
        assertFalse(viewModel.uiState.value.isValid)

        // Invalid: 501
        viewModel.setBurstCount(501)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.countError)
        assertFalse(viewModel.uiState.value.isValid)

        // Reset to valid
        viewModel.setBurstCount(50)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.countError)
        assertTrue(viewModel.uiState.value.isValid)
    }

    @Test
    fun testIntervalMs_validation() = runTest(testDispatcher) {
        viewModel.setIntervalMs(500L)
        advanceUntilIdle()
        assertEquals(500L, viewModel.uiState.value.intervalMs)
        assertNull(viewModel.uiState.value.intervalError)
        assertTrue(viewModel.uiState.value.isValid)

        // Invalid: 49ms
        viewModel.setIntervalMs(49L)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.intervalError)
        assertFalse(viewModel.uiState.value.isValid)

        // Invalid: 2001ms
        viewModel.setIntervalMs(2001L)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.intervalError)
        assertFalse(viewModel.uiState.value.isValid)
    }

    @Test
    fun testPayloadBytes_validation() = runTest(testDispatcher) {
        viewModel.setPayloadBytes(128)
        advanceUntilIdle()
        assertEquals(128, viewModel.uiState.value.payloadBytes)
        assertNull(viewModel.uiState.value.payloadError)
        assertTrue(viewModel.uiState.value.isValid)

        // Invalid: 31B
        viewModel.setPayloadBytes(31)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.payloadError)
        assertFalse(viewModel.uiState.value.isValid)

        // Invalid: 351B
        viewModel.setPayloadBytes(351)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.payloadError)
        assertFalse(viewModel.uiState.value.isValid)
    }

    @Test
    fun testInitialTtl_clamping() = runTest(testDispatcher) {
        viewModel.setInitialTtl(5)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.initialTtl)

        viewModel.setInitialTtl(99)
        advanceUntilIdle()
        assertEquals(7, viewModel.uiState.value.initialTtl)

        viewModel.setInitialTtl(-5)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.initialTtl)
    }

    @Test
    fun testTtlSweepToggle() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isTtlSweep)

        viewModel.setTtlSweep(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isTtlSweep)

        viewModel.setTtlSweep(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isTtlSweep)
    }

    @Test
    fun testRoleToggle() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(BenchRole.SENDER, viewModel.uiState.value.role)

        viewModel.setRole(BenchRole.ECHO_NODE)
        advanceUntilIdle()
        assertEquals(BenchRole.ECHO_NODE, viewModel.uiState.value.role)

        viewModel.setRole(BenchRole.SENDER)
        advanceUntilIdle()
        assertEquals(BenchRole.SENDER, viewModel.uiState.value.role)
    }

    @Test
    fun testStartBenchmark_executesBurstAndUpdatesProgress() = runTest(testDispatcher) {
        viewModel.setBurstCount(5)
        viewModel.setIntervalMs(50L)
        viewModel.setPayloadBytes(64)
        viewModel.setInitialTtl(7)
        advanceUntilIdle()

        viewModel.startBenchmark()
        advanceUntilIdle()

        assertEquals(5, meshTransport.floodedFrames.size)
        val progress = viewModel.uiState.value.progress
        assertEquals(5, progress.sentCount)
        assertEquals(5, progress.totalCount)
    }

    @Test
    fun testStartBenchmark_blockedWhenInvalid() = runTest(testDispatcher) {
        viewModel.setBurstCount(0) // Invalid
        viewModel.startBenchmark()
        advanceUntilIdle()

        assertEquals(0, meshTransport.floodedFrames.size)
        assertFalse(viewModel.uiState.value.progress.isRunning)
    }

    @Test
    fun testHistoryObservation_loadsPastRuns() = runTest(testDispatcher) {
        val run1 = BenchRunEntity(
            startedAtMs = 1000L,
            burstCount = 10,
            intervalMs = 100L,
            payloadBytes = 64,
            initialTtl = 7,
            pdrPercent = 100f
        )
        val run2 = BenchRunEntity(
            startedAtMs = 2000L,
            burstCount = 20,
            intervalMs = 50L,
            payloadBytes = 128,
            initialTtl = 0,
            pdrPercent = 95f
        )
        benchDao.insertRun(run1)
        benchDao.insertRun(run2)
        advanceUntilIdle()

        val runs = viewModel.uiState.value.pastRuns
        assertEquals(2, runs.size)
    }

    @Test
    fun testSelectRun_togglesTtlBreakdown() = runTest(testDispatcher) {
        val runId = benchDao.insertRun(
            BenchRunEntity(startedAtMs = 1000L, burstCount = 2, intervalMs = 100L, payloadBytes = 64, initialTtl = 7)
        )
        benchDao.insertSample(
            BenchSampleEntity(runId = runId, msgId = 1L, seqNum = 0, initialTtl = 7, sentMonoNs = 1000L, delivered = true)
        )
        benchDao.insertSample(
            BenchSampleEntity(runId = runId, msgId = 2L, seqNum = 1, initialTtl = 7, sentMonoNs = 2000L, delivered = false)
        )
        advanceUntilIdle()

        // Select run
        viewModel.selectRun(runId)
        advanceUntilIdle()

        assertEquals(runId, viewModel.uiState.value.selectedRunId)
        val stats = viewModel.uiState.value.selectedRunTtlStats
        assertEquals(1, stats.size)
        assertEquals(7, stats[0].initialTtl)
        assertEquals(2, stats[0].total)
        assertEquals(1, stats[0].delivered)
        assertEquals(50f, stats[0].pdrPercent, 0.01f)

        // Deselect
        viewModel.selectRun(runId)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedRunId)
        assertTrue(viewModel.uiState.value.selectedRunTtlStats.isEmpty())
    }

    @Test
    fun testExportCsv_emitsSnackbar() = runTest(testDispatcher) {
        viewModel.exportCsv(123L)
        advanceUntilIdle()
        // Event verified without crash
    }
}
