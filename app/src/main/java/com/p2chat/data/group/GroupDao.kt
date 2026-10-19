package com.p2chat.data.group

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for groups table (SEC-8, SEC-10).
 */
@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: GroupEntity): Long

    @Update
    suspend fun updateGroup(group: GroupEntity): Int

    @Query("SELECT * FROM groups WHERE group_id = :groupId LIMIT 1")
    suspend fun getGroup(groupId: Long): GroupEntity?

    @Query("SELECT * FROM groups WHERE group_id = :groupId LIMIT 1")
    fun observeGroup(groupId: Long): Flow<GroupEntity?>

    @Query("SELECT * FROM groups ORDER BY name ASC")
    fun observeAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY name ASC")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("DELETE FROM groups WHERE group_id = :groupId")
    suspend fun deleteGroup(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun count(): Int
}
