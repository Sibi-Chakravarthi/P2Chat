package com.p2chat.crypto.envelope

import com.p2chat.wire.PacketHeader

/**
 * Result verdicts for packet validation and reception pipeline (PRD §3, §5.2).
 */
sealed class PacketVerdict {
    data class Accept(
        val header: PacketHeader,
        val sentAtMs: Long,
        val plaintext: String
    ) : PacketVerdict()

    object DropMalformed : PacketVerdict()
    object DropTtl : PacketVerdict()
    object DropDuplicate : PacketVerdict()
    object NotMemberOpaque : PacketVerdict()
    object HoldFutureEpoch : PacketVerdict()
    object DropEpochExpired : PacketVerdict()
    object DropUnknownSender : PacketVerdict()
    object DropRevokedSender : PacketVerdict()
    object DropBadSignature : PacketVerdict()
    object DropAuthFail : PacketVerdict()
    object DropStale : PacketVerdict()

    val isAccepted: Boolean get() = this is Accept
}
