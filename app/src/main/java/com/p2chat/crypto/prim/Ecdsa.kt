package com.p2chat.crypto.prim

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.wire.BleConstants
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * ECDSA P-256 (secp256r1) with SHA-256 digital signature operations (SEC-6, T1.5).
 * Encodes signatures as raw 64-byte (r || s) on the wire, converting to/from ASN.1 DER.
 */
object Ecdsa {

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val RAW_SIG_SIZE = BleConstants.ECDSA_SIGNATURE_SIZE_BYTES // 64 bytes

    init {
        EcProvider.ensureBouncyCastle()
    }

    /**
     * Signs data using the ECDSA private key.
     * Returns raw 64-byte (r || s).
     */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        val derSignature = signer.sign()
        return derToRaw64(derSignature)
    }

    /**
     * Verifies a raw 64-byte (r || s) ECDSA signature over the provided data.
     */
    fun verify(publicKey: PublicKey, data: ByteArray, signatureRaw64: ByteArray): Boolean {
        if (signatureRaw64.size != RAW_SIG_SIZE) {
            return false
        }
        return try {
            val derSignature = raw64ToDer(signatureRaw64)
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(derSignature)
        } catch (e: Exception) {
            false
        }
    }

    fun verify(uncompressedPubkey: ByteArray, data: ByteArray, signatureRaw64: ByteArray): Boolean {
        return try {
            val pubKey = EcProvider.decodeUncompressedPublicKey(uncompressedPubkey)
            verify(pubKey, data, signatureRaw64)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts ASN.1 DER encoded ECDSA signature into raw 64-byte (r || s).
     */
    fun derToRaw64(der: ByteArray): ByteArray {
        val seq = ASN1Primitive.fromByteArray(der) as? ASN1Sequence
            ?: throw CryptoError.BadSignature("Invalid ASN.1 DER sequence")
        require(seq.size() >= 2) { "DER signature sequence must contain at least 2 integers" }

        val r = (seq.getObjectAt(0) as ASN1Integer).positiveValue
        val s = (seq.getObjectAt(1) as ASN1Integer).positiveValue

        val rBytes = toFixed32(r)
        val sBytes = toFixed32(s)

        val out = ByteArray(RAW_SIG_SIZE)
        System.arraycopy(rBytes, 0, out, 0, 32)
        System.arraycopy(sBytes, 0, out, 32, 32)
        return out
    }

    /**
     * Converts raw 64-byte (r || s) into ASN.1 DER encoded ECDSA signature.
     */
    fun raw64ToDer(raw64: ByteArray): ByteArray {
        require(raw64.size == RAW_SIG_SIZE) { "Raw signature must be 64 bytes, got ${raw64.size}" }

        val r = BigInteger(1, raw64.copyOfRange(0, 32))
        val s = BigInteger(1, raw64.copyOfRange(32, 64))

        val seq = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s)))
        return seq.encoded
    }

    private fun toFixed32(bigInt: BigInteger): ByteArray {
        val src = bigInt.toByteArray()
        val dest = ByteArray(32)
        if (src.size >= 32) {
            System.arraycopy(src, src.size - 32, dest, 0, 32)
        } else {
            System.arraycopy(src, 0, dest, 32 - src.size, src.size)
        }
        return dest
    }
}
