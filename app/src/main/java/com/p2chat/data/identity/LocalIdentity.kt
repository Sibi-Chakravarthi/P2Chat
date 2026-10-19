package com.p2chat.data.identity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local device cryptographic identity and wrapped private keys (ID-1, SEC-1).
 * Private keys are wrapped under Keystore AES-256-GCM master key (p2chat_identity_wrap_key).
 */
@Entity(tableName = "local_identity")
data class LocalIdentity(
    @PrimaryKey
    @ColumnInfo(name = "sender_fp")
    val senderFp: Long,

    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "signing_pub", typeAffinity = ColumnInfo.BLOB)
    val signingPub: ByteArray,

    @ColumnInfo(name = "ecdh_pub", typeAffinity = ColumnInfo.BLOB)
    val ecdhPub: ByteArray,

    @ColumnInfo(name = "wrapped_signing_priv", typeAffinity = ColumnInfo.BLOB)
    val wrappedSigningPriv: ByteArray,

    @ColumnInfo(name = "wrapped_ecdh_priv", typeAffinity = ColumnInfo.BLOB)
    val wrappedEcdhPriv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocalIdentity

        if (senderFp != other.senderFp) return false
        if (alias != other.alias) return false
        if (!signingPub.contentEquals(other.signingPub)) return false
        if (!ecdhPub.contentEquals(other.ecdhPub)) return false
        if (!wrappedSigningPriv.contentEquals(other.wrappedSigningPriv)) return false
        if (!wrappedEcdhPriv.contentEquals(other.wrappedEcdhPriv)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = senderFp.hashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + signingPub.contentHashCode()
        result = 31 * result + ecdhPub.contentHashCode()
        result = 31 * result + wrappedSigningPriv.contentHashCode()
        result = 31 * result + wrappedEcdhPriv.contentHashCode()
        return result
    }
}
