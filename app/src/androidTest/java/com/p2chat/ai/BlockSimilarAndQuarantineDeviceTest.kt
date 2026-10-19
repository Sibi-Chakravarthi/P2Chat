package com.p2chat.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockSimilarAndQuarantineDeviceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private class DeviceStubEmbedder : SentenceEmbedder {
        override val isAvailable: Boolean = true
        override val lastInferenceLatencyMs: Long = 1L
        override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray {
            return FloatArray(384) { 0f }.apply { this[0] = 1.0f }
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
            "test" to 103,
            "message" to 104
        )
        return WordPieceTokenizer(vocab)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.createInMemory(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testQuarantineRestoreMovesRowInRealRoomDatabase() = runBlocking {
        val clock = FakeClock(5000L)
        val repo = QuarantineRepository(db.quarantineDao(), db.messageDao(), clock)

        val entity = QuarantineEntity(
            msgId = 1001L,
            groupId = 42L,
            senderFp = 999L,
            body = EncryptedBody.of("quarantined threat alert"),
            score = 0.95f,
            nearestCategory = "threat",
            createdAtMs = 5000L,
            expiresAtMs = 5000L + (7L * 24 * 3600 * 1000L)
        )
        repo.add(entity)

        assertEquals(1, repo.count())
        assertEquals(0, db.messageDao().count())

        val restored = repo.restore(1001L)
        assertTrue("Restore must succeed", restored)

        assertEquals(0, repo.count())
        assertEquals(1, db.messageDao().count())

        val msg = db.messageDao().getMessage(1001L)
        assertNotNull(msg)
        assertEquals("quarantined threat alert", msg!!.body.text)
        assertTrue(msg.revealed)
        assertEquals(1, msg.tier)
    }

    @Test
    fun testQuarantinePurge7DaysInRealRoomDatabase() = runBlocking {
        val now = 10L * 24 * 3600 * 1000L
        val clock = FakeClock(now)
        val repo = QuarantineRepository(db.quarantineDao(), db.messageDao(), clock)

        // 8 days old
        repo.add(
            QuarantineEntity(
                msgId = 1L,
                groupId = 1L,
                senderFp = 10L,
                body = EncryptedBody.of("expired"),
                score = 0.9f,
                nearestCategory = "toxic",
                createdAtMs = now - (8L * 24 * 3600 * 1000L),
                expiresAtMs = now - (1L * 24 * 3600 * 1000L)
            )
        )

        // 3 days old
        repo.add(
            QuarantineEntity(
                msgId = 2L,
                groupId = 1L,
                senderFp = 20L,
                body = EncryptedBody.of("fresh"),
                score = 0.85f,
                nearestCategory = "threat",
                createdAtMs = now - (3L * 24 * 3600 * 1000L),
                expiresAtMs = now + (4L * 24 * 3600 * 1000L)
            )
        )

        assertEquals(2, repo.count())

        val purged = repo.purgeOlderThan7Days()
        assertEquals(1, purged)
        assertEquals(1, repo.count())
        assertNull(repo.getById(1L))
        assertNotNull(repo.getById(2L))
    }

    @Test
    fun testBlockSimilarEnforcesCapInRealRoomDatabase() = runBlocking {
        val dummyBlob = ByteArray(1536)
        val clock = FakeClock(1000L)
        val embedder = DeviceStubEmbedder()
        val tokenizer = createMinimalTokenizer()
        val store = VectorStore(FloatArray(384), listOf("toxic"))
        val scorer = Scorer(store, db.blockedVectorDao())

        val service = BlockSimilarService(
            messageDao = db.messageDao(),
            quarantineDao = db.quarantineDao(),
            blockedVectorDao = db.blockedVectorDao(),
            embedder = embedder,
            tokenizer = tokenizer,
            scorer = scorer,
            clock = clock
        )

        // Pre-insert 500 rows
        for (i in 1..500) {
            db.blockedVectorDao().insert(
                BlockedVectorEntity(
                    id = i.toLong(),
                    vector = dummyBlob,
                    forceQuarantine = true,
                    sourceMsgId = i.toLong(),
                    createdAtMs = 1000L
                )
            )
        }
        assertEquals(500, db.blockedVectorDao().count())

        // Insert message 501
        db.messageDao().insertMessage(
            MessageEntity(
                msgId = 501L,
                groupId = 1L,
                senderFp = 2L,
                keyEpoch = 0,
                sentAtMs = 1000L,
                receivedAtMs = 1000L,
                body = EncryptedBody.of("overflow message"),
                sigOk = true
            )
        )

        val result = service.blockSimilar(501L)
        assertTrue(result is BlockSimilarResult.BlocklistFull)
        assertEquals(500, db.blockedVectorDao().count())
    }
}
