package com.p2chat.crypto.invite

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class QrPayload(
    val signingPub: ByteArray,
    val ecdhPub: ByteArray,
    val alias: String
) {
    init {
        require(signingPub.size == 65 && signingPub[0] == 0x04.toByte()) { "Signing pubkey must be 65-byte uncompressed EC key" }
        require(ecdhPub.size == 65 && ecdhPub[0] == 0x04.toByte()) { "ECDH pubkey must be 65-byte uncompressed EC key" }
        require(alias.isNotEmpty() && alias.length <= 32) { "Alias must be between 1 and 32 characters" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QrPayload

        if (!signingPub.contentEquals(other.signingPub)) return false
        if (!ecdhPub.contentEquals(other.ecdhPub)) return false
        if (alias != other.alias) return false

        return true
    }

    override fun hashCode(): Int {
        var result = signingPub.contentHashCode()
        result = 31 * result + ecdhPub.contentHashCode()
        result = 31 * result + alias.hashCode()
        return result
    }
}

/**
 * Encodes and decodes identity QR payloads for air-gapped invites and verification (ID-4, T1.9).
 * Format: Magic (2B: 'P','2') + Version (1B: 0x01) + SigningPub (65B) + EcdhPub (65B) + AliasLen (1B) + Alias (UTF-8).
 * Total size: 134..165 bytes (well within QR Version 4-5 capacity).
 */
object QrPayloadCodec {

    private const val MAGIC_0: Byte = 0x50 // 'P'
    private const val MAGIC_1: Byte = 0x32 // '2'
    private const val VERSION: Byte = 0x01

    fun encode(payload: QrPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeByte(MAGIC_0.toInt())
        dos.writeByte(MAGIC_1.toInt())
        dos.writeByte(VERSION.toInt())

        dos.write(payload.signingPub)
        dos.write(payload.ecdhPub)

        val aliasBytes = payload.alias.toByteArray(Charsets.UTF_8)
        dos.writeByte(aliasBytes.size)
        dos.write(aliasBytes)

        dos.flush()
        return baos.toByteArray()
    }

    fun decode(bytes: ByteArray): Result<QrPayload> {
        if (bytes.size < 3 + 65 + 65 + 1) {
            return Result.failure(IllegalArgumentException("QR payload too short: ${bytes.size} bytes"))
        }

        return try {
            val bais = ByteArrayInputStream(bytes)
            val dis = DataInputStream(bais)

            val m0 = dis.readByte()
            val m1 = dis.readByte()
            val ver = dis.readByte()
            if (m0 != MAGIC_0 || m1 != MAGIC_1 || ver != VERSION) {
                return Result.failure(IllegalArgumentException("Invalid QR payload header magic: $m0 $m1 $ver"))
            }

            val signingPub = ByteArray(65).also { dis.readFully(it) }
            val ecdhPub = ByteArray(65).also { dis.readFully(it) }

            val aliasLen = dis.readByte().toInt() and 0xFF
            val aliasBytes = ByteArray(aliasLen).also { dis.readFully(it) }
            val alias = String(aliasBytes, Charsets.UTF_8)

            Result.success(QrPayload(signingPub = signingPub, ecdhPub = ecdhPub, alias = alias))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun encodeToBase64(payload: QrPayload): String {
        val bytes = encode(payload)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decodeFromBase64(base64Str: String): Result<QrPayload> {
        return try {
            val bytes = java.util.Base64.getUrlDecoder().decode(base64Str)
            decode(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
