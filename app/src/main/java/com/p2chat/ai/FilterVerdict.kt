package com.p2chat.ai

import com.p2chat.ai.score.CategoryScore
import com.p2chat.ai.score.NearestSource

/**
 * Filter decision produced by Layer 3 semantic moderation (T3.10, AI-7, AI-13).
 */
sealed class FilterVerdict {
    abstract val score: Float
    abstract val isBlurred: Boolean

    /**
     * Tier 1 (Quarantine): s_max >= tau_block or forced blocked vector match (AI-7, AI-9).
     * Withheld from chat feed and placed in quarantine table.
     */
    data class Tier1Quarantine(
        override val score: Float,
        val nearestSource: NearestSource,
        val topCategories: List<CategoryScore>,
        val forcedByBlocklist: Boolean = false
    ) : FilterVerdict() {
        override val isBlurred: Boolean = true
    }

    /**
     * Tier 2 (Warning): tau_warn <= s_max < tau_block (AI-7).
     * Rendered blurred with "Flagged by on-device AI (s = x.xx). Tap to reveal."
     */
    data class Tier2Warning(
        override val score: Float,
        val nearestSource: NearestSource,
        val topCategories: List<CategoryScore>
    ) : FilterVerdict() {
        override val isBlurred: Boolean = true
    }

    /**
     * Tier 3 (Clean): s_max < tau_warn or degenerate zero vector (AI-5, AI-7).
     * Rendered normally in the chat feed.
     */
    data class Tier3Clean(
        override val score: Float,
        val isDegenerate: Boolean = false
    ) : FilterVerdict() {
        override val isBlurred: Boolean = false
    }

    /**
     * NotScanned: Model, vocab, or vectors failed to initialize (AI-13).
     * Rendered blurred with "Not scanned (AI filter unavailable) — Tap to reveal".
     * Never silently displayed as Clean.
     */
    data class NotScanned(
        val reason: String
    ) : FilterVerdict() {
        override val score: Float = 0f
        override val isBlurred: Boolean = true
    }
}
