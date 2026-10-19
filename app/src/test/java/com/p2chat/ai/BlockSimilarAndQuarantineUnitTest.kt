package com.p2chat.ai

import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.NearestSource
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BlockSimilarAndQuarantineUnitTest {

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<Long, MessageEntity>()

        override suspend fun insertMessage(message: MessageEntity): Long {
            messages[message.msgId] = message
            return message.msgId
        }

        override suspend fun getMessage(msgId: Long): MessageEntity? = messages[msgId]

        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.groupId == groupId })

        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> =
            messages.values.filter { it.groupId == groupId }

        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int {
            val msg = messages[msgId] ?: return 0
            messages[msgId] = msg.copy(revealed = revealed)
            return 1
        }

        override suspend fun deleteMessage(msgId: Long): Int = if (messages.remove(msgId) != null) 1 else 0

        override suspend fun deleteGroupMessages(groupId: Long): Int {
            val toRemove = messages.values.filter { it.groupId == groupId }.map { it.msgId }
            toRemove.forEach { messages.remove(it) }
            return toRemove.size
        }

        override suspend fun count(): Int = messages.size
        override suspend fun countForGroup(groupId: Long): Int =
            messages.values.count { it.groupId == groupId }
    }

    private class FakeQuarantineDao : QuarantineDao {
        val records = mutableMapOf<Long, QuarantineEntity>()

        override suspend fun insert(entity: QuarantineEntity): Long {
            records[entity.msgId] = entity
            return entity.msgId
        }

        override suspend fun getById(msgId: Long): QuarantineEntity? = records[msgId]
        override suspend fun getAll(): List<QuarantineEntity> = records.values.toList()
        override fun getAllFlow(): Flow<List<QuarantineEntity>> = flowOf(records.values.toList())
        override suspend fun count(): Int = records.size
        override suspend fun deleteById(msgId: Long): Int = if (records.remove(msgId) != null) 1 else 0

        override suspend fun purgeOlderThan(cutoffMs: Long): Int {
            val toRemove = records.values.filter { it.createdAtMs < cutoffMs }.map { it.msgId }
            toRemove.forEach { records.remove(it) }
            return toRemove.size
        }
    }

    private class FakeBlockedVectorDao : BlockedVectorDao {
        val vectors = mutableListOf<BlockedVectorEntity>()
        var nextId = 1L

        override suspend fun count(): Int = vectors.size

        override suspend fun insert(entity: BlockedVectorEntity): Long {
            val assigned = entity.copy(id = nextId++)
            vectors.add(assigned)
            return assigned.id
        }

        override suspend fun getAll(): List<BlockedVectorEntity> = vectors.toList()
        override fun getAllFlow(): Flow<List<BlockedVectorEntity>> = flowOf(vectors.toList())
        override suspend fun getById(id: Long): BlockedVectorEntity? = vectors.firstOrNull { it.id == id }
        override suspend fun getBySourceMsgId(msgId: Long): BlockedVectorEntity? =
            vectors.firstOrNull { it.sourceMsgId == msgId }

        override suspend fun deleteById(id: Long): Int = if (vectors.removeIf { it.id == id }) 1 else 0
        override suspend fun deleteAll(): Int {
            val size = vectors.size
            vectors.clear()
            return size
        }

        override suspend fun insertIfBelowCap(entity: BlockedVectorEntity, cap: Int): Boolean {
            if (vectors.size >= cap) return false
            insert(entity)
            return true
        }
    }

    private class StubEmbedder : SentenceEmbedder {
        override val isAvailable: Boolean = true
        override val lastInferenceLatencyMs: Long = 1L

        override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray {
            // Predictable embedding: unit vector on dimension 5
            return FloatArray(384) { 0f }.apply { this[5] = 1.0f }
        }

        override fun reload() {}
        override fun close() {}
    }

    private fun createMinimalTokenizer(): WordPieceTokenizer {
        val vocab = mapOf(
            "[PAD]" to 0,
            "[UNK]" to 100,
            "[CLS]" to 101,
            "[SEP]" to 102,
            "hate" to 103,
            "speech" to 104
        )
        return WordPieceTokenizer(vocab)
    }

    @Test
    fun testBlockSimilar_successAndImmediateScoringOfNearDuplicate() = runBlocking {
        val msgDao = FakeMessageDao()
        val quarantineDao = FakeQuarantineDao()
        val blockedDao = FakeBlockedVectorDao()
        val embedder = StubEmbedder()
        val tokenizer = createMinimalTokenizer()

        // Empty centroids vector store
        val store = VectorStore(FloatArray(384), listOf("toxic"))
        val scorer = Scorer(store, blockedDao)
        val clock = FakeClock(1000L)

        val service = BlockSimilarService(
            messageDao = msgDao,
            quarantineDao = quarantineDao,
            blockedVectorDao = blockedDao,
            embedder = embedder,
            tokenizer = tokenizer,
            scorer = scorer,
            clock = clock
        )

        // Store a message to block
        val targetMsg = MessageEntity(
            msgId = 12345L,
            groupId = 1L,
            senderFp = 999L,
            keyEpoch = 0,
            sentAtMs = 1000L,
            receivedAtMs = 1000L,
            body = EncryptedBody.of("hate speech phrase"),
            sigOk = true,
            tier = 0,
            score = 0.1f,
            revealed = false
        )
        msgDao.insertMessage(targetMsg)

        // 1. Initial score against stub embedder output before blocking
        val query = FloatArray(384) { 0f }.apply { this[5] = 1.0f }
        val preScore = scorer.score(query)
        assertEquals(NearestSource.CENTROID, preScore.nearestSource)

        // 2. Perform Block Similar
        val result = service.blockSimilar(12345L, forceQuarantine = true)
        assertTrue("Block Similar must succeed", result is BlockSimilarResult.Success)
        val success = result as BlockSimilarResult.Success
        assertEquals(12345L, success.blockedVector.sourceMsgId)
        assertTrue(success.blockedVector.forceQuarantine)

        // 3. Score near-duplicate immediately without restart
        val postScore = scorer.score(query)
        assertEquals(NearestSource.USER_BLOCKED, postScore.nearestSource)
        assertEquals(1.0f, postScore.sMax, 1e-5f)
        assertTrue("forceQuarantine must be true for matched blocked vector", postScore.forceQuarantine)
    }

    @Test
    fun testBlockSimilar_refuses501stBlockAtCap() = runBlocking {
        val msgDao = FakeMessageDao()
        val quarantineDao = FakeQuarantineDao()
        val blockedDao = FakeBlockedVectorDao()
        val embedder = StubEmbedder()
        val tokenizer = createMinimalTokenizer()
        val store = VectorStore(FloatArray(384), listOf("toxic"))
        val scorer = Scorer(store, blockedDao)

        val service = BlockSimilarService(
            messageDao = msgDao,
            quarantineDao = quarantineDao,
            blockedVectorDao = blockedDao,
            embedder = embedder,
            tokenizer = tokenizer,
            scorer = scorer
        )

        // Pre-fill 500 blocked vectors
        val dummyVec = ByteArray(1536)
        for (i in 1..500) {
            blockedDao.insert(
                BlockedVectorEntity(
                    id = i.toLong(),
                    vector = dummyVec,
                    forceQuarantine = true,
                    sourceMsgId = i.toLong(),
                    createdAtMs = 1000L
                )
            )
        }
        assertEquals(500, blockedDao.count())

        // Add 501st message
        msgDao.insertMessage(
            MessageEntity(
                msgId = 99999L,
                groupId = 1L,
                senderFp = 1L,
                keyEpoch = 0,
                sentAtMs = 1000L,
                receivedAtMs = 1000L,
                body = EncryptedBody.of("blocked text"),
                sigOk = true
            )
        )

        // Attempt 501st block
        val result = service.blockSimilar(99999L)
        assertTrue("501st block must be refused", result is BlockSimilarResult.BlocklistFull)
        val full = result as BlockSimilarResult.BlocklistFull
        assertEquals(500, full.currentCount)
        assertEquals(500, blockedDao.count())
    }

    @Test
    fun testQuarantineRepository_restoreMovesExactlyOneRow() = runBlocking {
        val msgDao = FakeMessageDao()
        val quarantineDao = FakeQuarantineDao()
        val clock = FakeClock(1000L)
        val repo = QuarantineRepository(quarantineDao, msgDao, clock)

        val quarantined = QuarantineEntity(
            msgId = 777L,
            groupId = 5L,
            senderFp = 888L,
            body = EncryptedBody.of("flagged message"),
            score = 0.88f,
            nearestCategory = "threat",
            createdAtMs = 1000L,
            expiresAtMs = 1000L + 7L * 24 * 3600 * 1000L
        )
        repo.add(quarantined)

        assertEquals(1, repo.count())
        assertEquals(0, msgDao.count())

        // Restore
        val restoredOk = repo.restore(777L)
        assertTrue(restoredOk)

        assertEquals(0, repo.count())
        assertEquals(1, msgDao.count())

        val restoredMsg = msgDao.getMessage(777L)
        assertNotNull(restoredMsg)
        assertEquals(777L, restoredMsg!!.msgId)
        assertEquals(5L, restoredMsg.groupId)
        assertEquals(888L, restoredMsg.senderFp)
        assertEquals("flagged message", restoredMsg.body.text)
        assertTrue("Restored message must have revealed = true", restoredMsg.revealed)

        // Second restore attempt must return false
        assertFalse(repo.restore(777L))
    }

    @Test
    fun testQuarantineRepository_purgeRemovesOnlyOlderThan7Days() = runBlocking {
        val msgDao = FakeMessageDao()
        val quarantineDao = FakeQuarantineDao()
        val now = 10L * 24 * 3600 * 1000L // 10 days in ms
        val clock = FakeClock(now)
        val repo = QuarantineRepository(quarantineDao, msgDao, clock)

        val sevenDaysMs = 7L * 24 * 3600 * 1000L

        // Record 1: 8 days old (created at now - 8 days) -> Expired
        repo.add(
            QuarantineEntity(
                msgId = 1L,
                groupId = 1L,
                senderFp = 10L,
                body = EncryptedBody.of("8 days old"),
                score = 0.9f,
                nearestCategory = "toxic",
                createdAtMs = now - (8L * 24 * 3600 * 1000L),
                expiresAtMs = now - (1L * 24 * 3600 * 1000L)
            )
        )

        // Record 2: 3 days old (created at now - 3 days) -> Fresh
        repo.add(
            QuarantineEntity(
                msgId = 2L,
                groupId = 1L,
                senderFp = 20L,
                body = EncryptedBody.of("3 days old"),
                score = 0.85f,
                nearestCategory = "threat",
                createdAtMs = now - (3L * 24 * 3600 * 1000L),
                expiresAtMs = now + (4L * 24 * 3600 * 1000L)
            )
        )

        assertEquals(2, repo.count())

        val purgedCount = repo.purgeOlderThan7Days()
        assertEquals(1, purgedCount)
        assertEquals(1, repo.count())

        assertNull("8-day-old record must be purged", repo.getById(1L))
        assertNotNull("3-day-old record must survive", repo.getById(2L))
    }
}
