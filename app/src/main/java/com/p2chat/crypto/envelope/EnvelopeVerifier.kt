package com.p2chat.crypto.envelope

import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.util.Clock
import com.p2chat.wire.WireCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PublicKey

/**
 * EnvelopeVerifier enforcing cryptographic and protocol integrity boundaries (T1.6, SEC-3..6, SEC-14).
 * Enforces strict verification ordering:
 * 1. Frame bounds & TTL check
 * 2. ECDSA signature verification BEFORE attempting decryption (SEC-6), if senderSigningPubkey is provided
 * 3. AES-256-GCM decryption with AAD (authenticates ciphertext + AAD header)
 * 4. Timestamp freshness (SEC-14)
 */
object EnvelopeVerifier {

    fun verifyAndOpen(
        frameBytes: ByteArray,
        groupKey: ByteArray,
        senderSigningPubkey: ByteArray?,
        clock: Clock = Clock.System
    ): PacketVerdict {
        // 1. Minimum frame validation
        if (frameBytes.size < 2) {
            return PacketVerdict.DropMalformed
        }

        // 2. TTL check (offset 1): must be in 1..15
        val ttl = frameBytes[1].toInt() and 0xFF
        if (ttl < 1 || ttl > MessageLimits.MAX_TTL) {
            return PacketVerdict.DropTtl
        }

        // 3. Decode frame format
        val wireFrame = WireCodec.decode(frameBytes).getOrElse {
            return PacketVerdict.DropMalformed
        }

        val aad = wireFrame.header.computeAad()

        // 4. ECDSA signature verification BEFORE attempting decryption (SEC-6), if sender pubkey is provided
        if (senderSigningPubkey != null) {
            val coreBlobBytes = wireFrame.coreBlob.encode()
            val signedData = aad + coreBlobBytes

            val sigValid = Ecdsa.verify(senderSigningPubkey, signedData, wireFrame.signature)
            if (!sigValid) {
                return PacketVerdict.DropBadSignature
            }
        }

        // 5. AES-256-GCM decryption with AAD
        val openResult = AesGcm.open(
            key = groupKey,
            nonce = wireFrame.coreBlob.nonce,
            ciphertext = wireFrame.coreBlob.ciphertext,
            tag = wireFrame.coreBlob.tag,
            aad = aad
        )

        if (openResult.isFailure) {
            return PacketVerdict.DropAuthFail
        }

        val innerPayload = openResult.getOrThrow()
        if (innerPayload.size < MessageLimits.INNER_TIMESTAMP_SIZE_BYTES) {
            return PacketVerdict.DropMalformed
        }

        // 6. Extract sent_at timestamp and plaintext
        val sentAtMs = ByteBuffer.wrap(innerPayload, 0, MessageLimits.INNER_TIMESTAMP_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long

        val plaintextBytes = innerPayload.copyOfRange(MessageLimits.INNER_TIMESTAMP_SIZE_BYTES, innerPayload.size)
        val plaintext = String(plaintextBytes, Charsets.UTF_8)

        // 7. Check timestamp freshness (SEC-14)
        val nowMs = clock.currentTimeMillis()
        if (sentAtMs > nowMs + MessageLimits.MAX_CLOCK_SKEW_FUTURE_MS) {
            return PacketVerdict.DropStale
        }
        if (sentAtMs < nowMs - MessageLimits.MAX_MESSAGE_AGE_MS) {
            return PacketVerdict.DropStale
        }

        return PacketVerdict.Accept(
            header = wireFrame.header,
            sentAtMs = sentAtMs,
            plaintext = plaintext
        )
    }

    fun openWithGroupKeyOnly(
        frameBytes: ByteArray,
        groupKey: ByteArray,
        clock: Clock = Clock.System
    ): PacketVerdict = verifyAndOpen(frameBytes, groupKey, senderSigningPubkey = null, clock = clock)

    fun verifyAndOpen(
        frameBytes: ByteArray,
        groupKey: ByteArray,
        senderSigningPubkey: PublicKey,
        clock: Clock = Clock.System
    ): PacketVerdict {
        val pubBytes = com.p2chat.crypto.keys.EcProvider.encodeUncompressedPublicKey(senderSigningPubkey)
        return verifyAndOpen(frameBytes, groupKey, pubBytes, clock)
    }
}
