package com.p2chat.ai

import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.Clock
import kotlinx.coroutines.flow.Flow

/**
 * Repository managing quarantined messages, restorations, and automated 7-day purging (T3.11, AI-8).
 */
class QuarantineRepository(
    private val quarantineDao: QuarantineDao,
    private val messageDao: MessageDao,
    private val clock: Clock = Clock.System
) {

    suspend fun add(entity: QuarantineEntity): Long {
        return quarantineDao.insert(entity)
    }

    fun observeAll(): Flow<List<QuarantineEntity>> {
        return quarantineDao.getAllFlow()
    }

    suspend fun getAll(): List<QuarantineEntity> {
        return quarantineDao.getAll()
    }

    suspend fun getById(msgId: Long): QuarantineEntity? {
        return quarantineDao.getById(msgId)
    }

    suspend fun count(): Int {
        return quarantineDao.count()
    }

    suspend fun delete(msgId: Long): Boolean {
        return quarantineDao.deleteById(msgId) > 0
    }

    /**
     * Restores a quarantined message back to the active chat messages feed with revealed = true (AI-8).
     * Atomically transfers exactly one row from quarantine to messages table.
     */
    suspend fun restore(msgId: Long): Boolean {
        val quarantined = quarantineDao.getById(msgId) ?: return false

        val restored = MessageEntity(
            msgId = quarantined.msgId,
            groupId = quarantined.groupId,
            senderFp = quarantined.senderFp,
            keyEpoch = 0,
            sentAtMs = quarantined.createdAtMs,
            receivedAtMs = quarantined.createdAtMs,
            body = quarantined.body,
            sigOk = true,
            tier = 1,
            score = quarantined.score,
            revealed = true
        )

        messageDao.insertMessage(restored)
        quarantineDao.deleteById(msgId)
        return true
    }

    /**
     * Purges quarantine records older than 7 days relative to current clock time (AI-8).
     */
    suspend fun purgeOlderThan7Days(): Int {
        return quarantineDao.purgeOlderThan7Days(clock.currentTimeMillis())
    }
}
