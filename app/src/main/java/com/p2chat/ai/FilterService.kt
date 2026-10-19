package com.p2chat.ai

import android.content.Context
import com.p2chat.ai.model.ModelUnavailable
import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.model.TfliteEmbedder
import com.p2chat.ai.score.CategoryScore
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.TextNormalizer
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.settings.SettingDao
import com.p2chat.util.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Detailed diagnostic breakdown for test bench exploration (T3.10, AI-17).
 */
data class FilterDiagnosis(
    val rawText: String,
    val normalizedText: String,
    val tokens: List<String>,
    val tokenIds: IntArray,
    val latencyMs: Long,
    val topCategories: List<CategoryScore>,
    val score: Float,
    val verdict: FilterVerdict
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FilterDiagnosis) return false
        return rawText == other.rawText &&
                normalizedText == other.normalizedText &&
                tokens == other.tokens &&
                tokenIds.contentEquals(other.tokenIds) &&
                latencyMs == other.latencyMs &&
                topCategories == other.topCategories &&
                score == other.score &&
                verdict == other.verdict
    }

    override fun hashCode(): Int {
        var result = rawText.hashCode()
        result = 31 * result + normalizedText.hashCode()
        result = 31 * result + tokens.hashCode()
        result = 31 * result + tokenIds.contentHashCode()
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + topCategories.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + verdict.hashCode()
        return result
    }
}

/**
 * Layer 3 on-device semantic content moderation service (T3.10, AI-1, AI-5, AI-7, AI-11, AI-12, AI-13).
 *
 * Guarantees:
 * - Runs strictly post-decryption on receiving device via [Dispatchers.Default] (AI-1).
 * - Enforces threshold invariants (AI-11).
 * - Deduplicates inference per msgId via in-memory cache (AI-12).
 * - Fails safe to [FilterVerdict.NotScanned] if assets or embedder are unavailable (AI-13).
 */
class FilterService(
    private val embedder: SentenceEmbedder?,
    private val scorer: Scorer?,
    private val tokenizer: WordPieceTokenizer?,
    private val settingDao: SettingDao? = null,
    initialSettings: FilterSettings = FilterSettings()
) {
    companion object {
        private const val TAG = "FilterService"

        /**
         * Standard production factory initializing all required Layer 3 assets from Context.
         */
        fun create(context: Context, db: AppDatabase): FilterService {
            val embedder = try {
                TfliteEmbedder.fromAsset(context)
            } catch (e: Throwable) {
                SecureLog.w(TAG, "Failed to load TfliteEmbedder: ${e.message}")
                null
            }

            val tokenizer = try {
                context.assets.open("vocab.txt").use { stream ->
                    WordPieceTokenizer.fromInputStream(stream)
                }
            } catch (e: Throwable) {
                SecureLog.w(TAG, "Failed to load WordPieceTokenizer: ${e.message}")
                null
            }

            val scorer = try {
                val store = VectorStore.fromAssets(context)
                Scorer(store, db.blockedVectorDao())
            } catch (e: Throwable) {
                SecureLog.w(TAG, "Failed to load VectorStore/Scorer: ${e.message}")
                null
            }

            return FilterService(
                embedder = embedder,
                scorer = scorer,
                tokenizer = tokenizer,
                settingDao = db.settingDao()
            )
        }
    }

    @Volatile
    var settings: FilterSettings = initialSettings
        private set

    // In-memory deduplication cache: msg_id -> verdict (AI-12)
    private val verdictCache = ConcurrentHashMap<Long, FilterVerdict>()

    /**
     * Updates threshold pair in memory and persists to database if settingDao is available.
     * Enforces strict threshold invariants (AI-11).
     */
    suspend fun updateThresholds(tauWarn: Float, tauBlock: Float) {
        FilterSettings.validate(tauWarn, tauBlock)
        settings = FilterSettings(tauWarn, tauBlock)
        settingDao?.let { dao ->
            FilterSettingsRepository(dao).updateSettings(tauWarn, tauBlock)
        }
    }

    /**
     * Classifies a received message post-decryption on [Dispatchers.Default] (AI-1, AI-12).
     * If the message was already classified in this process, returns the cached verdict without re-embedding.
     */
    suspend fun classify(msgId: Long, text: String): FilterVerdict = withContext(Dispatchers.Default) {
        // AI-12: Deduplication check
        verdictCache[msgId]?.let { cachedVerdict ->
            return@withContext cachedVerdict
        }

        // AI-13: Asset availability check
        if (embedder == null || !embedder.isAvailable || tokenizer == null || scorer == null) {
            val verdict = FilterVerdict.NotScanned("Moderation assets or embedder unavailable")
            verdictCache[msgId] = verdict
            return@withContext verdict
        }

        try {
            // Step 1: Regex normalizer (AI-2, T3.6)
            val normalized = TextNormalizer.normalize(text)

            // Step 2: WordPiece tokenizer (AI-3, T3.7)
            val tokenized = tokenizer.encode(normalized, maxLength = 128)

            // Step 3: Quantized TFLite embedder with mutex (AI-4, T3.8)
            val embedding = embedder.embed(tokenized.tokenIds, tokenized.attentionMask)

            // Step 4: Vector dot-product scoring against centroids and blocked vectors (AI-5, AI-6, T3.9)
            val scoreResult = scorer.score(embedding)

            // Step 5: Tier policy assignment (AI-7, AI-9)
            val verdict = if (scoreResult.isDegenerate) {
                FilterVerdict.Tier3Clean(score = 0f, isDegenerate = true)
            } else if (scoreResult.forceQuarantine) {
                FilterVerdict.Tier1Quarantine(
                    score = scoreResult.sMax,
                    nearestSource = scoreResult.nearestSource,
                    topCategories = scoreResult.topCategories,
                    forcedByBlocklist = true
                )
            } else if (scoreResult.sMax >= settings.tauBlock) {
                FilterVerdict.Tier1Quarantine(
                    score = scoreResult.sMax,
                    nearestSource = scoreResult.nearestSource,
                    topCategories = scoreResult.topCategories,
                    forcedByBlocklist = false
                )
            } else if (scoreResult.sMax >= settings.tauWarn) {
                FilterVerdict.Tier2Warning(
                    score = scoreResult.sMax,
                    nearestSource = scoreResult.nearestSource,
                    topCategories = scoreResult.topCategories
                )
            } else {
                FilterVerdict.Tier3Clean(
                    score = scoreResult.sMax,
                    isDegenerate = false
                )
            }

            verdictCache[msgId] = verdict
            return@withContext verdict
        } catch (e: ModelUnavailable) {
            val verdict = FilterVerdict.NotScanned("Model unavailable during inference: ${e.message}")
            verdictCache[msgId] = verdict
            return@withContext verdict
        } catch (e: Throwable) {
            SecureLog.w(TAG, "Unexpected error in filter classification: ${e.message}")
            val verdict = FilterVerdict.NotScanned("Inference error: ${e.message}")
            verdictCache[msgId] = verdict
            return@withContext verdict
        }
    }

    /**
     * Diagnostic analysis for the AI Filter screen test bench (AI-17).
     * Runs full pipeline, records latency, normalized text, tokens, and top-3 categories.
     * Does NOT cache the result.
     */
    suspend fun diagnose(text: String): FilterDiagnosis = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()

        if (embedder == null || !embedder.isAvailable || tokenizer == null || scorer == null) {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            return@withContext FilterDiagnosis(
                rawText = text,
                normalizedText = text,
                tokens = emptyList(),
                tokenIds = IntArray(0),
                latencyMs = elapsedMs,
                topCategories = emptyList(),
                score = 0f,
                verdict = FilterVerdict.NotScanned("Moderation assets or embedder unavailable")
            )
        }

        val normalized = TextNormalizer.normalize(text)
        val basicTokens = tokenizer.basicTokenize(normalized)
        val tokenized = tokenizer.encode(normalized, maxLength = 128)

        val embedding = try {
            embedder.embed(tokenized.tokenIds, tokenized.attentionMask)
        } catch (e: Throwable) {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            return@withContext FilterDiagnosis(
                rawText = text,
                normalizedText = normalized,
                tokens = basicTokens,
                tokenIds = tokenized.tokenIds,
                latencyMs = elapsedMs,
                topCategories = emptyList(),
                score = 0f,
                verdict = FilterVerdict.NotScanned("Embedding error: ${e.message}")
            )
        }

        val scoreResult = scorer.score(embedding)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

        val verdict = if (scoreResult.isDegenerate) {
            FilterVerdict.Tier3Clean(score = 0f, isDegenerate = true)
        } else if (scoreResult.forceQuarantine) {
            FilterVerdict.Tier1Quarantine(
                score = scoreResult.sMax,
                nearestSource = scoreResult.nearestSource,
                topCategories = scoreResult.topCategories,
                forcedByBlocklist = true
            )
        } else if (scoreResult.sMax >= settings.tauBlock) {
            FilterVerdict.Tier1Quarantine(
                score = scoreResult.sMax,
                nearestSource = scoreResult.nearestSource,
                topCategories = scoreResult.topCategories,
                forcedByBlocklist = false
            )
        } else if (scoreResult.sMax >= settings.tauWarn) {
            FilterVerdict.Tier2Warning(
                score = scoreResult.sMax,
                nearestSource = scoreResult.nearestSource,
                topCategories = scoreResult.topCategories
            )
        } else {
            FilterVerdict.Tier3Clean(
                score = scoreResult.sMax,
                isDegenerate = false
            )
        }

        return@withContext FilterDiagnosis(
            rawText = text,
            normalizedText = normalized,
            tokens = basicTokens,
            tokenIds = tokenized.tokenIds,
            latencyMs = elapsedMs,
            topCategories = scoreResult.topCategories,
            score = scoreResult.sMax,
            verdict = verdict
        )
    }
}
