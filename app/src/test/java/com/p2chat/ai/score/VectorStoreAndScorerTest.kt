package com.p2chat.ai.score

import com.p2chat.data.ai.BlockedVectorEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VectorStoreAndScorerTest {

    private fun findAssetDir(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("../app/src/main/assets")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("Could not find assets directory")
    }

    @Test
    fun testRejectInvalidByteLength_notMultipleOf1536() {
        val invalidBytes = ByteArray(1537) { 0x01 }
        val dummyMeta = "[\"toxic\"]"

        try {
            VectorStore.fromBytes(invalidBytes, dummyMeta)
            fail("Expected IllegalArgumentException for 1537-byte file")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Must be an exact multiple of 1536 bytes") == true)
        }
    }

    @Test
    fun testRejectMetadataLengthMismatch() {
        val validBytes = ByteArray(1536 * 2) { 0x00 }
        val metaWith1 = "[\"toxic\"]" // 1 instead of 2

        try {
            VectorStore.fromBytes(validBytes, metaWith1)
            fail("Expected IllegalArgumentException for metadata count mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("does not match vector count") == true)
        }
    }

    @Test
    fun testLoadRealShippedAssets_andQueryEqualCentroidScoresOne() {
        val assetDir = findAssetDir()
        val binFile = File(assetDir, "toxic_vectors.bin")
        val metaFile = File(assetDir, "toxic_vectors_meta.json")

        assertTrue("toxic_vectors.bin must exist", binFile.isFile)
        assertTrue("toxic_vectors_meta.json must exist", metaFile.isFile)

        val store = VectorStore.fromBytes(binFile.readBytes(), metaFile.readText())
        assertEquals(525, store.vectorCount)
        assertEquals(525 * 384, store.flatCentroids.size)

        val scorer = Scorer(store)

        // Pick 5 random centroids: each must score 1.0 ± 1e-6 against itself
        for (idx in listOf(0, 10, 50, 100, 524)) {
            val centroid = store.getVector(idx)
            val result = scorer.score(centroid)

            assertEquals(NearestSource.CENTROID, result.nearestSource)
            assertEquals("Stored centroid must score 1.0 ± 1e-6 against itself", 1.0f, result.sMax, 1e-5f)
            assertFalse(result.isDegenerate)
            assertTrue("Top categories must have up to 3 elements", result.topCategories.size in 1..3)
            assertEquals(store.categories[idx], result.topCategories[0].category)
        }
    }

    @Test
    fun testZeroVectorProducesDegenerateCleanResult() {
        val assetDir = findAssetDir()
        val binFile = File(assetDir, "toxic_vectors.bin")
        val metaFile = File(assetDir, "toxic_vectors_meta.json")
        val store = VectorStore.fromBytes(binFile.readBytes(), metaFile.readText())
        val scorer = Scorer(store)

        val zeroVector = FloatArray(384) { 0f }
        val result = scorer.score(zeroVector)

        assertTrue(result.isDegenerate)
        assertEquals(0f, result.sMax, 0.0f)
        assertTrue(result.topCategories.isEmpty())
        assertFalse(result.forceQuarantine)
    }

    @Test
    fun testBlockedVectorAddedAtRuntimeChangesScoreWithoutRestart() {
        val assetDir = findAssetDir()
        val binFile = File(assetDir, "toxic_vectors.bin")
        val metaFile = File(assetDir, "toxic_vectors_meta.json")
        val store = VectorStore.fromBytes(binFile.readBytes(), metaFile.readText())
        val scorer = Scorer(store)

        // Construct an arbitrary unique test vector orthogonal to normal centroids
        val query = FloatArray(384) { 0f }.apply {
            this[0] = 1f
        }

        val initialScore = scorer.score(query)

        // Create a blocked vector matching query exactly
        val bb = ByteBuffer.allocate(1536).order(ByteOrder.LITTLE_ENDIAN)
        for (v in query) {
            bb.putFloat(v)
        }
        val blockedEntity = BlockedVectorEntity(
            id = 42L,
            vector = bb.array(),
            forceQuarantine = true,
            sourceMsgId = 1001L,
            createdAtMs = System.currentTimeMillis()
        )

        // Inject blocked vector at runtime
        scorer.updateBlockedVectors(listOf(blockedEntity))

        val updatedScore = scorer.score(query)
        assertEquals(NearestSource.USER_BLOCKED, updatedScore.nearestSource)
        assertEquals(42L, updatedScore.matchedBlockedVectorId)
        assertEquals(1.0f, updatedScore.sMax, 1e-5f)
        assertTrue("forceQuarantine must be true when score >= 0.80 and flag enabled", updatedScore.forceQuarantine)
        assertTrue(updatedScore.sMax > initialScore.sMax)
    }

    @Test
    fun testDotProductScanLatencyOver1000VectorsUnder2ms() {
        // Build 1,000 vectors (525 centroids + 475 blocked vectors)
        val assetDir = findAssetDir()
        val binFile = File(assetDir, "toxic_vectors.bin")
        val metaFile = File(assetDir, "toxic_vectors_meta.json")
        val store = VectorStore.fromBytes(binFile.readBytes(), metaFile.readText())
        val scorer = Scorer(store)

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

        // Warm up JIT
        for (i in 0 until 100) {
            scorer.score(query)
        }

        // Measure 1,000 runs
        val iterations = 1000
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            scorer.score(query)
        }
        val totalElapsedNs = System.nanoTime() - t0
        val avgLatencyMs = (totalElapsedNs.toDouble() / iterations) / 1_000_000.0

        println("Average dot product scan latency over 1,000 vectors: $avgLatencyMs ms")
        assertTrue(
            "Scanning 1,000 vectors must average under 2.0 ms (was $avgLatencyMs ms)",
            avgLatencyMs < 2.0
        )
    }
}
