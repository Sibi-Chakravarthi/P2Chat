package com.p2chat.data.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for messages table (PRD §4, SEC-15).
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE msg_id = :msgId LIMIT 1")
    suspend fun getMessage(msgId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE group_id = :groupId ORDER BY sent_at_ms ASC")
    fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE group_id = :groupId ORDER BY sent_at_ms ASC")
    suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity>

    @Query("UPDATE messages SET revealed = :revealed WHERE msg_id = :msgId")
    suspend fun setRevealed(msgId: Long, revealed: Boolean): Int

    @Query("DELETE FROM messages WHERE msg_id = :msgId")
    suspend fun deleteMessage(msgId: Long): Int

    @Query("DELETE FROM messages WHERE group_id = :groupId")
    suspend fun deleteGroupMessages(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE group_id = :groupId")
    suspend fun countForGroup(groupId: Long): Int
}
