package com.p2chat.crypto.invite

import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.group.SignedRoster
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.wire.BleConstants
import com.p2chat.wire.CoreBlob
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import com.p2chat.wire.WireFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey

data class InvitePayload(
    val groupId: Long,
    val groupKey: ByteArray,
    val keyEpoch: Int,
    val signedRoster: SignedRoster
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InvitePayload

        if (groupId != other.groupId) return false
        if (!groupKey.contentEquals(other.groupKey)) return false
        if (keyEpoch != other.keyEpoch) return false
        if (signedRoster != other.signedRoster) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + groupKey.contentHashCode()
        result = 31 * result + keyEpoch
        result = 31 * result + signedRoster.hashCode()
        return result
    }
}

/**
 * Service for constructing and unpacking PAIRWISE_INVITE control packets (SEC-2, SEC-7, SEC-9, T1.9).
 */
object InviteService {

    fun serializeInvitePayload(sentAtMs: Long, invite: InvitePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeLong(sentAtMs)
        dos.writeInt((invite.groupId and 0xFFFFFFFFL).toInt())
        dos.writeShort(invite.keyEpoch and 0xFFFF)
        require(invite.groupKey.size == 32) { "Group key must be 32 bytes" }
        dos.write(invite.groupKey)

        val rosterBytes = RosterCodec.encode(invite.signedRoster)
        dos.writeInt(rosterBytes.size)
        dos.write(rosterBytes)

        dos.flush()
        return baos.toByteArray()
    }

    fun deserializeInvitePayload(bytes: ByteArray): Pair<Long, InvitePayload> {
        val bais = ByteArrayInputStream(bytes)
        val dis = DataInputStream(bais)

        val sentAtMs = dis.readLong()
        val groupId = dis.readInt().toLong() and 0xFFFFFFFFL
        val keyEpoch = dis.readShort().toInt() and 0xFFFF
        val groupKey = ByteArray(32).also { dis.readFully(it) }

        val rosterLen = dis.readInt()
        val rosterBytes = ByteArray(rosterLen).also { dis.readFully(it) }
        val signedRoster = RosterCodec.decode(rosterBytes).getOrThrow()

        return Pair(sentAtMs, InvitePayload(groupId, groupKey, keyEpoch, signedRoster))
    }

    fun buildInvitePacket(
        adminFp: Long,
        adminSigningPrivateKey: PrivateKey,
        inviteePairwiseChannel: PairwiseChannel,
        invitePayload: InvitePayload,
        sentAtMs: Long,
        msgId: Long = java.util.concurrent.ThreadLocalRandom.current().nextLong()
    ): ByteArray {
        val innerPayload = serializeInvitePayload(sentAtMs, invitePayload)

        val header = PacketHeader(
            packetType = PacketType.PAIRWISE_INVITE,
            ttl = BleConstants.DEFAULT_TTL,
            msgId = msgId,
            groupId = invitePayload.groupId,
            senderFp = adminFp,
            keyEpoch = invitePayload.keyEpoch
        )

        val aad = header.computeAad()
        val sealed = inviteePairwiseChannel.seal(innerPayload, aad)

        val coreBlob = CoreBlob(sealed.nonce, sealed.tag, sealed.ciphertext)
        val signedData = aad + coreBlob.encode()
        val signature = Ecdsa.sign(adminSigningPrivateKey, signedData)

        val frame = WireFrame(header, coreBlob, signature)
        return WireCodec.encode(frame)
    }

    fun openInvitePacket(
        frameBytes: ByteArray,
        adminSigningPubkey: ByteArray,
        inviteePairwiseChannel: PairwiseChannel
    ): Result<Pair<PacketHeader, InvitePayload>> {
        val wireFrame = WireCodec.decode(frameBytes).getOrElse { return Result.failure(it) }
        if (wireFrame.header.packetType != PacketType.PAIRWISE_INVITE) {
            return Result.failure(IllegalArgumentException("Expected PAIRWISE_INVITE packet, got ${wireFrame.header.packetType}"))
        }

        // 1. Verify admin signature before decrypting
        val aad = wireFrame.header.computeAad()
        val coreBlobBytes = wireFrame.coreBlob.encode()
        val signedData = aad + coreBlobBytes
        val sigValid = Ecdsa.verify(adminSigningPubkey, signedData, wireFrame.signature)
        if (!sigValid) {
            return Result.failure(com.p2chat.crypto.prim.CryptoError.BadSignature("Invalid admin signature on invite packet"))
        }

        // 2. Decrypt with invitee's pairwise channel
        val openResult = inviteePairwiseChannel.open(
            nonce = wireFrame.coreBlob.nonce,
            ciphertext = wireFrame.coreBlob.ciphertext,
            tag = wireFrame.coreBlob.tag,
            aad = aad
        )

        if (openResult.isFailure) {
            return Result.failure(openResult.exceptionOrNull()!!)
        }

        val decryptedPayload = openResult.getOrThrow()
        return try {
            val (_, invitePayload) = deserializeInvitePayload(decryptedPayload)
            Result.success(Pair(wireFrame.header, invitePayload))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
