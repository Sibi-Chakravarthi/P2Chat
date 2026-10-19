package com.p2chat.crypto.group

import com.p2chat.data.group.RosterMember

/**
 * Member entry in a signed group roster.
 */
data class RosterMemberInfo(
    val senderFp: Long,
    val signingPub: ByteArray,
    val ecdhPub: ByteArray,
    val alias: String,
    val revoked: Boolean = false
) {
    fun toRosterMember(groupId: Long): RosterMember = RosterMember(
        groupId = groupId,
        senderFp = senderFp,
        signingPub = signingPub,
        ecdhPub = ecdhPub,
        alias = alias,
        revoked = revoked
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RosterMemberInfo

        if (senderFp != other.senderFp) return false
        if (!signingPub.contentEquals(other.signingPub)) return false
        if (!ecdhPub.contentEquals(other.ecdhPub)) return false
        if (alias != other.alias) return false
        if (revoked != other.revoked) return false

        return true
    }

    override fun hashCode(): Int {
        var result = senderFp.hashCode()
        result = 31 * result + signingPub.contentHashCode()
        result = 31 * result + ecdhPub.contentHashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + revoked.hashCode()
        return result
    }
}

/**
 * Complete signed roster payload for ROSTER_SYNC and invite verification (SEC-8, SEC-10).
 */
data class SignedRoster(
    val groupId: Long,
    val rosterVersion: Int,
    val adminFp: Long,
    val members: List<RosterMemberInfo>,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignedRoster

        if (groupId != other.groupId) return false
        if (rosterVersion != other.rosterVersion) return false
        if (adminFp != other.adminFp) return false
        if (members != other.members) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + rosterVersion
        result = 31 * result + adminFp.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
