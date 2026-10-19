package com.p2chat.crypto.envelope

import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.wire.CoreBlob
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.WireCodec
import com.p2chat.wire.WireFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey

/**
 * Constructs sealed and signed wire packets (SEC-3, SEC-4, SEC-5, SEC-6, T1.6).
 * Ensures TTL is excluded from AAD and signature so intermediate relays can decrement it.
 */
object EnvelopeBuilder {

    /**
     * Builds and serializes an authenticated, encrypted wire frame.
     */
    fun buildChatPacket(
        header: PacketHeader,
        sentAtMs: Long,
        plaintext: String,
        groupKey: ByteArray,
        signingPrivateKey: PrivateKey,
        explicitNonce: ByteArray? = null
    ): ByteArray {
        val plaintextBytes = MessageLimits.validatePlaintext(plaintext)

        // 1. Inner payload: sent_at (8B BE int64 ms) + UTF-8 plaintext bytes
        val innerPayload = ByteBuffer.allocate(MessageLimits.INNER_TIMESTAMP_SIZE_BYTES + plaintextBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(sentAtMs)
            .put(plaintextBytes)
            .array()

        // 2. Compute 23-byte AAD (excludes ttl at header offset 1)
        val aad = header.computeAad()

        // 3. Encrypt inner payload under groupKey with AES-256-GCM
        val sealed = AesGcm.seal(
            key = groupKey,
            plaintext = innerPayload,
            aad = aad,
            explicitNonce = explicitNonce
        )

        // 4. Core blob: nonce (12B) + tag (16B) + ct_len (4B BE) + ct
        val coreBlob = CoreBlob(
            nonce = sealed.nonce,
            tag = sealed.tag,
            ciphertext = sealed.ciphertext
        )

        // 5. Raw 64-byte ECDSA signature over (AAD || CoreBlob)
        val signedData = aad + coreBlob.encode()
        val signature = Ecdsa.sign(signingPrivateKey, signedData)

        // 6. Complete WireFrame
        val wireFrame = WireFrame(
            header = header,
            coreBlob = coreBlob,
            signature = signature
        )

        return WireCodec.encode(wireFrame)
    }
}
