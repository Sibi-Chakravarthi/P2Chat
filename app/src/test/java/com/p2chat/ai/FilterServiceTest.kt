package com.p2chat.ai

import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.CategoryScore
import com.p2chat.ai.score.NearestSource
import com.p2chat.ai.score.ScoreResult
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.ai.BlockedVectorEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class FilterServiceTest {

    private class CountingFakeEmbedder(
        override var isAvailable: Boolean = true,
        var vectorToReturn: FloatArray = FloatArray(384) { 0f }.apply { this[0] = 1f }
    ) : SentenceEmbedder {
        var callCount = 0
        override val lastInferenceLatencyMs: Long = 1L

        override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray {
            callCount++
            return vectorToReturn
        }

        override fun reload() {}
        override fun close() {
            isAvailable = false
        }
    }

    private fun createControlledVectorStore(): VectorStore {
        // 3 centroids with distinct categories: toxic, threat, obscene
        val flat = FloatArray(3 * 384)
        // Centroid 0 (toxic): unit vector along dimension 0
        flat[0] = 1f
        // Centroid 1 (threat): unit vector along dimension 1
        flat[384 + 1] = 1f
        // Centroid 2 (obscene): unit vector along dimension 2
        flat[384 * 2 + 2] = 1f

        return VectorStore(flat, listOf("toxic", "threat", "obscene"))
    }

    private fun createMinimalTokenizer(): WordPieceTokenizer {
        val vocab = mapOf(
            "[PAD]" to 0,
            "[UNK]" to 100,
            "[CLS]" to 101,
            "[SEP]" to 102,
            "hello" to 103,
            "world" to 104
        )
        return WordPieceTokenizer(vocab)
    }

    private fun vectorWithScoreOnDim0(score: Float): FloatArray {
        val vec = FloatArray(384)
        vec[0] = score
        val remaining = sqrt(1.0 - (score * score).toDouble()).toFloat()
        vec[3] = remaining // orthogonal dimension 3
        return vec
    }

    @Test
    fun testTierAssignment_exactThresholdBoundaries() = runBlocking {
        val store = createControlledVectorStore()
        val scorer = Scorer(store)
        val tokenizer = createMinimalTokenizer()
        val fakeEmbedder = CountingFakeEmbedder()

        val service = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = tokenizer,
            initialSettings = FilterSettings(tauWarn = 0.68f, tauBlock = 0.82f)
        )

        // 1. Score = 0.82 -> Tier 1 Quarantine
        fakeEmbedder.vectorToReturn = vectorWithScoreOnDim0(0.82f)
        val verdict82 = service.classify(1L, "hello")
        assertTrue("0.82 must be Tier 1", verdict82 is FilterVerdict.Tier1Quarantine)
        assertEquals(0.82f, verdict82.score, 1e-4f)
        assertTrue(verdict82.isBlurred)

        // 2. Score = 0.68 -> Tier 2 Warning
        fakeEmbedder.vectorToReturn = vectorWithScoreOnDim0(0.68f)
        val verdict68 = service.classify(2L, "hello")
        assertTrue("0.68 must be Tier 2", verdict68 is FilterVerdict.Tier2Warning)
        assertEquals(0.68f, verdict68.score, 1e-4f)
        assertTrue(verdict68.isBlurred)

        // 3. Score = 0.6799 -> Tier 3 Clean
        fakeEmbedder.vectorToReturn = vectorWithScoreOnDim0(0.6799f)
        val verdict6799 = service.classify(3L, "hello")
        assertTrue("0.6799 must be Tier 3", verdict6799 is FilterVerdict.Tier3Clean)
        assertEquals(0.6799f, verdict6799.score, 1e-4f)
        assertFalse(verdict6799.isBlurred)
    }

    @Test
    fun testForcedQuarantineAt080_worksWithFlagOnAndNotWithFlagOff() = runBlocking {
        val store = createControlledVectorStore()
        val scorer = Scorer(store)
        val tokenizer = createMinimalTokenizer()
        val fakeEmbedder = CountingFakeEmbedder()

        val service = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = tokenizer,
            initialSettings = FilterSettings(tauWarn = 0.68f, tauBlock = 0.85f) // block is 0.85
        )

        // Target query along dimension 10 (orthogonal to centroids)
        val query = FloatArray(384) { 0f }.apply { this[10] = 1f }
        fakeEmbedder.vectorToReturn = query

        // Blocked vector matching query with score = 0.80
        val blockedVec = FloatArray(384) { 0f }.apply {
            this[10] = 0.80f
            this[11] = sqrt(1.0 - 0.80 * 0.80).toFloat()
        }
        val buf = ByteBuffer.allocate(1536).order(ByteOrder.LITTLE_ENDIAN)
        for (v in blockedVec) buf.putFloat(v)

        // Case A: forceQuarantine = true -> Forces Tier 1 even though 0.80 < 0.85 tauBlock
        val entityWithFlagOn = BlockedVectorEntity(
            id = 1L,
            vector = buf.array(),
            forceQuarantine = true,
            sourceMsgId = 101L,
            createdAtMs = 1000L
        )
        scorer.updateBlockedVectors(listOf(entityWithFlagOn))

        val verdictFlagOn = service.classify(10L, "test text")
        assertTrue("force_quarantine on must force Tier 1", verdictFlagOn is FilterVerdict.Tier1Quarantine)
        val t1On = verdictFlagOn as FilterVerdict.Tier1Quarantine
        assertTrue("forcedByBlocklist flag must be true", t1On.forcedByBlocklist)

        // Case B: forceQuarantine = false -> Normal threshold applies: 0.80 is Tier 2 (between 0.68 and 0.85)
        val entityWithFlagOff = BlockedVectorEntity(
            id = 2L,
            vector = buf.array(),
            forceQuarantine = false,
            sourceMsgId = 102L,
            createdAtMs = 1000L
        )
        scorer.updateBlockedVectors(listOf(entityWithFlagOff))

        val verdictFlagOff = service.classify(11L, "test text")
        assertTrue("force_quarantine off at 0.80 with tauBlock=0.85 must be Tier 2", verdictFlagOff is FilterVerdict.Tier2Warning)
        assertEquals(0.80f, verdictFlagOff.score, 1e-4f)
    }

    @Test
    fun testInvalidThresholdPairsAreRejected() {
        // tauWarn <= 0
        try {
            FilterSettings.validate(0.0f, 0.82f)
            fail("Expected IllegalArgumentException for tauWarn = 0.0")
        } catch (_: IllegalArgumentException) {}

        // tauBlock > 1.0
        try {
            FilterSettings.validate(0.68f, 1.01f)
            fail("Expected IllegalArgumentException for tauBlock > 1.0")
        } catch (_: IllegalArgumentException) {}

        // tauWarn >= tauBlock
        try {
            FilterSettings.validate(0.85f, 0.82f)
            fail("Expected IllegalArgumentException for tauWarn >= tauBlock")
        } catch (_: IllegalArgumentException) {}

        // Gap < 0.01
        try {
            FilterSettings.validate(0.80f, 0.805f)
            fail("Expected IllegalArgumentException for gap < 0.01")
        } catch (_: IllegalArgumentException) {}

        // Exactly 0.01 gap is valid
        FilterSettings.validate(0.80f, 0.81f)
    }

    @Test
    fun testSecondCallForSameMsgIdDoesNotCallEmbedder() = runBlocking {
        val store = createControlledVectorStore()
        val scorer = Scorer(store)
        val tokenizer = createMinimalTokenizer()
        val fakeEmbedder = CountingFakeEmbedder()

        val service = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = tokenizer
        )

        assertEquals(0, fakeEmbedder.callCount)

        val v1 = service.classify(42L, "hello world")
        assertEquals(1, fakeEmbedder.callCount)

        // Second call with same msgId must return cached verdict without calling embedder
        val v2 = service.classify(42L, "hello world")
        assertEquals(1, fakeEmbedder.callCount)
        assertEquals(v1, v2)

        // Different msgId calls embedder
        service.classify(43L, "hello world")
        assertEquals(2, fakeEmbedder.callCount)
    }

    @Test
    fun testMissingVocabOrUnavailableEmbedderYieldsNotScanned() = runBlocking {
        val store = createControlledVectorStore()
        val scorer = Scorer(store)
        val fakeEmbedder = CountingFakeEmbedder(isAvailable = false)

        // Missing tokenizer (null)
        val serviceNoTok = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = null
        )
        val verdict1 = serviceNoTok.classify(1L, "hello")
        assertTrue(verdict1 is FilterVerdict.NotScanned)
        assertTrue(verdict1.isBlurred)

        // Unavailable embedder
        val tokenizer = createMinimalTokenizer()
        val serviceNoEmbedder = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = tokenizer
        )
        val verdict2 = serviceNoEmbedder.classify(2L, "hello")
        assertTrue(verdict2 is FilterVerdict.NotScanned)
        assertTrue(verdict2.isBlurred)
    }

    @Test
    fun testDiagnoseReturnsThreeCategoriesAndDoesNotCache() = runBlocking {
        val store = createControlledVectorStore()
        val scorer = Scorer(store)
        val tokenizer = createMinimalTokenizer()
        val fakeEmbedder = CountingFakeEmbedder()

        val service = FilterService(
            embedder = fakeEmbedder,
            scorer = scorer,
            tokenizer = tokenizer
        )

        val diag = service.diagnose("hello world")

        assertEquals("hello world", diag.rawText)
        assertEquals(3, diag.topCategories.size)
        assertTrue(diag.latencyMs >= 0)
        assertNotNull(diag.verdict)

        // Confirm diagnose did not cache: classify for new ID still calls embedder
        val countBefore = fakeEmbedder.callCount
        service.classify(999L, "hello world")
        assertEquals(countBefore + 1, fakeEmbedder.callCount)
    }
}
