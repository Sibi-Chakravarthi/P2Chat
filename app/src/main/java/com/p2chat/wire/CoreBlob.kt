package com.p2chat.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CoreBlob(
    val nonce: ByteArray,
    val tag: ByteArray,
    val ciphertext: ByteArray
) {
    init {
        require(nonce.size == BleConstants.GCM_NONCE_SIZE_BYTES) {
            "Nonce must be ${BleConstants.GCM_NONCE_SIZE_BYTES} bytes, got ${nonce.size}"
        }
        require(tag.size == BleConstants.GCM_TAG_SIZE_BYTES) {
            "Tag must be ${BleConstants.GCM_TAG_SIZE_BYTES} bytes, got ${tag.size}"
        }
    }

    fun encode(): ByteArray {
        val totalSize = BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES + ciphertext.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(nonce)
        buffer.put(tag)
        buffer.putInt(ciphertext.size)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoreBlob
        if (!nonce.contentEquals(other.nonce)) return false
        if (!tag.contentEquals(other.tag)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Result<CoreBlob> {
            if (length < BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES) {
                return Result.failure(
                    IllegalArgumentException(
                        WireError.TruncatedFrame(length, BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES).message
                    )
                )
            }

            val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
            val nonce = ByteArray(BleConstants.GCM_NONCE_SIZE_BYTES)
            buffer.get(nonce)

            val tag = ByteArray(BleConstants.GCM_TAG_SIZE_BYTES)
            buffer.get(tag)

            val ctLen = buffer.int.toLong() and 0xFFFFFFFFL
            val remaining = buffer.remaining()

            if (ctLen > remaining) {
                return Result.failure(
                    IllegalArgumentException(
                        WireError.CiphertextLengthOverrun(ctLen, remaining).message
                    )
                )
            }

            val ciphertext = ByteArray(ctLen.toInt())
            buffer.get(ciphertext)

            return Result.success(CoreBlob(nonce = nonce, tag = tag, ciphertext = ciphertext))
        }
    }
}
