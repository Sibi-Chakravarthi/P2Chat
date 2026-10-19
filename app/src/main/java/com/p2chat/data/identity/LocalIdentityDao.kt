package com.p2chat.data.identity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for local_identity table (ID-1, SEC-1).
 */
@Dao
interface LocalIdentityDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIdentity(identity: LocalIdentity): Long

    @Query("SELECT * FROM local_identity LIMIT 1")
    suspend fun getIdentity(): LocalIdentity?

    @Query("SELECT * FROM local_identity LIMIT 1")
    fun observeIdentity(): Flow<LocalIdentity?>

    @Query("UPDATE local_identity SET alias = :newAlias WHERE sender_fp = :senderFp")
    suspend fun updateAlias(senderFp: Long, newAlias: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM local_identity)")
    suspend fun hasIdentity(): Boolean

    @Query("DELETE FROM local_identity")
    suspend fun clearIdentity(): Int
}
