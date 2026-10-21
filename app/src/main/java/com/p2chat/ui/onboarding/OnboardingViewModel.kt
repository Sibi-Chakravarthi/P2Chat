package com.p2chat.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.group.RosterMemberInfo
import com.p2chat.crypto.invite.GroupInviteCodec
import com.p2chat.crypto.invite.PeerPinning
import com.p2chat.crypto.invite.PinningVerdict
import com.p2chat.crypto.invite.QrPayload
import com.p2chat.crypto.invite.QrPayloadCodec
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val identityManager: IdentityManager,
    private val peerDao: PeerDao,
    private val groupManager: GroupManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _alias = MutableStateFlow("Ghost_Node_71")
    val alias: StateFlow<String> = _alias.asStateFlow()

    private val _aliasError = MutableStateFlow<String?>(null)
    val aliasError: StateFlow<String?> = _aliasError.asStateFlow()

    init {
        checkExistingIdentity()
    }

    fun checkExistingIdentity() {
        viewModelScope.launch {
            try {
                val existing = identityManager.getIdentity()
                if (existing != null) {
                    _alias.value = existing.alias
                    emitSuccessState(existing)
                }
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error("Failed to check identity: ${e.message}")
            }
        }
    }

    fun updateAlias(newAlias: String) {
        val trimmed = newAlias.trim()
        if (!validateAlias(trimmed)) return
        viewModelScope.launch {
            try {
                identityManager.updateAlias(trimmed)
                _alias.value = trimmed
                val existing = identityManager.getIdentity()
                if (existing != null) {
                    emitSuccessState(existing)
                }
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error("Failed to update alias: ${e.message}")
            }
        }
    }

    fun setAlias(newAlias: String) {
        _alias.value = newAlias
        validateAlias(newAlias)
    }

    fun validateAlias(aliasStr: String): Boolean {
        return when {
            aliasStr.isEmpty() -> {
                _aliasError.value = "Alias cannot be empty"
                false
            }
            aliasStr.length > 32 -> {
                _aliasError.value = "Alias cannot exceed 32 characters"
                false
            }
            else -> {
                _aliasError.value = null
                true
            }
        }
    }

    fun generateRandomAlias() {
        val candidates = listOf(
            "Specter_Node_09", "Cipher_Fox_42", "Null_Apex_88",
            "Echo_Vanguard_19", "Rogue_Relay_04", "Ghost_Node_71",
            "Shadow_Mesh_12", "Apex_Raven_33", "Vortex_99"
        )
        val next = candidates.filter { it != _alias.value }.random()
        setAlias(next)
    }

    fun generateIdentity() {
        val currentAlias = _alias.value.trim()
        if (!validateAlias(currentAlias)) return

        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Loading
            try {
                val identity = identityManager.getOrCreateIdentity(currentAlias)
                emitSuccessState(identity)
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error("Key generation failed: ${e.message}")
            }
        }
    }

    fun processScannedQr(qrBase64: String) {
        viewModelScope.launch {
            // 1. Check if optical code is a Group Invite (SEC-9)
            if (GroupInviteCodec.isInvite(qrBase64)) {
                val inviteResult = GroupInviteCodec.decode(qrBase64)
                if (inviteResult.isSuccess) {
                    val invite = inviteResult.getOrThrow()
                    // Pin the group admin
                    val adminPeer = Peer(
                        senderFp = invite.adminFp,
                        signingPub = invite.adminSigningPub,
                        ecdhPub = invite.adminEcdhPub,
                        alias = invite.adminAlias,
                        verified = true,
                        firstSeenMs = System.currentTimeMillis()
                    )
                    peerDao.pinOrVerifyPeer(adminPeer)

                    // Join group if GroupManager is available
                    val selfIdentity = identityManager.getIdentity()
                    if (groupManager != null && selfIdentity != null) {
                        val adminMember = RosterMemberInfo(
                            senderFp = invite.adminFp,
                            signingPub = invite.adminSigningPub,
                            ecdhPub = invite.adminEcdhPub,
                            alias = invite.adminAlias,
                            revoked = false
                        )
                        val selfMember = RosterMemberInfo(
                            senderFp = selfIdentity.senderFp,
                            signingPub = selfIdentity.signingPub,
                            ecdhPub = selfIdentity.ecdhPub,
                            alias = selfIdentity.alias,
                            revoked = false
                        )
                        groupManager.joinGroup(
                            groupId = invite.groupId,
                            name = invite.groupName,
                            rawGroupKey = invite.groupKey,
                            keyEpoch = invite.keyEpoch,
                            rosterVersion = 1,
                            members = listOf(adminMember, selfMember)
                        )
                    }
                    updateCurrentStateWithScannedPeer(adminPeer, keyConflict = null, joinedGroupName = invite.groupName)
                    return@launch
                }
            }

            // 2. Decode mutual optical peer handshake (ID-4)
            val decodeResult = QrPayloadCodec.decodeFromBase64(qrBase64)
            if (decodeResult.isFailure) {
                _uiState.value = OnboardingUiState.Error("Malformed QR code: Invalid P2Chat optical payload")
                return@launch
            }

            val qrPayload = decodeResult.getOrThrow()
            when (val verdict = PeerPinning.pinFromQr(peerDao, qrPayload)) {
                is PinningVerdict.Pinned -> {
                    updateCurrentStateWithScannedPeer(verdict.peer, keyConflict = null)
                }
                is PinningVerdict.AlreadyPinned -> {
                    updateCurrentStateWithScannedPeer(verdict.peer, keyConflict = null)
                }
                is PinningVerdict.PeerKeyChanged -> {
                    updateCurrentStateWithScannedPeer(
                        verdict.existing,
                        keyConflict = "SECURITY WARNING: Known fingerprint presented a changed public key (ID-5)!"
                    )
                }
            }
        }
    }

    private fun emitSuccessState(identity: LocalIdentity) {
        val hexFp = EcProvider.senderFpHex(identity.senderFp)
        val formattedFp = hexFp.uppercase().chunked(4).joinToString(":")
        val qrPayload = QrPayload(
            signingPub = identity.signingPub,
            ecdhPub = identity.ecdhPub,
            alias = identity.alias
        )
        val qrBase64 = QrPayloadCodec.encodeToBase64(qrPayload)

        _uiState.value = OnboardingUiState.Created(
            identity = identity,
            formattedFingerprint = formattedFp,
            qrBase64 = qrBase64
        )
    }

    private fun updateCurrentStateWithScannedPeer(peer: Peer, keyConflict: String?, joinedGroupName: String? = null) {
        val current = _uiState.value
        if (current is OnboardingUiState.Created) {
            _uiState.value = current.copy(
                scannedPeer = peer,
                keyConflictWarning = keyConflict,
                joinedGroupName = joinedGroupName
            )
        }
    }
}
