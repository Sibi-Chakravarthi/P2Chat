package com.p2chat.data.group

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for roster_members table (SEC-8, SEC-10, SEC-11).
 */
@Dao
interface RosterMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(member: RosterMember): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<RosterMember>)

    @Query("SELECT * FROM roster_members WHERE group_id = :groupId AND sender_fp = :senderFp LIMIT 1")
    suspend fun getMember(groupId: Long, senderFp: Long): RosterMember?

    @Query("SELECT * FROM roster_members WHERE group_id = :groupId ORDER BY alias ASC")
    fun observeMembers(groupId: Long): Flow<List<RosterMember>>

    @Query("SELECT * FROM roster_members WHERE group_id = :groupId ORDER BY alias ASC")
    suspend fun getMembers(groupId: Long): List<RosterMember>

    @Query("SELECT * FROM roster_members WHERE group_id = :groupId AND revoked = 0 ORDER BY alias ASC")
    suspend fun getActiveMembers(groupId: Long): List<RosterMember>

    @Query("UPDATE roster_members SET revoked = 1 WHERE group_id = :groupId AND sender_fp = :senderFp")
    suspend fun revokeMember(groupId: Long, senderFp: Long): Int

    @Query("DELETE FROM roster_members WHERE group_id = :groupId AND sender_fp = :senderFp")
    suspend fun deleteMember(groupId: Long, senderFp: Long): Int

    @Query("DELETE FROM roster_members WHERE group_id = :groupId")
    suspend fun deleteGroupRoster(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM roster_members WHERE group_id = :groupId AND revoked = 0")
    suspend fun countActiveMembers(groupId: Long): Int
}
