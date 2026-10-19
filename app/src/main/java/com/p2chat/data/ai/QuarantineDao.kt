package com.p2chat.data.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for quarantined messages (AI-8).
 */
@Dao
interface QuarantineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuarantineEntity): Long

    @Query("SELECT * FROM quarantine WHERE msg_id = :msgId")
    suspend fun getById(msgId: Long): QuarantineEntity?

    @Query("SELECT * FROM quarantine ORDER BY created_at_ms DESC")
    suspend fun getAll(): List<QuarantineEntity>

    @Query("SELECT * FROM quarantine ORDER BY created_at_ms DESC")
    fun getAllFlow(): Flow<List<QuarantineEntity>>

    @Query("SELECT COUNT(*) FROM quarantine")
    suspend fun count(): Int

    @Query("DELETE FROM quarantine WHERE msg_id = :msgId")
    suspend fun deleteById(msgId: Long): Int

    @Query("DELETE FROM quarantine WHERE created_at_ms < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long): Int

    /**
     * Purges quarantine records older than 7 days relative to [nowMs] (AI-8).
     */
    suspend fun purgeOlderThan7Days(nowMs: Long): Int {
        val cutoff = nowMs - SEVEN_DAYS_MS
        return purgeOlderThan(cutoff)
    }

    companion object {
        const val SEVEN_DAYS_MS: Long = 7L * 24 * 60 * 60 * 1000L
    }
}
