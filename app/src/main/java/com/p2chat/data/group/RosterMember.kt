package com.p2chat.data.group

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Group membership roster entry (SEC-8, SEC-10, SEC-11).
 * Composite Primary Key: (group_id, sender_fp).
 */
@Entity(
    tableName = "roster_members",
    primaryKeys = ["group_id", "sender_fp"]
)
data class RosterMember(
    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "sender_fp")
    val senderFp: Long,

    @ColumnInfo(name = "signing_pub", typeAffinity = ColumnInfo.BLOB)
    val signingPub: ByteArray,

    @ColumnInfo(name = "ecdh_pub", typeAffinity = ColumnInfo.BLOB)
    val ecdhPub: ByteArray,

    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "revoked")
    val revoked: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RosterMember

        if (groupId != other.groupId) return false
        if (senderFp != other.senderFp) return false
        if (!signingPub.contentEquals(other.signingPub)) return false
        if (!ecdhPub.contentEquals(other.ecdhPub)) return false
        if (alias != other.alias) return false
        if (revoked != other.revoked) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + senderFp.hashCode()
        result = 31 * result + signingPub.contentHashCode()
        result = 31 * result + ecdhPub.contentHashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + revoked.hashCode()
        return result
    }
}
