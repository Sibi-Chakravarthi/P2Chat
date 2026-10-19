package com.p2chat.data.identity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pinned peer identity record (ID-5, SEC-8).
 * Enforces Trust-On-First-Use (TOFU) key pinning: public keys for a known sender_fp
 * must never be silently overwritten.
 */
@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey
    @ColumnInfo(name = "sender_fp")
    val senderFp: Long,

    @ColumnInfo(name = "signing_pub", typeAffinity = ColumnInfo.BLOB)
    val signingPub: ByteArray,

    @ColumnInfo(name = "ecdh_pub", typeAffinity = ColumnInfo.BLOB)
    val ecdhPub: ByteArray,

    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "verified")
    val verified: Boolean = false,

    @ColumnInfo(name = "first_seen_ms")
    val firstSeenMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Peer

        if (senderFp != other.senderFp) return false
        if (!signingPub.contentEquals(other.signingPub)) return false
        if (!ecdhPub.contentEquals(other.ecdhPub)) return false
        if (alias != other.alias) return false
        if (verified != other.verified) return false
        if (firstSeenMs != other.firstSeenMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = senderFp.hashCode()
        result = 31 * result + signingPub.contentHashCode()
        result = 31 * result + ecdhPub.contentHashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + firstSeenMs.hashCode()
        return result
    }
}
