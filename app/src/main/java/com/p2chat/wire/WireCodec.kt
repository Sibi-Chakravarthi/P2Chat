package com.p2chat.wire

/**
 * P2Chat Wire Codec.
 * Encodes and decodes network frames according to docs/WIRE_SPEC.md.
 * Pure serialization logic without cryptographic operations.
 */
object WireCodec {
    const val MIN_FRAME_SIZE_BYTES: Int =
        BleConstants.HEADER_SIZE_BYTES + BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES + BleConstants.ECDSA_SIGNATURE_SIZE_BYTES // 120 bytes

    fun encode(frame: WireFrame): ByteArray {
        return frame.encode()
    }

    fun decode(bytes: ByteArray): Result<WireFrame> {
        if (bytes.size < MIN_FRAME_SIZE_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    WireError.TruncatedFrame(bytes.size, MIN_FRAME_SIZE_BYTES).message
                )
            )
        }

        if (bytes.size > BleConstants.MAX_FRAME_SIZE_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    WireError.OversizedFrame(bytes.size, BleConstants.MAX_FRAME_SIZE_BYTES).message
                )
            )
        }

        // 1. Decode fixed header (24 bytes)
        val headerResult = PacketHeader.decode(bytes, 0)
        if (headerResult.isFailure) {
            return Result.failure(headerResult.exceptionOrNull()!!)
        }
        val header = headerResult.getOrThrow()

        // 2. Extract signature (last 64 bytes)
        val sigOffset = bytes.size - BleConstants.ECDSA_SIGNATURE_SIZE_BYTES
        val signature = bytes.copyOfRange(sigOffset, bytes.size)

        // 3. Decode core blob between header and signature
        val blobLength = sigOffset - BleConstants.HEADER_SIZE_BYTES
        val coreBlobResult = CoreBlob.decode(bytes, BleConstants.HEADER_SIZE_BYTES, blobLength)
        if (coreBlobResult.isFailure) {
            return Result.failure(coreBlobResult.exceptionOrNull()!!)
        }
        val coreBlob = coreBlobResult.getOrThrow()

        // Verify total frame length matches expected size
        val expectedTotal = BleConstants.HEADER_SIZE_BYTES + BleConstants.CORE_BLOB_PREFIX_SIZE_BYTES + coreBlob.ciphertext.size + BleConstants.ECDSA_SIGNATURE_SIZE_BYTES
        if (bytes.size != expectedTotal) {
            return Result.failure(
                IllegalArgumentException(
                    WireError.MalformedStructure("Frame size mismatch: buffer has ${bytes.size} bytes, blob declared $expectedTotal bytes").message
                )
            )
        }

        return Result.success(
            WireFrame(
                header = header,
                coreBlob = coreBlob,
                signature = signature
            )
        )
    }
}
