package com.p2chat.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.group.RosterMemberInfo
import com.p2chat.crypto.group.SignedRoster
import com.p2chat.crypto.invite.GroupInviteData
import com.p2chat.crypto.invite.InvitePayload
import com.p2chat.crypto.invite.InviteService
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.invite.QrPayload
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.crypto.rotation.RotationService
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.identity.PeerPinningResult
import com.p2chat.pipeline.SendPipeline
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * State representing Group Administration screen content (T4.6, SEC-9..11, UI-4).
 */
sealed class GroupAdminUiState {
    object Loading : GroupAdminUiState()
    object AccessDenied : GroupAdminUiState()
    data class Success(
        val group: GroupEntity,
        val members: List<RosterMember>,
        val currentEpoch: Int,
        val epochDigest: String,
        val graceRemainingSeconds: Int,
        val canAddMember: Boolean
    ) : GroupAdminUiState()
    data class Error(val message: String) : GroupAdminUiState()
}

/**
 * ViewModel managing Group Admin operations: member removal with forward secrecy key rotation,
 * manual ratchet key rotation, and QR-based mutual peer invitations (T4.6, SEC-9, SEC-10, SEC-11, UI-4).
 */
class GroupAdminViewModel(
    val groupId: Long,
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private val localIdentityDao: LocalIdentityDao,
    private val peerDao: PeerDao,
    private val identityManager: IdentityManager,
    private val rotationService: RotationService,
    private val sendPipeline: SendPipeline,
    private val keyVault: KeyVault,
    private val clock: Clock = Clock.System
) : ViewModel() {
    companion object {
        private const val TAG = "GroupAdminViewModel"
    }

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val uiState: StateFlow<GroupAdminUiState> = combine(
        groupDao.observeGroup(groupId),
        rosterMemberDao.observeMembers(groupId)
    ) { group, members ->
        if (group == null) {
            return@combine GroupAdminUiState.Error("Group not found")
        }

        // Access Control: Non-admins cannot access or manage group admin (SEC-10, UI-4)
        if (group.myRole != "ADMIN") {
            return@combine GroupAdminUiState.AccessDenied
        }

        val nowMs = clock.currentTimeMillis()
        val graceRemaining = if (group.graceExpiresMs != null && group.graceExpiresMs > nowMs) {
            ((group.graceExpiresMs - nowMs) / 1000L).toInt()
        } else {
            0
        }

        val activeMembers = members.filter { !it.revoked }
        val canAdd = activeMembers.size < BleConstants.MAX_ROSTER_MEMBERS

        // Compute short SHA-256 digest of wrapped current key for visual verification
        val digest = computeEpochDigest(group.wrappedCurrentKey)

        GroupAdminUiState.Success(
            group = group,
            members = members,
            currentEpoch = group.currentEpoch,
            epochDigest = digest,
            graceRemainingSeconds = graceRemaining,
            canAddMember = canAdd
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GroupAdminUiState.Loading)

    /**
     * Removes a member from the group (SEC-11).
     * Automatically increments key_epoch, generates fresh group key, revokes member,
     * signs new roster, and floods N-1 KEY_ROTATE frames + ROSTER_SYNC over the mesh.
     */
    fun removeMember(removedSenderFp: Long): Job = viewModelScope.launch(Dispatchers.Default) {
        val group = groupDao.getGroup(groupId) ?: return@launch
        if (group.myRole != "ADMIN") {
            _snackbarEvent.emit("Unauthorized: Only group admin can remove members.")
            return@launch
        }

        val identity = localIdentityDao.getIdentity() ?: return@launch
        val signingPrivKey = identityManager.unwrapSigningPrivateKey(identity)
        val ecdhPrivKey = identityManager.unwrapEcdhPrivateKey(identity)

        val members = rosterMemberDao.getMembers(groupId)
        val currentRoster = buildSignedRoster(group, members, identity, signingPrivKey)

        val nowMs = clock.currentTimeMillis()
        val result = rotationService.removeMember(
            groupId = groupId,
            currentEpoch = group.currentEpoch,
            currentRoster = currentRoster,
            removedSenderFp = removedSenderFp,
            adminIdentity = identity,
            adminSigningPrivateKey = signingPrivKey,
            adminEcdhPrivateKey = ecdhPrivKey,
            nowMs = nowMs
        )

        result.fold(
            onSuccess = { removalResult ->
                val frames = removalResult.keyRotatePackets + removalResult.rosterSyncPacket
                sendPipeline.sendControlFrames(frames)
                SecureLog.d(TAG, "Removed member $removedSenderFp; rotated to epoch ${removalResult.newEpoch} with ${frames.size} control packets")
                _snackbarEvent.emit("Member removed. Key rotated to Epoch ${removalResult.newEpoch} for forward secrecy.")
            },
            onFailure = { t ->
                SecureLog.w(TAG, "Failed to remove member: ${t.message}")
                _snackbarEvent.emit("Failed to remove member: ${t.message}")
            }
        )
    }

    /**
     * Manually triggers multi-hop forward secrecy re-keying across the mesh (T4.6, SEC-11).
     */
    fun rotateKeyNow(): Job = viewModelScope.launch(Dispatchers.Default) {
        val group = groupDao.getGroup(groupId) ?: return@launch
        if (group.myRole != "ADMIN") {
            _snackbarEvent.emit("Unauthorized: Only group admin can rotate keys.")
            return@launch
        }

        val identity = localIdentityDao.getIdentity() ?: return@launch
        val signingPrivKey = identityManager.unwrapSigningPrivateKey(identity)
        val ecdhPrivKey = identityManager.unwrapEcdhPrivateKey(identity)

        val members = rosterMemberDao.getMembers(groupId)
        val currentRoster = buildSignedRoster(group, members, identity, signingPrivKey)

        val nowMs = clock.currentTimeMillis()
        val result = rotationService.rotateKey(
            groupId = groupId,
            currentEpoch = group.currentEpoch,
            currentRoster = currentRoster,
            adminIdentity = identity,
            adminSigningPrivateKey = signingPrivKey,
            adminEcdhPrivateKey = ecdhPrivKey,
            nowMs = nowMs
        )

        result.fold(
            onSuccess = { rotResult ->
                val frames = rotResult.keyRotatePackets + rotResult.rosterSyncPacket
                sendPipeline.sendControlFrames(frames)
                SecureLog.d(TAG, "Rotated key to epoch ${rotResult.newEpoch} with ${frames.size} control packets")
                _snackbarEvent.emit("Group key rotated to Epoch ${rotResult.newEpoch}.")
            },
            onFailure = { t ->
                SecureLog.w(TAG, "Failed to rotate key: ${t.message}")
                _snackbarEvent.emit("Key rotation failed: ${t.message}")
            }
        )
    }

    /**
     * Adds a new member via scanned QR code (T4.6, SEC-9).
     * Enforces strict 16-member limit and sends PAIRWISE_INVITE + ROSTER_SYNC.
     */
    fun addMemberFromQr(qrPayload: QrPayload, rotateKeyOnJoin: Boolean = false): Job = viewModelScope.launch(Dispatchers.Default) {
        val group = groupDao.getGroup(groupId) ?: return@launch
        if (group.myRole != "ADMIN") {
            _snackbarEvent.emit("Unauthorized: Only group admin can add members.")
            return@launch
        }

        val activeMembers = rosterMemberDao.getActiveMembers(groupId)
        if (activeMembers.size >= BleConstants.MAX_ROSTER_MEMBERS) {
            _snackbarEvent.emit("Group is at maximum capacity (16 members).")
            return@launch
        }

        val identity = localIdentityDao.getIdentity() ?: return@launch
        val signingPrivKey = identityManager.unwrapSigningPrivateKey(identity)
        val ecdhPrivKey = identityManager.unwrapEcdhPrivateKey(identity)

        val nowMs = clock.currentTimeMillis()

        val senderFp = EcProvider.computeSenderFp(qrPayload.signingPub)

        // 1. Pin or verify peer in database (ID-5, SEC-8)
        val newPeer = Peer(
            senderFp = senderFp,
            signingPub = qrPayload.signingPub,
            ecdhPub = qrPayload.ecdhPub,
            alias = qrPayload.alias,
            verified = true,
            firstSeenMs = nowMs
        )
        val pinResult = peerDao.pinOrVerifyPeer(newPeer)
        if (pinResult is PeerPinningResult.KeyConflict) {
            _snackbarEvent.emit("Rejected: Peer ${qrPayload.alias} has conflicting pinned public keys.")
            return@launch
        }

        // 2. Add to roster
        val newMember = RosterMember(
            groupId = groupId,
            senderFp = senderFp,
            signingPub = qrPayload.signingPub,
            ecdhPub = qrPayload.ecdhPub,
            alias = qrPayload.alias,
            revoked = false
        )
        rosterMemberDao.insertOrUpdate(newMember)

        // 3. Construct updated signed roster
        val allMembers = rosterMemberDao.getMembers(groupId)
        val newRosterVersion = group.rosterVersion + 1
        val memberInfos = allMembers.map {
            RosterMemberInfo(it.senderFp, it.signingPub, it.ecdhPub, it.alias, it.revoked)
        }
        val unsignedRoster = RosterCodec.encodeForSigning(
            groupId = groupId,
            rosterVersion = newRosterVersion,
            adminFp = identity.senderFp,
            members = memberInfos
        )
        val adminSig = Ecdsa.sign(signingPrivKey, unsignedRoster)
        val signedRoster = SignedRoster(
            groupId = groupId,
            rosterVersion = newRosterVersion,
            adminFp = identity.senderFp,
            members = memberInfos,
            signature = adminSig
        )

        // 4. Update group roster version in DB
        groupDao.updateGroup(group.copy(rosterVersion = newRosterVersion))

        // 5. Derive pairwise channel for invite
        val inviteeChannel = PairwiseChannel.derive(ecdhPrivKey, qrPayload.ecdhPub)
        val groupKeyBytes = keyVault.unwrap(group.wrappedCurrentKey)

        // 6. Build PAIRWISE_INVITE packet
        val invitePayload = InvitePayload(
            groupId = groupId,
            groupKey = groupKeyBytes,
            keyEpoch = group.currentEpoch,
            signedRoster = signedRoster
        )
        val invitePacket = InviteService.buildInvitePacket(
            adminFp = identity.senderFp,
            adminSigningPrivateKey = signingPrivKey,
            inviteePairwiseChannel = inviteeChannel,
            invitePayload = invitePayload,
            sentAtMs = nowMs
        )

        // 7. Build ROSTER_SYNC packet
        val rosterSyncPacket = RotationService.buildRosterSyncPacket(
            adminFp = identity.senderFp,
            adminSigningPrivateKey = signingPrivKey,
            signedRoster = signedRoster,
            groupKey = groupKeyBytes,
            keyEpoch = group.currentEpoch,
            sentAtMs = nowMs
        )

        // 8. Flood control frames to mesh
        sendPipeline.sendControlFrames(listOf(invitePacket, rosterSyncPacket))
        SecureLog.d(TAG, "Invited ${qrPayload.alias} ($senderFp) to group $groupId (roster v$newRosterVersion)")
        _snackbarEvent.emit("Added ${qrPayload.alias} to group.")
    }

    suspend fun getGroupInviteData(): GroupInviteData? = withContext(Dispatchers.Default) {
        val group = groupDao.getGroup(groupId) ?: return@withContext null
        val identity = localIdentityDao.getIdentity() ?: return@withContext null
        val groupKey = keyVault.unwrap(group.wrappedCurrentKey)
        GroupInviteData(
            groupId = group.groupId,
            groupName = group.name,
            groupKey = groupKey,
            keyEpoch = group.currentEpoch,
            adminFp = identity.senderFp,
            adminSigningPub = identity.signingPub,
            adminEcdhPub = identity.ecdhPub,
            adminAlias = identity.alias
        )
    }

    private fun buildSignedRoster(
        group: GroupEntity,
        members: List<RosterMember>,
        identity: LocalIdentity,
        signingPrivKey: java.security.PrivateKey
    ): SignedRoster {
        val memberInfos = members.map {
            RosterMemberInfo(it.senderFp, it.signingPub, it.ecdhPub, it.alias, it.revoked)
        }
        val unsignedRoster = RosterCodec.encodeForSigning(
            groupId = groupId,
            rosterVersion = group.rosterVersion,
            adminFp = identity.senderFp,
            members = memberInfos
        )
        val sig = Ecdsa.sign(signingPrivKey, unsignedRoster)
        return SignedRoster(
            groupId = groupId,
            rosterVersion = group.rosterVersion,
            adminFp = identity.senderFp,
            members = memberInfos,
            signature = sig
        )
    }

    private fun computeEpochDigest(wrappedKey: ByteArray): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(wrappedKey)
            digest.take(6).joinToString("") { "%02x".format(it) } + "..."
        } catch (e: Exception) {
            "..."
        }
    }
}
