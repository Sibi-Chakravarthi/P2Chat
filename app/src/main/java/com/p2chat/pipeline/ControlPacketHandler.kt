package com.p2chat.pipeline

import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.rotation.KeyRotatePayload
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.util.SecureLog
import com.p2chat.wire.PacketType

/**
 * Handles control packets (KEY_ROTATE, ROSTER_SYNC, PAIRWISE_INVITE) dispatched by ReceivePipeline (T4.2).
 */
class ControlPacketHandler(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private var onEpochRotated: (suspend (groupId: Long, newEpoch: Int) -> Unit)? = null
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
                SecureLog.d(TAG, "PAIRWISE_INVITE received for group ${verdict.header.groupId}")
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

    suspend fun notifyEpochRotated(groupId: Long, newEpoch: Int) {
        onEpochRotated?.invoke(groupId, newEpoch)
    }
}
