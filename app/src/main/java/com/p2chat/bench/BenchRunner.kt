package com.p2chat.bench

import com.p2chat.ai.FilterService
import com.p2chat.ble.MeshTransport
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.data.bench.BenchDao
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.BenchSampleEntity
import com.p2chat.pipeline.BenchPacketListener
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import com.p2chat.wire.CoreBlob
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import com.p2chat.wire.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Configuration for a benchmark test burst (T4.9, BENCH-1, BENCH-2).
 */
data class BenchConfig(
    val count: Int = 100,
    val intervalMs: Long = 100L,
    val payloadBytes: Int = 350,
    val initialTtl: Int = 7,
    val isTtlSweep: Boolean = false,
    val isEchoTargetRole: Boolean = false
) {
    init {
        require(count in 1..500) { "Burst count must be between 1 and 500, got $count" }
        require(intervalMs in 50..2000) { "Interval must be between 50 and 2000 ms, got $intervalMs" }
        require(payloadBytes in 32..350) { "Payload size must be between 32 and 350 bytes, got $payloadBytes" }
        require(initialTtl in 1..7) { "Initial TTL must be between 1 and 7, got $initialTtl" }
    }
}

/**
 * Real-time progress and telemetry telemetry emitted during a benchmark run (BENCH-2).
 */
data class BenchProgress(
    val isRunning: Boolean = false,
    val runId: Long = 0L,
    val sentCount: Int = 0,
    val totalCount: Int = 0,
    val ackedCount: Int = 0,
    val rttHalfP50Ms: Float = 0f,
    val rttHalfP95Ms: Float = 0f,
    val pdrPercent: Float = 0f,
    val throughputBps: Float = 0f,
    val inferenceP50Ms: Float = 0f,
    val inferenceP95Ms: Float = 0f,
    val statusMessage: String = "Ready"
)

/**
 * Multi-hop benchmark engine coordinating latency probes, ACK reflections,
 * packet delivery ratios, and TFLite inference profiling (T4.9, BENCH-1..5).
 */
class BenchRunner(
    private val benchDao: BenchDao,
    private val meshTransport: MeshTransport,
    private val identityManager: IdentityManager,
    private val filterService: FilterService?,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : BenchPacketListener {

    companion object {
        private const val TAG = "BenchRunner"

        // Deterministic benchmark group key derived for BleConstants.BENCH_GROUP_ID
        val BENCH_GROUP_KEY: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("P2Chat-v1-Benchmark-Group-PreSharedKey".toByteArray(Charsets.UTF_8))
    }

    private val _progress = MutableStateFlow(BenchProgress())
    val progress: StateFlow<BenchProgress> = _progress.asStateFlow()

    private val runMutex = Mutex()
    private var activeJob: Job? = null

    // In-memory lookup from msgId to active sample for rapid ACK matching
    private val activeSamples = ConcurrentHashMap<Long, BenchSampleEntity>()
    private var activeRunId: Long = 0L
    private var activeConfig: BenchConfig? = null

    var isEchoTargetMode: Boolean = false

    override suspend fun onBenchData(
        msgId: Long,
        initialTtl: Int,
        receivedTtl: Int,
        payload: ByteArray
    ) {
        // Target node receives BENCH_DATA -> immediately reflect compact BENCH_ACK
        try {
            val identity = identityManager.getIdentity() ?: return
            val signingPrivKey = identityManager.unwrapSigningPrivateKey(identity)
            val nowMs = clock.currentTimeMillis()

            val ackPayload = BenchPayloadCodec.encodeAck(
                origMsgId = msgId,
                receivedTtl = receivedTtl,
                initialTtl = initialTtl
            )

            val ackFrame = buildBenchFrame(
                packetType = PacketType.BENCH_ACK,
                msgId = msgId,
                ttl = BleConstants.DEFAULT_TTL,
                senderFp = identity.senderFp,
                sentAtMs = nowMs,
                innerPayload = ackPayload,
                signingPrivKey = signingPrivKey
            )

            meshTransport.flood(ackFrame)
            SecureLog.d(TAG, "Reflected BENCH_ACK for msgId $msgId (received_ttl=$receivedTtl)")
        } catch (e: Throwable) {
            SecureLog.w(TAG, "Failed to reflect BENCH_ACK: ${e.message}")
        }
    }

    override suspend fun onBenchAck(
        msgId: Long,
        receivedTtl: Int,
        ackMonoNs: Long
    ) {
        val sample = activeSamples[msgId] ?: return
        if (sample.delivered) return

        val rttHalfMs = ((ackMonoNs - sample.sentMonoNs).coerceAtLeast(0L).toDouble() / 2_000_000.0).toFloat()
        val hopCount = (sample.initialTtl - receivedTtl).coerceAtLeast(0)

        val updated = sample.copy(
            ackMonoNs = ackMonoNs,
            receivedTtl = receivedTtl,
            hopCount = hopCount,
            rttHalfMs = rttHalfMs,
            delivered = true
        )
        activeSamples[msgId] = updated

        benchDao.recordAck(
            runId = sample.runId,
            msgId = msgId,
            ackMonoNs = ackMonoNs,
            receivedTtl = receivedTtl,
            hopCount = hopCount,
            rttHalfMs = rttHalfMs
        )

        updateLiveStats()
    }

    /**
     * Executes a configured benchmark burst run (BENCH-1, BENCH-2).
     */
    fun startBurst(config: BenchConfig): Job = scope.launch {
        runMutex.withLock {
            activeConfig = config
            isEchoTargetMode = config.isEchoTargetRole

            if (config.isEchoTargetRole) {
                _progress.value = BenchProgress(
                    isRunning = true,
                    statusMessage = "Target Echo Node Active — Listening for probes..."
                )
                return@launch
            }

            val identity = identityManager.getIdentity() ?: return@launch
            val signingPrivKey = identityManager.unwrapSigningPrivateKey(identity)
            val startTimeMs = clock.currentTimeMillis()

            val deviceModel = try {
                "${android.os.Build.MANUFACTURER ?: "Generic"} ${android.os.Build.MODEL ?: "Device"}"
            } catch (e: Throwable) {
                "Generic Device"
            }

            val runEntity = BenchRunEntity(
                startedAtMs = startTimeMs,
                burstCount = config.count,
                intervalMs = config.intervalMs,
                payloadBytes = config.payloadBytes,
                initialTtl = if (config.isTtlSweep) 0 else config.initialTtl,
                connectedPeers = meshTransport.getSinks().size,
                deviceModels = deviceModel
            )
            val runId = benchDao.insertRun(runEntity)
            activeRunId = runId
            activeSamples.clear()

            _progress.value = BenchProgress(
                isRunning = true,
                runId = runId,
                totalCount = config.count,
                statusMessage = "Transmitting probe burst (0/${config.count})..."
            )

            val burstStartMonoNs = System.nanoTime()

            // 1. Transmit probe burst
            for (i in 0 until config.count) {
                val ttl = if (config.isTtlSweep) (i % 7) + 1 else config.initialTtl
                val msgId = ThreadLocalRandom.current().nextLong()
                val nowMs = clock.currentTimeMillis()
                val sentMonoNs = System.nanoTime()

                val dataPayload = BenchPayloadCodec.encodeData(
                    seqNum = i,
                    totalPayloadBytes = config.payloadBytes
                )

                val frameBytes = buildBenchFrame(
                    packetType = PacketType.BENCH_DATA,
                    msgId = msgId,
                    ttl = ttl,
                    senderFp = identity.senderFp,
                    sentAtMs = nowMs,
                    innerPayload = dataPayload,
                    signingPrivKey = signingPrivKey
                )

                val sample = BenchSampleEntity(
                    runId = runId,
                    msgId = msgId,
                    seqNum = i,
                    initialTtl = ttl,
                    sentMonoNs = sentMonoNs
                )
                activeSamples[msgId] = sample
                benchDao.insertSample(sample)

                meshTransport.flood(frameBytes)

                _progress.value = _progress.value.copy(
                    sentCount = i + 1,
                    statusMessage = "Transmitting probe burst (${i + 1}/${config.count})..."
                )

                if (i < config.count - 1) {
                    delay(config.intervalMs)
                }
            }

            // 2. Wait for tail ACKs
            val ackGracePeriodMs = (config.intervalMs * 3).coerceAtLeast(1000L).coerceAtMost(5000L)
            _progress.value = _progress.value.copy(statusMessage = "Collecting return ACKs...")
            delay(ackGracePeriodMs)

            // 3. Profile on-device TFLite inference latency (200 synthetic samples) (BENCH-2)
            _progress.value = _progress.value.copy(statusMessage = "Profiling on-device AI inference...")
            val (infP50, infP95) = runSyntheticAiProfiling()

            // 4. Compute final statistics and persist to Room
            val deliveredSamples = benchDao.getSamplesForRun(runId).filter { it.delivered }
            val sortedRtts = deliveredSamples.map { it.rttHalfMs }.sorted()
            val deliveredCount = deliveredSamples.size
            val pdr = BenchMetricsCalculator.pdrPercent(deliveredCount, config.count)
            val p50 = BenchMetricsCalculator.p50(sortedRtts)
            val p95 = BenchMetricsCalculator.p95(sortedRtts)

            val totalDurationSec = (System.nanoTime() - burstStartMonoNs).toDouble() / 1_000_000_000.0
            val throughput = BenchMetricsCalculator.throughputBps(deliveredCount, config.payloadBytes, totalDurationSec)

            val finishedTimeMs = clock.currentTimeMillis()
            val updatedRun = runEntity.copy(
                runId = runId,
                finishedAtMs = finishedTimeMs,
                pdrPercent = pdr,
                rttHalfP50Ms = p50,
                rttHalfP95Ms = p95,
                throughputBps = throughput,
                inferenceP50Ms = infP50,
                inferenceP95Ms = infP95
            )
            benchDao.updateRun(updatedRun)

            _progress.value = BenchProgress(
                isRunning = false,
                runId = runId,
                sentCount = config.count,
                totalCount = config.count,
                ackedCount = deliveredCount,
                rttHalfP50Ms = p50,
                rttHalfP95Ms = p95,
                pdrPercent = pdr,
                throughputBps = throughput,
                inferenceP50Ms = infP50,
                inferenceP95Ms = infP95,
                statusMessage = "Benchmark run completed. PDR: ${"%.1f".format(pdr)}%, RTT/2 p95: ${"%.1f".format(p95)} ms"
            )
        }
    }

    private suspend fun runSyntheticAiProfiling(): Pair<Float, Float> = withContext(Dispatchers.Default) {
        if (filterService == null) return@withContext Pair(0f, 0f)
        val sampleLatencies = mutableListOf<Float>()
        val testPhrases = listOf(
            "Clear tactical message probe for speed testing",
            "Relaying urgent packet coordinate to node 42",
            "Warning: interference on 2.4GHz physical channel",
            "Normal conversation test across multi-hop line"
        )

        for (i in 0 until 200) {
            val phrase = testPhrases[i % testPhrases.size]
            val diag = filterService.diagnose(phrase)
            sampleLatencies.add(diag.latencyMs.toFloat())
        }

        sampleLatencies.sort()
        val p50 = BenchMetricsCalculator.p50(sampleLatencies)
        val p95 = BenchMetricsCalculator.p95(sampleLatencies)
        Pair(p50, p95)
    }

    private fun updateLiveStats() {
        val config = activeConfig ?: return
        val currentSamples = activeSamples.values.toList()
        val delivered = currentSamples.filter { it.delivered }
        val sortedRtts = delivered.map { it.rttHalfMs }.sorted()

        val pdr = BenchMetricsCalculator.pdrPercent(delivered.size, config.count)
        val p50 = BenchMetricsCalculator.p50(sortedRtts)
        val p95 = BenchMetricsCalculator.p95(sortedRtts)

        _progress.value = _progress.value.copy(
            ackedCount = delivered.size,
            rttHalfP50Ms = p50,
            rttHalfP95Ms = p95,
            pdrPercent = pdr
        )
    }

    private fun buildBenchFrame(
        packetType: PacketType,
        msgId: Long,
        ttl: Int,
        senderFp: Long,
        sentAtMs: Long,
        innerPayload: ByteArray,
        signingPrivKey: PrivateKey
    ): ByteArray {
        // Inner payload: sentAtMs (8B BE) + innerPayload bytes
        val wrappedPayload = ByteBuffer.allocate(8 + innerPayload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(sentAtMs)
            .put(innerPayload)
            .array()

        val header = PacketHeader(
            packetType = packetType,
            ttl = ttl,
            msgId = msgId,
            groupId = BleConstants.BENCH_GROUP_ID,
            senderFp = senderFp,
            keyEpoch = 1
        )

        val aad = header.computeAad()
        val sealed = AesGcm.seal(BENCH_GROUP_KEY, wrappedPayload, aad)

        val coreBlob = CoreBlob(sealed.nonce, sealed.tag, sealed.ciphertext)
        val signedData = aad + coreBlob.encode()
        val signature = Ecdsa.sign(signingPrivKey, signedData)

        val wireFrame = WireFrame(header, coreBlob, signature)
        return WireCodec.encode(wireFrame)
    }
}
