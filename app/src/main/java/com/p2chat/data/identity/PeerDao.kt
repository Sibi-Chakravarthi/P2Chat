package com.p2chat.data.identity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

sealed class PeerPinningResult {
    data class Success(val peer: Peer) : PeerPinningResult()
    data class KeyConflict(val existing: Peer, val attempted: Peer) : PeerPinningResult()
    data class Unchanged(val peer: Peer) : PeerPinningResult()
}

/**
 * Room DAO for peers table with key-pinning protection (ID-5, SEC-8).
 */
@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRaw(peer: Peer): Long

    @Query("SELECT * FROM peers WHERE sender_fp = :senderFp LIMIT 1")
    suspend fun getPeer(senderFp: Long): Peer?

    @Query("SELECT * FROM peers ORDER BY alias ASC")
    fun observeAllPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers ORDER BY alias ASC")
    suspend fun getAllPeers(): List<Peer>

    @Query("UPDATE peers SET verified = :verified WHERE sender_fp = :senderFp")
    suspend fun updateVerification(senderFp: Long, verified: Boolean): Int

    @Query("UPDATE peers SET alias = :alias WHERE sender_fp = :senderFp")
    suspend fun updateAlias(senderFp: Long, alias: String): Int

    @Query("DELETE FROM peers WHERE sender_fp = :senderFp")
    suspend fun deletePeer(senderFp: Long): Int

    @Query("SELECT COUNT(*) FROM peers")
    suspend fun count(): Int

    /**
     * Atomically inserts or validates a peer against existing pinned keys.
     * Rejects key modification with KeyConflict (never overwrites pinned public keys).
     */
    @Transaction
    suspend fun pinOrVerifyPeer(peer: Peer): PeerPinningResult {
        val existing = getPeer(peer.senderFp)
        return when {
            existing == null -> {
                insertRaw(peer)
                PeerPinningResult.Success(peer)
            }
            !existing.signingPub.contentEquals(peer.signingPub) || !existing.ecdhPub.contentEquals(peer.ecdhPub) -> {
                PeerPinningResult.KeyConflict(existing = existing, attempted = peer)
            }
            else -> {
                if (peer.verified && !existing.verified) {
                    updateVerification(peer.senderFp, true)
                }
                PeerPinningResult.Unchanged(existing)
            }
        }
    }
}
