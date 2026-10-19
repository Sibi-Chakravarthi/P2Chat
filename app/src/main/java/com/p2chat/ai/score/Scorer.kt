package com.p2chat.ai.score

import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-performance vector similarity scorer for on-device AI moderation (T3.9, AI-5, AI-6, AI-9).
 * Computes maximum dot product against bundled centroids and user-blocked vectors.
 */
class Scorer(
    val vectorStore: VectorStore,
    private val blockedVectorDao: BlockedVectorDao? = null
) {
    data class CachedBlockedVector(
        val id: Long,
        val vector: FloatArray,
        val forceQuarantine: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedBlockedVector) return false
            return id == other.id &&
                    forceQuarantine == other.forceQuarantine &&
                    vector.contentEquals(other.vector)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + vector.contentHashCode()
            result = 31 * result + forceQuarantine.hashCode()
            return result
        }
    }

    @Volatile
    private var cachedBlockedVectors: List<CachedBlockedVector> = emptyList()

    @Volatile
    private var isBlockedCacheValid: Boolean = false

    fun updateBlockedVectors(entities: List<BlockedVectorEntity>) {
        val list = ArrayList<CachedBlockedVector>(entities.size)
        for (e in entities) {
            val floatVec = FloatArray(VectorStore.EMBEDDING_DIM)
            ByteBuffer.wrap(e.vector)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(floatVec)
            list.add(CachedBlockedVector(e.id, floatVec, e.forceQuarantine))
        }
        cachedBlockedVectors = list
        isBlockedCacheValid = true
    }

    fun invalidateBlockedCache() {
        isBlockedCacheValid = false
    }

    suspend fun refreshBlockedVectors() {
        if (blockedVectorDao != null) {
            val entities = blockedVectorDao.getAll()
            updateBlockedVectors(entities)
        }
    }

    /**
     * Scores an embedding vector against all bundled centroids and user-blocked vectors (AI-6).
     *
     * @param query 384-dimensional embedding vector (L2-normalized).
     * @param explicitBlocked Optional list of blocked entities to use instead of the cache.
     * @return [ScoreResult] with maximum score, nearest source, and top-3 categories.
     */
    fun score(
        query: FloatArray,
        explicitBlocked: List<BlockedVectorEntity>? = null
    ): ScoreResult {
        require(query.size == VectorStore.EMBEDDING_DIM) {
            "Query vector must be exactly ${VectorStore.EMBEDDING_DIM} dimensions, got ${query.size}"
        }

        // AI-5: Check L2 norm
        var sumSq = 0.0
        for (v in query) {
            sumSq += (v * v)
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm <= 1e-9f) {
            return ScoreResult.degenerate()
        }

        // Normalize if not already unit norm
        val q = if (abs(norm - 1.0f) > 1e-5f) {
            FloatArray(VectorStore.EMBEDDING_DIM) { query[it] / norm }
        } else {
            query
        }

        val flat = vectorStore.flatCentroids
        val numCentroids = vectorStore.vectorCount
        val categories = vectorStore.categories

        var maxScore = -Float.MAX_VALUE
        var nearestSource = NearestSource.CENTROID
        var matchedBlockedId: Long? = null
        var forceQuarantine = false

        // Category maximum scores tracker
        val categoryMaxScores = HashMap<String, Float>()

        // 1. Scan bundled centroids
        var centroidOffset = 0
        for (i in 0 until numCentroids) {
            var dot = 0f
            var d = 0
            while (d < VectorStore.EMBEDDING_DIM) {
                dot += flat[centroidOffset + d] * q[d]
                d++
            }
            centroidOffset += VectorStore.EMBEDDING_DIM

            val cat = categories[i]
            val currCatMax = categoryMaxScores[cat]
            if (currCatMax == null || dot > currCatMax) {
                categoryMaxScores[cat] = dot
            }

            if (dot > maxScore) {
                maxScore = dot
                nearestSource = NearestSource.CENTROID
                matchedBlockedId = null
            }
        }

        // 2. Scan user-blocked vectors
        val blockedList: List<CachedBlockedVector> = if (explicitBlocked != null) {
            explicitBlocked.map { e ->
                val floatVec = FloatArray(VectorStore.EMBEDDING_DIM)
                ByteBuffer.wrap(e.vector)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(floatVec)
                CachedBlockedVector(e.id, floatVec, e.forceQuarantine)
            }
        } else {
            cachedBlockedVectors
        }

        for (b in blockedList) {
            var dot = 0f
            var d = 0
            val bVec = b.vector
            while (d < VectorStore.EMBEDDING_DIM) {
                dot += bVec[d] * q[d]
                d++
            }

            // AI-9: blocked vector scoring >= 0.80 forces Tier 1 quarantine regardless of sliders
            if (dot >= 0.80f && b.forceQuarantine) {
                forceQuarantine = true
            }

            if (dot > maxScore) {
                maxScore = dot
                nearestSource = NearestSource.USER_BLOCKED
                matchedBlockedId = b.id
            }
        }

        // Top-3 unique categories sorted by maximum score descending
        val top3Categories = categoryMaxScores.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { CategoryScore(it.key, it.value) }

        return ScoreResult(
            sMax = maxScore,
            nearestSource = nearestSource,
            topCategories = top3Categories,
            isDegenerate = false,
            matchedBlockedVectorId = matchedBlockedId,
            forceQuarantine = forceQuarantine
        )
    }
}
