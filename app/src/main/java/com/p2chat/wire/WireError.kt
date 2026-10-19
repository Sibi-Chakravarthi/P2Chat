package com.p2chat.wire

sealed class WireError(val message: String) {
    data class TruncatedFrame(val actualBytes: Int, val requiredBytes: Int) :
        WireError("Truncated frame: actual $actualBytes < minimum required $requiredBytes")

    data class UnknownPacketType(val code: Byte) :
        WireError("Unknown packet type: 0x%02X".format(code))

    data class InvalidTtl(val ttl: Int) :
        WireError("TTL out of valid range [1..15]: $ttl")

    data class CiphertextLengthOverrun(val declaredLen: Long, val availableBytes: Int) :
        WireError("Ciphertext length overrun: declared $declaredLen > available $availableBytes")

    data class OversizedFrame(val size: Int, val maxSize: Int) :
        WireError("Frame size $size exceeds maximum allowable $maxSize")

    data class MalformedStructure(val detail: String) :
        WireError("Malformed wire structure: $detail")
}
