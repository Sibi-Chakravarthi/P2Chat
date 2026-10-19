package com.p2chat.crypto.invite

import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.Ecdh
import com.p2chat.crypto.prim.Hkdf
import com.p2chat.crypto.prim.SealedData
import com.p2chat.wire.BleConstants
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Direct point-to-point pairwise cryptographic channel (SEC-2, SEC-7, T1.9).
 * Derives AES-256-GCM session key using ECDH + HKDF-SHA256:
 * - Salt: "P2Chat-v1-Pairwise-Salt"
 * - Info: "P2Chat-v1-Pairwise-AES256GCM"
 */
class PairwiseChannel(
    val pairwiseKey: ByteArray
) {
    init {
        require(pairwiseKey.size == 32) { "Pairwise key must be 32 bytes" }
    }

    fun seal(plaintext: ByteArray, aad: ByteArray, explicitNonce: ByteArray? = null): SealedData {
        return AesGcm.seal(
            key = pairwiseKey,
            plaintext = plaintext,
            aad = aad,
            explicitNonce = explicitNonce
        )
    }

    fun open(nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): Result<ByteArray> {
        return AesGcm.open(
            key = pairwiseKey,
            nonce = nonce,
            ciphertext = ciphertext,
            tag = tag,
            aad = aad
        )
    }

    companion object {
        fun derive(
            myEcdhPrivateKey: PrivateKey,
            peerEcdhPublicKey: PublicKey
        ): PairwiseChannel {
            val ecdhRawSecret = Ecdh.deriveSecret(myEcdhPrivateKey, peerEcdhPublicKey)
            val salt = BleConstants.HKDF_PAIRWISE_SALT.toByteArray(Charsets.UTF_8)
            val info = BleConstants.HKDF_PAIRWISE_INFO.toByteArray(Charsets.UTF_8)
            val derivedKey = Hkdf.deriveKey(ikm = ecdhRawSecret, salt = salt, info = info, length = 32)
            return PairwiseChannel(derivedKey)
        }

        fun derive(
            myEcdhPrivateKey: PrivateKey,
            peerUncompressedPubkey: ByteArray
        ): PairwiseChannel {
            val peerPubKey = com.p2chat.crypto.keys.EcProvider.decodeUncompressedPublicKey(peerUncompressedPubkey)
            return derive(myEcdhPrivateKey, peerPubKey)
        }
    }
}
