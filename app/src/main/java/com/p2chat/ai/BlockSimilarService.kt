package com.p2chat.ai

import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.text.TextNormalizer
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.message.MessageDao
import com.p2chat.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Result of the "Block Similar" action (T3.11, AI-9, AI-10).
 */
sealed class BlockSimilarResult {
    data class Success(
        val blockedVector: BlockedVectorEntity
    ) : BlockSimilarResult()

    data class BlocklistFull(
        val currentCount: Int = BlockedVectorDao.MAX_BLOCKED_VECTORS
    ) : BlockSimilarResult()

    data class MessageNotFound(
        val msgId: Long
    ) : BlockSimilarResult()

    data class Failure(
        val reason: String
    ) : BlockSimilarResult()
}

/**
 * Service orchestrating the "Block Similar" workflow (T3.11, AI-9, AI-10).
 *
 * Guarantees:
 * - Decrypts stored body, normalizes, tokenizes, and embeds into an L2-normalized 384-d vector.
 * - Enforces the strict 500-vector maximum cap (AI-10).
 * - Immediately invalidates and refreshes the Scorer cache so near-duplicates match instantly without restart.
 */
class BlockSimilarService(
    private val messageDao: MessageDao,
    private val quarantineDao: QuarantineDao,
    private val blockedVectorDao: BlockedVectorDao,
    private val embedder: SentenceEmbedder,
    private val tokenizer: WordPieceTokenizer,
    private val scorer: Scorer,
    private val clock: Clock = Clock.System
) {

    suspend fun blockSimilar(
        msgId: Long,
        forceQuarantine: Boolean = true
    ): BlockSimilarResult = withContext(Dispatchers.Default) {
        // 1. Locate message in chat table or quarantine table
        val message = messageDao.getMessage(msgId)
        val text = message?.body?.text
            ?: quarantineDao.getById(msgId)?.body?.text
            ?: return@withContext BlockSimilarResult.MessageNotFound(msgId)

        if (text.isBlank()) {
            return@withContext BlockSimilarResult.Failure("Message body is blank or corrupted")
        }

        // 2. Normalize and tokenize
        val normalized = TextNormalizer.normalize(text)
        val tokenized = tokenizer.encode(normalized, maxLength = 128)

        // 3. Generate L2-normalized 384-dimensional embedding
        val embedding = try {
            embedder.embed(tokenized.tokenIds, tokenized.attentionMask)
        } catch (e: Throwable) {
            return@withContext BlockSimilarResult.Failure("Failed to embed message: ${e.message}")
        }

        // 4. Encode to 1,536-byte little-endian byte array
        val byteBuf = ByteBuffer.allocate(BlockedVectorEntity.VECTOR_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (v in embedding) {
            byteBuf.putFloat(v)
        }
        val vectorBlob = byteBuf.array()

        val entity = BlockedVectorEntity(
            vector = vectorBlob,
            forceQuarantine = forceQuarantine,
            sourceMsgId = msgId,
            createdAtMs = clock.currentTimeMillis()
        )

        // 5. Atomic insertion under 500-vector cap (AI-10)
        val inserted = blockedVectorDao.insertIfBelowCap(
            entity = entity,
            cap = BlockedVectorDao.MAX_BLOCKED_VECTORS
        )

        if (!inserted) {
            return@withContext BlockSimilarResult.BlocklistFull(
                currentCount = blockedVectorDao.count()
            )
        }

        // 6. Invalidate and refresh scorer cache immediately (AI-9)
        scorer.refreshBlockedVectors()

        return@withContext BlockSimilarResult.Success(entity)
    }
}
