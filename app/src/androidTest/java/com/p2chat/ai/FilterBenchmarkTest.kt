package com.p2chat.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.ai.model.TfliteEmbedder
import com.p2chat.ai.score.NearestSource
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.db.AppDatabase
import com.p2chat.util.SecureLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device benchmark and quality bar verification suite (T3.12, AI-4, AI-12, AI-14, §8 Quality Bar).
 *
 * Requirements:
 * - p95 inference + scoring latency < 35 ms (Quality Bar §8).
 * - 1,000-vector dot-product scan < 2 ms (Quality Bar §8).
 * - Total tracked AI assets < 30 MB (AI-14).
 */
@RunWith(AndroidJUnit4::class)
class FilterBenchmarkTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var filterService: FilterService
    private lateinit var scorer: Scorer
    private lateinit var store: VectorStore

    private val benchmarkSentences = listOf(
        "Are we still meeting at the library tomorrow afternoon?",
        "Watch out, police are setting up barricades near the train station!",
        "Can someone send the link to the document?",
        "You are an absolute idiot and nobody likes you.",
        "Medical supplies and clean water are available at the south tent.",
        "Stop talking before someone comes and punches you.",
        "The weather forecast says clear skies all weekend.",
        "Tear gas deployed at the square, everyone disperse calmly!",
        "I will destroy everything you care about you worthless trash.",
        "Don't forget to charge your device before heading out.",
        "Why is the mesh network responding so quickly today?",
        "Get out of here before I beat you up.",
        "Volunteers needed for food packaging at community center.",
        "Let's test the 3-device BLE GATT epidemic routing.",
        "Emergency exit on 4th street is blocked, use alternative route."
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.createInMemory(context)

        val t0 = System.nanoTime()
        val embedder = TfliteEmbedder.fromAsset(context)
        val coldLoadMs = (System.nanoTime() - t0) / 1_000_000L
        SecureLog.d("FilterBenchmark", "Cold model load time: $coldLoadMs ms")

        val tokenizer = context.assets.open("vocab.txt").use { stream ->
            WordPieceTokenizer.fromInputStream(stream)
        }

        store = VectorStore.fromAssets(context)
        scorer = Scorer(store, db.blockedVectorDao())

        filterService = FilterService(
            embedder = embedder,
            scorer = scorer,
            tokenizer = tokenizer,
            settingDao = db.settingDao()
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testLatencyBenchmarkOver300Messages() = runBlocking {
        // 1. 20 warm-up runs
        for (i in 0 until 20) {
            val text = benchmarkSentences[i % benchmarkSentences.size]
            filterService.diagnose(text)
        }

        // 2. 300 measured runs (use diagnose to avoid deduplication cache)
        val runs = 300
        val latencies = LongArray(runs)

        for (i in 0 until runs) {
            val text = benchmarkSentences[i % benchmarkSentences.size]
            val diag = filterService.diagnose(text)
            latencies[i] = diag.latencyMs
        }

        latencies.sort()
        val p50 = latencies[(runs * 0.50).toInt()]
        val p95 = latencies[(runs * 0.95).toInt()]
        val avg = latencies.average()

        SecureLog.d("FilterBenchmark", "Latency over $runs messages: p50 = $p50 ms, p95 = $p95 ms, avg = $avg ms")

        // Quality bar check
        assertTrue(
            "p95 inference latency must be under 35 ms (was $p95 ms)",
            p95 < 35
        )
    }

    @Test
    fun testDotProductScanLatencyOver1000Vectors() {
        // Build 1,000 vectors (525 centroids + 475 blocked vectors)
        val blockedList = (1..475).map { idx ->
            val buf = ByteBuffer.allocate(1536).order(ByteOrder.LITTLE_ENDIAN)
            val d = (idx % 384)
            val vec = FloatArray(384) { 0f }.apply { this[d] = 1f }
            for (v in vec) buf.putFloat(v)
            BlockedVectorEntity(
                id = idx.toLong(),
                vector = buf.array(),
                forceQuarantine = false,
                sourceMsgId = idx.toLong(),
                createdAtMs = 1000L + idx
            )
        }
        scorer.updateBlockedVectors(blockedList)

        val query = store.getVector(0)

        // Warm up
        for (i in 0 until 50) {
            scorer.score(query)
        }

        // 1,000 iterations
        val iterations = 1000
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            scorer.score(query)
        }
        val elapsedTotalNs = System.nanoTime() - t0
        val avgMs = (elapsedTotalNs.toDouble() / iterations) / 1_000_000.0

        SecureLog.d("FilterBenchmark", "1,000-vector dot-product scan avg: $avgMs ms")

        assertTrue(
            "1,000-vector dot product scan must average under 2.0 ms (was $avgMs ms)",
            avgMs < 2.0
        )
    }

    @Test
    fun testDiagnoseSamplePhrasesAndAssetBudget() = runBlocking {
        // Check asset sizes
        val tfliteFd = context.assets.openFd("all-MiniLM-L6-v2-quant.tflite")
        val vocabFd = context.assets.openFd("vocab.txt")
        val binFd = context.assets.openFd("toxic_vectors.bin")

        val totalBytes = tfliteFd.declaredLength + vocabFd.declaredLength + binFd.declaredLength
        val maxBudget = 30L * 1024L * 1024L

        SecureLog.d("FilterBenchmark", "Total assets: $totalBytes bytes / $maxBudget bytes")
        assertTrue(
            "Total tracked assets must stay under 30 MB budget (was $totalBytes bytes)",
            totalBytes <= maxBudget
        )

        // Diagnose test phrases
        val samplePhrases = listOf(
            "Water distribution at north gate at 12:00",
            "I'm going to kill you and break your neck",
            "Police using tear gas, move back to 5th avenue!"
        )

        for (phrase in samplePhrases) {
            val diag = filterService.diagnose(phrase)
            assertNotNull(diag)
            assertTrue(diag.topCategories.isNotEmpty())
            SecureLog.d(
                "FilterBenchmark",
                "Phrase: '$phrase' -> Score: ${diag.score}, Top: ${diag.topCategories.map { "${it.category}:${it.score}" }}, Latency: ${diag.latencyMs} ms"
            )
        }
    }
}
