package com.p2chat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.ai.BlockSimilarResult
import com.p2chat.ai.BlockSimilarService
import com.p2chat.crypto.envelope.MessageLimits
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.pipeline.SendPipeline
import com.p2chat.pipeline.SendResult
import com.p2chat.util.SecureLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI representation of an individual message bubble (T4.5).
 */
data class ChatMessageUiModel(
    val msgId: Long,
    val groupId: Long,
    val senderFp: Long,
    val senderAlias: String,
    val isSelf: Boolean,
    val keyEpoch: Int,
    val sentAtMs: Long,
    val text: String,
    val sigOk: Boolean,
    val tofuVerified: Boolean,
    val tier: Int,
    val score: Float,
    val revealed: Boolean
)

/**
 * ViewModel managing E2EE chat stream, composer, and moderation actions (T4.5, SEC-19, AI-7, AI-9, AI-12).
 */
class ChatViewModel(
    val groupId: Long,
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private val peerDao: PeerDao,
    private val localIdentityDao: LocalIdentityDao,
    private val messageDao: MessageDao,
    private val sendPipeline: SendPipeline,
    private val blockSimilarService: BlockSimilarService? = null
) : ViewModel() {
    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val group: StateFlow<GroupEntity?> = groupDao.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<ChatMessageUiModel>> = combine(
        messageDao.observeMessagesForGroup(groupId),
        localIdentityDao.observeIdentity(),
        rosterMemberDao.observeMembers(groupId)
    ) { msgEntities, identity, members ->
        val selfFp = identity?.senderFp ?: 0L
        val memberMap = members.associateBy { it.senderFp }

        msgEntities.sortedBy { it.sentAtMs }.map { entity ->
            val isSelf = entity.senderFp == selfFp
            val member = memberMap[entity.senderFp]
            val peer = if (!isSelf && member == null) peerDao.getPeer(entity.senderFp) else null

            val alias = when {
                isSelf -> identity?.alias ?: "Self (Operator)"
                member != null -> member.alias
                peer != null -> peer.alias
                else -> "Node_${(entity.senderFp and 0xFFFF).toString(16).padStart(4, '0')}"
            }

            val tofuVerified = isSelf || (peer?.verified == true) || (member != null)

            ChatMessageUiModel(
                msgId = entity.msgId,
                groupId = entity.groupId,
                senderFp = entity.senderFp,
                senderAlias = alias,
                isSelf = isSelf,
                keyEpoch = entity.keyEpoch,
                sentAtMs = entity.sentAtMs,
                text = entity.body.text,
                sigOk = entity.sigOk,
                tofuVerified = tofuVerified,
                tier = entity.tier,
                score = entity.score,
                revealed = entity.revealed
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Unmasks a blurred Tier 2 or NOT_SCANNED message (AI-12).
     * Flips revealed = true directly in Room without re-running TFLite.
     */
    fun revealMessage(msgId: Long): kotlinx.coroutines.Job = viewModelScope.launch {
        try {
            messageDao.setRevealed(msgId, true)
            SecureLog.d(TAG, "Unmasked message $msgId (persisted revealed = true)")
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Failed to reveal message $msgId: ${t.message}")
        }
    }

    /**
     * Blocks similar messages by embedding and inserting into user_blocked_vectors (AI-9, AI-10).
     */
    fun blockSimilar(msgId: Long): kotlinx.coroutines.Job = viewModelScope.launch {
        val service = blockSimilarService
        if (service == null) {
            _snackbarEvent.emit("Moderation service unavailable")
            return@launch
        }
        when (val result = service.blockSimilar(msgId)) {
            is BlockSimilarResult.Success -> {
                _snackbarEvent.emit("Message blocked. Future similar messages will be quarantined.")
            }
            is BlockSimilarResult.BlocklistFull -> {
                _snackbarEvent.emit("Blocklist limit reached (maximum 500 vectors).")
            }
            is BlockSimilarResult.MessageNotFound -> {
                _snackbarEvent.emit("Message not found.")
            }
            is BlockSimilarResult.Failure -> {
                _snackbarEvent.emit("Failed to block message: ${result.reason}")
            }
        }
    }

    /**
     * Sends an outbound chat message via SendPipeline (T4.3, SEC-19).
     */
    fun sendMessage(text: String): kotlinx.coroutines.Job? {
        val trimmed = text.trim()
        val byteCount = trimmed.toByteArray(Charsets.UTF_8).size
        if (byteCount == 0 || byteCount > MessageLimits.MAX_PLAINTEXT_BYTES) {
            return null
        }

        return viewModelScope.launch {
            when (val result = sendPipeline.sendChatMessage(groupId, trimmed)) {
                is SendResult.Success -> {
                    SecureLog.d(TAG, "Sent message ${result.msgId} (${result.frameBytes.size} bytes)")
                }
                is SendResult.MessageTooLong -> {
                    _snackbarEvent.emit("Message exceeds 350-byte limit.")
                }
                is SendResult.GroupNotFound -> {
                    _snackbarEvent.emit("Group not found.")
                }
                is SendResult.IdentityNotFound -> {
                    _snackbarEvent.emit("Local identity not configured.")
                }
                is SendResult.InvalidTtl -> {
                    _snackbarEvent.emit("Invalid TTL value.")
                }
                is SendResult.Error -> {
                    _snackbarEvent.emit("Failed to send message: ${result.message}")
                }
            }
        }
    }
}
