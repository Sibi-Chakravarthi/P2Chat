package com.p2chat.crypto.prim

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import java.security.PrivateKey
import java.security.PublicKey

/**
 * ECDH (Elliptic Curve Diffie-Hellman) key agreement over NIST P-256 (secp256r1).
 * Yields a 32-byte shared secret (SEC-2, SEC-7).
 */
object Ecdh {

    fun deriveSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        return SoftwareEcProvider.deriveSharedSecret(privateKey, peerPublicKey)
    }

    fun deriveSecret(privateKey: PrivateKey, peerUncompressedPubkey: ByteArray): ByteArray {
        val pubKey = EcProvider.decodeUncompressedPublicKey(peerUncompressedPubkey)
        return deriveSecret(privateKey, pubKey)
    }
}
