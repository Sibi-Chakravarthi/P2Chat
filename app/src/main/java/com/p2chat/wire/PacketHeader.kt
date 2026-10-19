package com.p2chat.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PacketHeader(
    val packetType: PacketType,
    val ttl: Int,
    val msgId: Long,
    val groupId: Long,
    val senderFp: Long,
    val keyEpoch: Int
) {
    init {
        require(ttl in 0..255) { "TTL must fit in an unsigned byte (0..255), got $ttl" }
        require(groupId in 0..0xFFFFFFFFL) { "GroupID must fit in an unsigned 32-bit int, got $groupId" }
        require(keyEpoch in 0..0xFFFF) { "KeyEpoch must fit in an unsigned 16-bit int, got $keyEpoch" }
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(BleConstants.HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(packetType.code)
        buffer.put((ttl and 0xFF).toByte())
        buffer.putLong(msgId)
        buffer.putInt((groupId and 0xFFFFFFFFL).toInt())
        buffer.putLong(senderFp)
        buffer.putShort((keyEpoch and 0xFFFF).toShort())
        return buffer.array()
    }

    /**
     * Computes the 23-byte Authenticated Additional Data (AAD) for AES-256-GCM.
     * Excludes ttl (1B) at header offset 1 so relays can decrement ttl without invalidating GCM or signature.
     */
    fun computeAad(): ByteArray {
        val buffer = ByteBuffer.allocate(BleConstants.AAD_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(packetType.code)
        buffer.putLong(msgId)
        buffer.putInt((groupId and 0xFFFFFFFFL).toInt())
        buffer.putLong(senderFp)
        buffer.putShort((keyEpoch and 0xFFFF).toShort())
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray, offset: Int = 0): Result<PacketHeader> {
            if (bytes.size - offset < BleConstants.HEADER_SIZE_BYTES) {
                return Result.failure(
                    IllegalArgumentException(
                        WireError.TruncatedFrame(bytes.size - offset, BleConstants.HEADER_SIZE_BYTES).message
                    )
                )
            }
            val buffer = ByteBuffer.wrap(bytes, offset, BleConstants.HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            val typeByte = buffer.get()
            val packetType = PacketType.fromCode(typeByte)
                ?: return Result.failure(IllegalArgumentException(WireError.UnknownPacketType(typeByte).message))

            val ttl = buffer.get().toInt() and 0xFF
            if (ttl < 1 || ttl > BleConstants.MAX_TTL) {
                return Result.failure(IllegalArgumentException(WireError.InvalidTtl(ttl).message))
            }

            val msgId = buffer.long
            val groupId = buffer.int.toLong() and 0xFFFFFFFFL
            val senderFp = buffer.long
            val keyEpoch = buffer.short.toInt() and 0xFFFF

            return Result.success(
                PacketHeader(
                    packetType = packetType,
                    ttl = ttl,
                    msgId = msgId,
                    groupId = groupId,
                    senderFp = senderFp,
                    keyEpoch = keyEpoch
                )
            )
        }
    }
}
