package com.p2chat.data.mesh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for seen_messages table (NET-8).
 */
@Dao
interface SeenMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(seenMessage: SeenMessage): Long

    /**
     * Inserts the message ID if not already present.
     * Returns true if newly inserted, false if already present (duplicate).
     */
    suspend fun insertIfAbsent(seenMessage: SeenMessage): Boolean {
        return insertIgnore(seenMessage) != -1L
    }

    suspend fun insertIfAbsent(msgId: Long, firstSeenMs: Long): Boolean {
        return insertIfAbsent(SeenMessage(msgId, firstSeenMs))
    }

    @Query("DELETE FROM seen_messages WHERE msg_id = :msgId")
    suspend fun delete(msgId: Long): Int

    @Query("DELETE FROM seen_messages WHERE first_seen_ms < :cutoffMs")
    suspend fun purgeExpired(cutoffMs: Long): Int

    @Query("SELECT msg_id FROM seen_messages")
    suspend fun allMsgIds(): List<Long>

    @Query("SELECT COUNT(*) FROM seen_messages")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM seen_messages WHERE msg_id = :msgId)")
    suspend fun exists(msgId: Long): Boolean
}
