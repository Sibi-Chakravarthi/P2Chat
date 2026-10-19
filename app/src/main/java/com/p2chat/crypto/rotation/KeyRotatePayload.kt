package com.p2chat.crypto.rotation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Payload carried inside PACKET_TYPE_KEY_ROTATE (SEC-7, SEC-11, T1.11).
 * Encrypted under each recipient's pairwise secret and signed by the admin.
 */
data class KeyRotatePayload(
    val groupId: Long,
    val keyEpoch: Int,
    val newGroupKey: ByteArray,
    val removedSenderFp: Long? = null,
    val rosterVersion: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeyRotatePayload

        if (groupId != other.groupId) return false
        if (keyEpoch != other.keyEpoch) return false
        if (!newGroupKey.contentEquals(other.newGroupKey)) return false
        if (removedSenderFp != other.removedSenderFp) return false
        if (rosterVersion != other.rosterVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + keyEpoch
        result = 31 * result + newGroupKey.contentHashCode()
        result = 31 * result + (removedSenderFp?.hashCode() ?: 0)
        result = 31 * result + rosterVersion
        return result
    }

    companion object {
        fun serialize(sentAtMs: Long, payload: KeyRotatePayload): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            dos.writeLong(sentAtMs)
            dos.writeInt((payload.groupId and 0xFFFFFFFFL).toInt())
            dos.writeShort(payload.keyEpoch and 0xFFFF)
            require(payload.newGroupKey.size == 32) { "New group key must be 32 bytes, got ${payload.newGroupKey.size}" }
            dos.write(payload.newGroupKey)
            dos.writeLong(payload.removedSenderFp ?: 0L)
            dos.writeInt(payload.rosterVersion)

            dos.flush()
            return baos.toByteArray()
        }

        fun deserialize(bytes: ByteArray): Pair<Long, KeyRotatePayload> {
            val bais = ByteArrayInputStream(bytes)
            val dis = DataInputStream(bais)

            val sentAtMs = dis.readLong()
            val groupId = dis.readInt().toLong() and 0xFFFFFFFFL
            val keyEpoch = dis.readShort().toInt() and 0xFFFF
            val newGroupKey = ByteArray(32).also { dis.readFully(it) }
            val removedFpRaw = dis.readLong()
            val removedSenderFp = if (removedFpRaw != 0L) removedFpRaw else null
            val rosterVersion = dis.readInt()

            return Pair(
                sentAtMs,
                KeyRotatePayload(
                    groupId = groupId,
                    keyEpoch = keyEpoch,
                    newGroupKey = newGroupKey,
                    removedSenderFp = removedSenderFp,
                    rosterVersion = rosterVersion
                )
            )
        }
    }
}
