package com.p2chat.crypto.prim

import com.p2chat.wire.BleConstants
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SealedData(
    val nonce: ByteArray,
    val tag: ByteArray,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SealedData

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
}

/**
 * AES-256-GCM cryptographic primitive (SEC-3, SEC-4, T1.5).
 * Encapsulates authenticated encryption with associated data (AAD).
 */
object AesGcm {

    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32
    private const val NONCE_SIZE_BYTES = BleConstants.GCM_NONCE_SIZE_BYTES // 12 bytes
    private const val TAG_SIZE_BYTES = BleConstants.GCM_TAG_SIZE_BYTES     // 16 bytes
    private const val TAG_SIZE_BITS = TAG_SIZE_BYTES * 8

    private val secureRandom = SecureRandom()

    fun seal(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        explicitNonce: ByteArray? = null
    ): SealedData {
        if (key.size != KEY_SIZE_BYTES) {
            throw CryptoError.BadKey("AES-256 key must be 32 bytes, got ${key.size}")
        }
        val nonce = explicitNonce ?: ByteArray(NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        if (nonce.size != NONCE_SIZE_BYTES) {
            throw CryptoError.InvalidLength("GCM nonce must be 12 bytes, got ${nonce.size}")
        }

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        val output = cipher.doFinal(plaintext)
        // Java Cipher outputs ciphertext followed by the 16-byte GCM tag
        val ciphertextLen = output.size - TAG_SIZE_BYTES
        val ciphertext = output.copyOfRange(0, ciphertextLen)
        val tag = output.copyOfRange(ciphertextLen, output.size)

        return SealedData(nonce = nonce, tag = tag, ciphertext = ciphertext)
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray
    ): Result<ByteArray> {
        if (key.size != KEY_SIZE_BYTES) {
            return Result.failure(CryptoError.BadKey("AES-256 key must be 32 bytes, got ${key.size}"))
        }
        if (nonce.size != NONCE_SIZE_BYTES) {
            return Result.failure(CryptoError.InvalidLength("GCM nonce must be 12 bytes, got ${nonce.size}"))
        }
        if (tag.size != TAG_SIZE_BYTES) {
            return Result.failure(CryptoError.BadTag("GCM tag must be 16 bytes, got ${tag.size}"))
        }

        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val spec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }

            // Java AES/GCM expects ciphertext concatenated with the tag
            val combined = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
            System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

            val plaintext = cipher.doFinal(combined)
            Result.success(plaintext)
        } catch (e: AEADBadTagException) {
            Result.failure(CryptoError.AuthFailed("AES-GCM authentication failed (bad tag, corrupted ciphertext, or AAD mismatch)", e))
        } catch (e: Exception) {
            Result.failure(CryptoError.AuthFailed("Decryption failed: ${e.message}", e))
        }
    }
}
