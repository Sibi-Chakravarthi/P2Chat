package com.p2chat.pipeline

import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.invite.InviteService
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.rotation.RotationService
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.message.MessageDao
import com.p2chat.util.SecureLog
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType

/**
 * Handles control packets (KEY_ROTATE, ROSTER_SYNC, PAIRWISE_INVITE, GROUP_REMOVE_MEMBER) dispatched by ReceivePipeline (T4.2, STEP 3.1).
 */
class ControlPacketHandler(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private var onEpochRotated: (suspend (groupId: Long, newEpoch: Int) -> Unit)? = null,
    private val identityManager: IdentityManager? = null,
    private val peerDao: PeerDao? = null,
    private val groupManager: GroupManager? = null,
    private val messageDao: MessageDao? = null,
    private var onGroupRemoved: (suspend (groupId: Long) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ControlPacketHandler"
    }

    fun setOnEpochRotatedListener(listener: suspend (groupId: Long, newEpoch: Int) -> Unit) {
        onEpochRotated = listener
    }

    fun setOnGroupRemovedListener(listener: suspend (groupId: Long) -> Unit) {
        onGroupRemoved = listener
    }

    suspend fun handleControlPacket(
        verdict: PacketVerdict.Accept,
        frameBytes: ByteArray
    ) {
        when (verdict.header.packetType) {
            PacketType.KEY_ROTATE -> {
                handleKeyRotate(verdict, frameBytes)
            }
            PacketType.ROSTER_SYNC -> {
                handleRosterSync(verdict, frameBytes)
            }
            PacketType.PAIRWISE_INVITE -> {
                handlePairwiseInvite(verdict.header, frameBytes)
            }
            PacketType.GROUP_REMOVE_MEMBER -> {
                handleGroupRemoveMember(verdict.header, frameBytes)
            }
            else -> {
                SecureLog.w(TAG, "Unhandled control packet type: ${verdict.header.packetType}")
            }
        }
    }

    private suspend fun handleKeyRotate(verdict: PacketVerdict.Accept, frameBytes: ByteArray) {
        val groupId = verdict.header.groupId
        val newEpoch = verdict.header.keyEpoch

        SecureLog.d(TAG, "Processing KEY_ROTATE for group $groupId to epoch $newEpoch")
        try {
            if (identityManager != null && peerDao != null) {
                val adminPeer = peerDao.getPeer(verdict.header.senderFp)
                val identity = identityManager.getIdentity()
                if (adminPeer != null && identity != null) {
                    val ecdhPriv = identityManager.unwrapEcdhPrivateKey(identity)
                    val pairwiseChannel = PairwiseChannel.derive(ecdhPriv, adminPeer.ecdhPub)
                    val openResult = RotationService.openKeyRotatePacket(
                        frameBytes = frameBytes,
                        adminSigningPubkey = adminPeer.signingPub,
                        recipientPairwiseChannel = pairwiseChannel
                    )
                    if (openResult.isSuccess) {
                        val (_, keyRotatePayload) = openResult.getOrThrow()
                        val currentGroup = groupDao.getGroup(groupId)
                        if (currentGroup != null) {
                            val keyVault = KeyVault()
                            val wrappedNewKey = keyVault.wrap(keyRotatePayload.newGroupKey)
                            val updatedGroup = currentGroup.copy(
                                currentEpoch = keyRotatePayload.keyEpoch,
                                wrappedCurrentKey = wrappedNewKey,
                                prevEpoch = currentGroup.currentEpoch,
                                wrappedPrevKey = currentGroup.wrappedCurrentKey,
                                graceExpiresMs = System.currentTimeMillis() + 10 * 60 * 1000L,
                                rosterVersion = keyRotatePayload.rosterVersion
                            )
                            groupDao.updateGroup(updatedGroup)
                            SecureLog.d(TAG, "Applied KEY_ROTATE: group $groupId updated to epoch ${keyRotatePayload.keyEpoch}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Failed to apply KEY_ROTATE for group $groupId: ${t.message}")
        }
        onEpochRotated?.invoke(groupId, newEpoch)
    }

    private suspend fun handleRosterSync(verdict: PacketVerdict.Accept, frameBytes: ByteArray) {
        val groupId = verdict.header.groupId
        SecureLog.d(TAG, "Processing ROSTER_SYNC for group $groupId")
        try {
            if (peerDao != null && groupManager != null) {
                val adminPeer = peerDao.getPeer(verdict.header.senderFp)
                val group = groupDao.getGroup(groupId)
                if (adminPeer != null && group != null) {
                    val keyVault = KeyVault()
                    val groupKey = keyVault.unwrap(group.wrappedCurrentKey)
                    val openResult = RotationService.openRosterSyncPacket(
                        frameBytes = frameBytes,
                        adminSigningPubkey = adminPeer.signingPub,
                        groupKey = groupKey
                    )
                    if (openResult.isSuccess) {
                        val (_, signedRoster) = openResult.getOrThrow()
                        groupManager.applyRosterSync(signedRoster, adminPeer.signingPub)
                        SecureLog.d(TAG, "Applied ROSTER_SYNC: group $groupId updated to roster version ${signedRoster.rosterVersion}")
                    }
                }
            }
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Failed to apply ROSTER_SYNC for group $groupId: ${t.message}")
        }
    }

    private suspend fun handlePairwiseInvite(header: PacketHeader, frameBytes: ByteArray) {
        SecureLog.d(TAG, "PAIRWISE_INVITE received for group ${header.groupId} from ${header.senderFp}")
        if (identityManager == null || peerDao == null || groupManager == null) {
            SecureLog.w(TAG, "Missing dependencies to process PAIRWISE_INVITE")
            return
        }

        try {
            val identity = identityManager.getIdentity() ?: return
            val adminPeer = peerDao.getPeer(header.senderFp) ?: run {
                SecureLog.w(TAG, "PAIRWISE_INVITE dropped: Admin ${header.senderFp} is not yet pinned in peerDao (requires mutual QR scan or group invite scan).")
                return
            }
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

    private suspend fun handleGroupRemoveMember(header: PacketHeader, frameBytes: ByteArray) {
        SecureLog.d(TAG, "GROUP_REMOVE_MEMBER received for group ${header.groupId} from ${header.senderFp}")
        if (identityManager == null) {
            SecureLog.w(TAG, "Missing identityManager to process GROUP_REMOVE_MEMBER")
            return
        }

        try {
            val identity = identityManager.getIdentity() ?: return

            val adminPeer = peerDao?.getPeer(header.senderFp)
            val adminMember = rosterMemberDao.getMember(header.groupId, header.senderFp)

            val adminSigningPub = adminPeer?.signingPub ?: adminMember?.signingPub
            val adminEcdhPub = adminPeer?.ecdhPub ?: adminMember?.ecdhPub

            if (adminSigningPub == null || adminEcdhPub == null) {
                SecureLog.w(TAG, "Cannot verify GROUP_REMOVE_MEMBER: Admin public keys not found for sender ${header.senderFp}")
                return
            }

            val ecdhPriv = identityManager.unwrapEcdhPrivateKey(identity)
            val pairwiseChannel = PairwiseChannel.derive(ecdhPriv, adminEcdhPub)

            val openResult = RotationService.openGroupRemoveMemberPacket(
                frameBytes = frameBytes,
                adminSigningPubkey = adminSigningPub,
                removedMemberPairwiseChannel = pairwiseChannel
            )

            if (openResult.isSuccess) {
                val (_, removePayload) = openResult.getOrThrow()
                if (removePayload.removedSenderFp == identity.senderFp) {
                    SecureLog.d(TAG, "Self removed from group ${removePayload.groupId}. Revoking membership and wiping group...")
                    groupDao.deleteGroup(removePayload.groupId)
                    rosterMemberDao.deleteGroupRoster(removePayload.groupId)
                    messageDao?.deleteGroupMessages(removePayload.groupId)
                    onGroupRemoved?.invoke(removePayload.groupId)
                } else {
                    rosterMemberDao.revokeMember(removePayload.groupId, removePayload.removedSenderFp)
                    SecureLog.d(TAG, "Revoked member ${removePayload.removedSenderFp} in group ${removePayload.groupId}")
                }
            } else {
                SecureLog.w(TAG, "Failed to open GROUP_REMOVE_MEMBER: ${openResult.exceptionOrNull()?.message}")
            }
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Exception handling GROUP_REMOVE_MEMBER: ${t.message}")
        }
    }
}
