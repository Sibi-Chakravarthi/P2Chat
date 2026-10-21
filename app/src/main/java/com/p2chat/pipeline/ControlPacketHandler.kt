package com.p2chat.pipeline

import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.invite.InviteService
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.rotation.KeyRotatePayload
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.PeerDao
import com.p2chat.util.SecureLog
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType

/**
 * Handles control packets (KEY_ROTATE, ROSTER_SYNC, PAIRWISE_INVITE) dispatched by ReceivePipeline (T4.2).
 */
class ControlPacketHandler(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private var onEpochRotated: (suspend (groupId: Long, newEpoch: Int) -> Unit)? = null,
    private val identityManager: IdentityManager? = null,
    private val peerDao: PeerDao? = null,
    private val groupManager: GroupManager? = null
) {
    companion object {
        private const val TAG = "ControlPacketHandler"
    }

    fun setOnEpochRotatedListener(listener: suspend (groupId: Long, newEpoch: Int) -> Unit) {
        onEpochRotated = listener
    }

    suspend fun handleControlPacket(
        verdict: PacketVerdict.Accept,
        frameBytes: ByteArray
    ) {
        when (verdict.header.packetType) {
            PacketType.KEY_ROTATE -> {
                handleKeyRotate(verdict)
            }
            PacketType.ROSTER_SYNC -> {
                handleRosterSync(verdict)
            }
            PacketType.PAIRWISE_INVITE -> {
                handlePairwiseInvite(verdict.header, frameBytes)
            }
            else -> {
                SecureLog.w(TAG, "Unhandled control packet type: ${verdict.header.packetType}")
            }
        }
    }

    private suspend fun handleKeyRotate(verdict: PacketVerdict.Accept) {
        val groupId = verdict.header.groupId
        val newEpoch = verdict.header.keyEpoch

        SecureLog.d(TAG, "Processing KEY_ROTATE for group $groupId to epoch $newEpoch")
        onEpochRotated?.invoke(groupId, newEpoch)
    }

    private suspend fun handleRosterSync(verdict: PacketVerdict.Accept) {
        val groupId = verdict.header.groupId
        SecureLog.d(TAG, "Processing ROSTER_SYNC for group $groupId")
    }

    private suspend fun handlePairwiseInvite(header: PacketHeader, frameBytes: ByteArray) {
        SecureLog.d(TAG, "PAIRWISE_INVITE received for group ${header.groupId} from ${header.senderFp}")
        if (identityManager == null || peerDao == null || groupManager == null) {
            SecureLog.w(TAG, "Missing dependencies to process PAIRWISE_INVITE")
            return
        }

        try {
            val identity = identityManager.getIdentity() ?: return
            val adminPeer = peerDao.getPeer(header.senderFp) ?: return
            val ecdhPriv = identityManager.unwrapEcdhPrivateKey(identity)
            val pairwiseChannel = PairwiseChannel.derive(ecdhPriv, adminPeer.ecdhPub)

            val openResult = InviteService.openInvitePacket(
                frameBytes = frameBytes,
                adminSigningPubkey = adminPeer.signingPub,
                inviteePairwiseChannel = pairwiseChannel
            )

            if (openResult.isSuccess) {
                val (_, invitePayload) = openResult.getOrThrow()
                groupManager.joinGroup(
                    groupId = invitePayload.groupId,
                    name = "Group ${invitePayload.groupId.toString(16).uppercase()}",
                    rawGroupKey = invitePayload.groupKey,
                    keyEpoch = invitePayload.keyEpoch,
                    rosterVersion = invitePayload.signedRoster.rosterVersion,
                    members = invitePayload.signedRoster.members
                )
                SecureLog.d(TAG, "Successfully joined group ${invitePayload.groupId} via PAIRWISE_INVITE packet")
                onEpochRotated?.invoke(invitePayload.groupId, invitePayload.keyEpoch)
            } else {
                SecureLog.w(TAG, "Failed to open PAIRWISE_INVITE: ${openResult.exceptionOrNull()?.message}")
            }
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Exception handling PAIRWISE_INVITE: ${t.message}")
        }
    }

    suspend fun notifyEpochRotated(groupId: Long, newEpoch: Int) {
        onEpochRotated?.invoke(groupId, newEpoch)
    }
}
