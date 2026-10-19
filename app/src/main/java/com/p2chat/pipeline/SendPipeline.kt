package com.p2chat.pipeline

import com.p2chat.ble.DefaultMeshTransport
import com.p2chat.ble.MeshTransport
import com.p2chat.crypto.envelope.EnvelopeBuilder
import com.p2chat.crypto.envelope.MessageLimits
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.group.GroupDao
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.mesh.BufferedPacket
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.data.mesh.SeenMessageDao
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Result of attempting to send an outbound message (T4.3).
 */
sealed class SendResult {
    data class Success(
        val msgId: Long,
        val frameBytes: ByteArray,
        val message: MessageEntity
    ) : SendResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return msgId == other.msgId &&
                    frameBytes.contentEquals(other.frameBytes) &&
                    message == other.message
        }

        override fun hashCode(): Int {
            var result = msgId.hashCode()
            result = 31 * result + frameBytes.contentHashCode()
            result = 31 * result + message.hashCode()
            return result
        }
    }

    data class MessageTooLong(
        val actualBytes: Int,
        val maxAllowedBytes: Int = MessageLimits.MAX_PLAINTEXT_BYTES
    ) : SendResult()

    data class GroupNotFound(val groupId: Long) : SendResult()
    object IdentityNotFound : SendResult()
    data class InvalidTtl(val ttl: Int) : SendResult()
    data class Error(val message: String, val cause: Throwable? = null) : SendResult()
}

/**
 * Outbound SendPipeline coordinating message sealing, signing, local storage,
 * and mesh flooding (T4.3, SEC-3..7, SEC-11, SEC-19, NET-6, NET-7, AI-1).
 */
class SendPipeline(
    private val groupDao: GroupDao,
    private val localIdentityDao: LocalIdentityDao,
    private val messageDao: MessageDao,
    private val seenMessageDao: SeenMessageDao,
    private val bufferedPacketDao: BufferedPacketDao,
    private val keyVault: KeyVault,
    val meshTransport: MeshTransport = DefaultMeshTransport(),
    private val clock: Clock = Clock.System,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    companion object {
        private const val TAG = "SendPipeline"
        const val PRIORITY_CHAT = 0
        const val PRIORITY_CONTROL = 1
    }

    /**
     * Sends a chat message to the specified group (T4.3).
     *
     * Validates size against [MessageLimits.MAX_PLAINTEXT_BYTES] (350 B) before touching crypto or DB.
     * Encrypts under group's current epoch key, signs with local ECDSA key, inserts into local DB
     * as Tier 3 Clean (outbound AI filter never runs on sender, AI-1), stores in packet buffer,
     * and floods to connected peer sinks.
     */
    suspend fun sendChatMessage(
        groupId: Long,
        plaintext: String,
        ttl: Int = BleConstants.DEFAULT_TTL,
        explicitMsgId: Long? = null,
        explicitNonce: ByteArray? = null
    ): SendResult = withContext(Dispatchers.Default) {
        // 1. Validate plaintext length before touching crypto or DB
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        if (plaintextBytes.size > MessageLimits.MAX_PLAINTEXT_BYTES) {
            SecureLog.w(TAG, "Message rejected: size ${plaintextBytes.size} exceeds maximum ${MessageLimits.MAX_PLAINTEXT_BYTES} bytes")
            return@withContext SendResult.MessageTooLong(
                actualBytes = plaintextBytes.size,
                maxAllowedBytes = MessageLimits.MAX_PLAINTEXT_BYTES
            )
        }

        // Validate TTL bounds
        if (ttl < 1 || ttl > MessageLimits.MAX_TTL) {
            return@withContext SendResult.InvalidTtl(ttl)
        }

        // 2. Fetch group and identity
        val group = groupDao.getGroup(groupId)
            ?: return@withContext SendResult.GroupNotFound(groupId)

        val identity = localIdentityDao.getIdentity()
            ?: return@withContext SendResult.IdentityNotFound

        try {
            // 3. Unwrap group current epoch key and local signing private key
            val groupKey = keyVault.unwrap(group.wrappedCurrentKey)
            val signingPrivateKey = keyVault.unwrapPrivateKey(identity.wrappedSigningPriv)

            // 4. Generate msg_id and timestamp
            val msgId = explicitMsgId ?: secureRandom.nextLong()
            val sentAtMs = clock.currentTimeMillis()

            val header = PacketHeader(
                packetType = PacketType.CHAT,
                ttl = ttl,
                msgId = msgId,
                groupId = groupId,
                senderFp = identity.senderFp,
                keyEpoch = group.currentEpoch
            )

            // 5. Seal and sign wire packet (SEC-3..6)
            val frameBytes = EnvelopeBuilder.buildChatPacket(
                header = header,
                sentAtMs = sentAtMs,
                plaintext = plaintext,
                groupKey = groupKey,
                signingPrivateKey = signingPrivateKey,
                explicitNonce = explicitNonce
            )

            // 6. Insert own message into local Room table as Tier 3 (Clean) without AI filtering (AI-1)
            val messageEntity = MessageEntity(
                msgId = msgId,
                groupId = groupId,
                senderFp = identity.senderFp,
                keyEpoch = group.currentEpoch,
                sentAtMs = sentAtMs,
                receivedAtMs = sentAtMs,
                body = EncryptedBody.of(plaintext),
                sigOk = true,
                tier = 3,
                score = 0.0f,
                revealed = true
            )
            messageDao.insertMessage(messageEntity)

            // 7. Record msgId in seen_messages (NET-9)
            seenMessageDao.insertIfAbsent(msgId, sentAtMs)

            // 8. Store in packet_buffer for store-and-forward relaying
            val expiresAtMs = sentAtMs + MessageLimits.MAX_MESSAGE_AGE_MS
            val bufferedPacket = BufferedPacket(
                msgId = msgId,
                groupId = groupId,
                packetType = PacketType.CHAT.code.toInt(),
                frame = frameBytes,
                receivedTtl = ttl,
                storedAtMs = sentAtMs,
                expiresAtMs = expiresAtMs,
                priority = PRIORITY_CHAT
            )
            bufferedPacketDao.insertIfAbsent(bufferedPacket)

            // 9. Flood to connected peers via MeshTransport (NET-6)
            meshTransport.flood(frameBytes)

            SecureLog.d(TAG, "Sent message $msgId for group $groupId (epoch ${group.currentEpoch}, ${frameBytes.size} bytes)")
            SendResult.Success(msgId, frameBytes, messageEntity)
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Failed to send chat message: ${t.message}")
            SendResult.Error("Error sealing or sending message", t)
        }
    }

    /**
     * Dispatches control frames (KEY_ROTATE, ROSTER_SYNC, PAIRWISE_INVITE) with high buffer priority (T4.3).
     */
    suspend fun sendControlFrame(frameBytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
        if (frameBytes.size < 2) return@withContext false
        val header = PacketHeader.decode(frameBytes, 0).getOrNull() ?: return@withContext false
        val nowMs = clock.currentTimeMillis()

        // 1. Record in seen_messages
        seenMessageDao.insertIfAbsent(header.msgId, nowMs)

        // 2. Buffer with high priority (PRIORITY_CONTROL)
        val expiresAtMs = nowMs + MessageLimits.MAX_MESSAGE_AGE_MS
        val bufferedPacket = BufferedPacket(
            msgId = header.msgId,
            groupId = header.groupId,
            packetType = header.packetType.code.toInt(),
            frame = frameBytes,
            receivedTtl = header.ttl,
            storedAtMs = nowMs,
            expiresAtMs = expiresAtMs,
            priority = PRIORITY_CONTROL
        )
        bufferedPacketDao.insertIfAbsent(bufferedPacket)

        // 3. Flood to connected peers
        meshTransport.flood(frameBytes)
        SecureLog.d(TAG, "Dispatched control frame ${header.packetType} msgId ${header.msgId} (priority $PRIORITY_CONTROL)")
        true
    }

    /**
     * Dispatches a batch of control frames (e.g. key rotation fanout).
     */
    suspend fun sendControlFrames(frames: List<ByteArray>): Int = withContext(Dispatchers.Default) {
        var count = 0
        for (frame in frames) {
            if (sendControlFrame(frame)) {
                count++
            }
        }
        count
    }
}
