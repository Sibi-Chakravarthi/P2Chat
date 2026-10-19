package com.p2chat.data.mesh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for packet_buffer table (NET-9).
 */
@Dao
interface BufferedPacketDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(packet: BufferedPacket): Long

    suspend fun insertIfAbsent(packet: BufferedPacket): Boolean {
        return insertIgnore(packet) != -1L
    }

    @Query("DELETE FROM packet_buffer WHERE msg_id = :msgId")
    suspend fun delete(msgId: Long): Int

    @Query("DELETE FROM packet_buffer WHERE expires_at_ms < :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM packet_buffer")
    suspend fun countBuffer(): Int

    @Query("SELECT COUNT(*) FROM packet_buffer")
    fun observeBufferCount(): Flow<Int>

    @Query("SELECT msg_id FROM packet_buffer")
    suspend fun allMsgIds(): List<Long>

    @Query("SELECT * FROM packet_buffer WHERE msg_id = :msgId LIMIT 1")
    suspend fun getPacket(msgId: Long): BufferedPacket?

    @Query("SELECT * FROM packet_buffer ORDER BY priority DESC, stored_at_ms ASC LIMIT :limit")
    suspend fun getPacketsToForward(limit: Int): List<BufferedPacket>

    @Query("DELETE FROM packet_buffer")
    suspend fun clearAll(): Int
}
