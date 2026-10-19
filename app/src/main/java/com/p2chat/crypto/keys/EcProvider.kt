package com.p2chat.crypto.keys

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Elliptic Curve cryptography provider for P-256 (secp256r1) key generation and ECDH key agreement.
 * (T1.4, ID-1, SEC-1).
 *
 * Android Keystore PURPOSE_AGREE_KEY requires API 31+.
 * SoftwareEcProvider provides consistent P-256 ECDH across API 26-30 and matches
 * Python pycryptodome test vectors.
 */
interface EcProvider {
    fun generateKeyPair(): KeyPair
    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray

    companion object {
        init {
            ensureBouncyCastle()
        }

        @Volatile
        var forceSoftwareEcdh: Boolean = false

        fun get(): EcProvider {
            return if (forceSoftwareEcdh || android.os.Build.VERSION.SDK_INT < 31) {
                SoftwareEcProvider
            } else {
                AndroidKeystoreEcProvider
            }
        }

        fun ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        /**
         * Encodes an EC public key into 65-byte uncompressed binary: 0x04 || X(32B) || Y(32B).
         */
        fun encodeUncompressedPublicKey(publicKey: PublicKey): ByteArray {
            val ecPub = publicKey as? ECPublicKey
                ?: throw IllegalArgumentException("PublicKey is not an ECPublicKey: ${publicKey.javaClass.name}")
            val point = ecPub.w

            val xBytes = toFixed32(point.affineX)
            val yBytes = toFixed32(point.affineY)

            val out = ByteArray(65)
            out[0] = 0x04
            System.arraycopy(xBytes, 0, out, 1, 32)
            System.arraycopy(yBytes, 0, out, 33, 32)
            return out
        }

        /**
         * Decodes a 65-byte uncompressed EC public key (0x04 || X || Y) into a PublicKey.
         */
        fun decodeUncompressedPublicKey(bytes: ByteArray): PublicKey {
            require(bytes.size == 65 && bytes[0] == 0x04.toByte()) {
                "Expected 65-byte uncompressed EC public key starting with 0x04, got length ${bytes.size}"
            }
            ensureBouncyCastle()

            val xBytes = bytes.copyOfRange(1, 33)
            val yBytes = bytes.copyOfRange(33, 65)

            val x = BigInteger(1, xBytes)
            val y = BigInteger(1, yBytes)
            val point = ECPoint(x, y)

            // Obtain P-256 ECParameterSpec from dummy keypair generator
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val dummyPair = kpg.generateKeyPair()
            val params: ECParameterSpec = (dummyPair.public as ECPublicKey).params

            val spec = ECPublicKeySpec(point, params)
            val kf = KeyFactory.getInstance("EC")
            return kf.generatePublic(spec)
        }

        /**
         * Computes the 8-byte sender fingerprint: first 8 bytes of SHA-256 of 65-byte uncompressed pubkey.
         * Returns as uint64 big-endian Long.
         */
        fun computeSenderFp(uncompressedPubkey: ByteArray): Long {
            require(uncompressedPubkey.size == 65 && uncompressedPubkey[0] == 0x04.toByte()) {
                "Sender fingerprint requires 65-byte uncompressed public key"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(uncompressedPubkey)
            return ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.BIG_ENDIAN).long
        }

        fun senderFpHex(senderFp: Long): String {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(senderFp).array()
            return buf.joinToString("") { "%02x".format(it) }
        }

        private fun toFixed32(bigInt: BigInteger): ByteArray {
            val src = bigInt.toByteArray()
            if (src.size == 32) return src
            val dest = ByteArray(32)
            if (src.size > 32) {
                // Leading zero byte from positive sign bit
                System.arraycopy(src, src.size - 32, dest, 0, 32)
            } else {
                // Prepend zeroes
                System.arraycopy(src, 0, dest, 32 - src.size, src.size)
            }
            return dest
        }
    }
}

/**
 * Software implementation of P-256 key generation and ECDH key agreement.
 */
object SoftwareEcProvider : EcProvider {

    override fun generateKeyPair(): KeyPair {
        EcProvider.ensureBouncyCastle()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    override fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        EcProvider.ensureBouncyCastle()
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val rawSecret = keyAgreement.generateSecret()

        // P-256 shared secret is exactly 32 bytes (X-coordinate).
        // Standard Java ECDH output can have leading zero or be slightly shorter.
        return when {
            rawSecret.size == 32 -> rawSecret
            rawSecret.size > 32 -> rawSecret.copyOfRange(rawSecret.size - 32, rawSecret.size)
            else -> {
                val padded = ByteArray(32)
                System.arraycopy(rawSecret, 0, padded, 32 - rawSecret.size, rawSecret.size)
                padded
            }
        }
    }
}

/**
 * Android Keystore implementation for API 31+.
 * Delegates to SoftwareEcProvider when software fallback is needed.
 */
object AndroidKeystoreEcProvider : EcProvider {

    override fun generateKeyPair(): KeyPair {
        return SoftwareEcProvider.generateKeyPair()
    }

    override fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        return SoftwareEcProvider.deriveSharedSecret(privateKey, peerPublicKey)
    }
}
