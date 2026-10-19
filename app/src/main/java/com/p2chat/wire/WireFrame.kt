package com.p2chat.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WireFrame(
    val header: PacketHeader,
    val coreBlob: CoreBlob,
    val signature: ByteArray
) {
    init {
        require(signature.size == BleConstants.ECDSA_SIGNATURE_SIZE_BYTES) {
            "Signature must be ${BleConstants.ECDSA_SIGNATURE_SIZE_BYTES} bytes, got ${signature.size}"
        }
    }

    val totalSize: Int
        get() = BleConstants.HEADER_SIZE_BYTES + BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES + coreBlob.ciphertext.size + BleConstants.ECDSA_SIGNATURE_SIZE_BYTES

    fun encode(): ByteArray {
        val encodedHeader = header.encode()
        val encodedBlob = coreBlob.encode()
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(encodedHeader)
        buffer.put(encodedBlob)
        buffer.put(signature)
        return buffer.array()
    }

    /**
     * Signed bytes: AAD (23 bytes) || CoreBlob (32 + ct.size bytes).
     * Excludes ttl (1B) from header.
     */
    fun computeSignedData(): ByteArray {
        val aad = header.computeAad()
        val blob = coreBlob.encode()
        val buffer = ByteBuffer.allocate(aad.size + blob.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(aad)
        buffer.put(blob)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WireFrame
        if (header != other.header) return false
        if (coreBlob != other.coreBlob) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + coreBlob.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
