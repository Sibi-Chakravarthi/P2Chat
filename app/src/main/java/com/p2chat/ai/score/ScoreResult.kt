package com.p2chat.ai.score

/**
 * Origin of the nearest matching vector (AI-6).
 */
enum class NearestSource {
    CENTROID,
    USER_BLOCKED
}

/**
 * Category label and its maximum similarity score for a query (AI-17).
 */
data class CategoryScore(
    val category: String,
    val score: Float
)

/**
 * Verdict of the dot-product scan against centroids and user-blocked vectors (T3.9, AI-5, AI-6, AI-9).
 */
data class ScoreResult(
    val sMax: Float,
    val nearestSource: NearestSource,
    val topCategories: List<CategoryScore>,
    val isDegenerate: Boolean = false,
    val matchedBlockedVectorId: Long? = null,
    val forceQuarantine: Boolean = false
) {
    companion object {
        fun degenerate(): ScoreResult = ScoreResult(
            sMax = 0f,
            nearestSource = NearestSource.CENTROID,
            topCategories = emptyList(),
            isDegenerate = true,
            matchedBlockedVectorId = null,
            forceQuarantine = false
        )
    }
}
