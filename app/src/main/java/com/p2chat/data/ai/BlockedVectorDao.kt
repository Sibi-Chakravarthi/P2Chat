package com.p2chat.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for user-blocked semantic vectors (AI-9, AI-10).
 * Enforces a strict 500-vector maximum cap (AI-10) to keep memory and dot-product scan times bounded.
 */
@Dao
interface BlockedVectorDao {

    @Query("SELECT COUNT(*) FROM user_blocked_vectors")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: BlockedVectorEntity): Long

    @Query("SELECT * FROM user_blocked_vectors ORDER BY created_at_ms DESC")
    suspend fun getAll(): List<BlockedVectorEntity>

    @Query("SELECT * FROM user_blocked_vectors ORDER BY created_at_ms DESC")
    fun getAllFlow(): Flow<List<BlockedVectorEntity>>

    @Query("SELECT * FROM user_blocked_vectors WHERE id = :id")
    suspend fun getById(id: Long): BlockedVectorEntity?

    @Query("SELECT * FROM user_blocked_vectors WHERE source_msg_id = :msgId LIMIT 1")
    suspend fun getBySourceMsgId(msgId: Long): BlockedVectorEntity?

    @Query("DELETE FROM user_blocked_vectors WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("UPDATE user_blocked_vectors SET force_quarantine = :forceQuarantine WHERE id = :id")
    suspend fun updateForceQuarantine(id: Long, forceQuarantine: Boolean): Int

    @Query("DELETE FROM user_blocked_vectors")
    suspend fun deleteAll(): Int

    /**
     * Atomically inserts [entity] only if the current row count is below [cap] (default 500).
     * Returns true if inserted, or false if the cap was reached (AI-10).
     */
    @Transaction
    suspend fun insertIfBelowCap(
        entity: BlockedVectorEntity,
        cap: Int = MAX_BLOCKED_VECTORS
    ): Boolean {
        require(entity.vector.size == BlockedVectorEntity.VECTOR_SIZE_BYTES) {
            "Vector blob must be exactly ${BlockedVectorEntity.VECTOR_SIZE_BYTES} bytes"
        }
        val currentCount = count()
        if (currentCount >= cap) {
            return false
        }
        insert(entity)
        return true
    }

    companion object {
        const val MAX_BLOCKED_VECTORS = 500
    }
}
