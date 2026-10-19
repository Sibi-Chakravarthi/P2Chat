package com.p2chat.crypto.prim

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * HKDF-SHA256 key derivation function (RFC 5869).
 * Used for pairwise secret derivation and epoch key expansion.
 */
object Hkdf {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * HKDF-Extract(salt, ikm) -> PRK
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(prk, info, length) -> OKM
     */
    fun expand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "HKDF output length $length is out of bounds" }

        val actualInfo = info ?: ByteArray(0)
        val n = ceil(length.toDouble() / HASH_LEN).toInt()
        val okm = ByteArray(length)

        var tPrev = ByteArray(0)
        var bytesWritten = 0

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        for (i in 1..n) {
            mac.reset()
            if (tPrev.isNotEmpty()) {
                mac.update(tPrev)
            }
            if (actualInfo.isNotEmpty()) {
                mac.update(actualInfo)
            }
            mac.update(i.toByte())
            tPrev = mac.doFinal()

            val toCopy = minOf(HASH_LEN, length - bytesWritten)
            System.arraycopy(tPrev, 0, okm, bytesWritten, toCopy)
            bytesWritten += toCopy
        }

        return okm
    }

    /**
     * Full HKDF: Extract then Expand.
     */
    fun deriveKey(ikm: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int = 32): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
