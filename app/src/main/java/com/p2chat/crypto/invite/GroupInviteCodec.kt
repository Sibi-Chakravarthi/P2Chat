package com.p2chat.crypto.invite

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class GroupInviteData(
    val groupId: Long,
    val groupName: String,
    val groupKey: ByteArray,
    val keyEpoch: Int,
    val adminFp: Long,
    val adminSigningPub: ByteArray,
    val adminEcdhPub: ByteArray,
    val adminAlias: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupInviteData
        return groupId == other.groupId &&
                groupName == other.groupName &&
                groupKey.contentEquals(other.groupKey) &&
                keyEpoch == other.keyEpoch &&
                adminFp == other.adminFp &&
                adminSigningPub.contentEquals(other.adminSigningPub) &&
                adminEcdhPub.contentEquals(other.adminEcdhPub) &&
                adminAlias == other.adminAlias
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + groupName.hashCode()
        result = 31 * result + groupKey.contentHashCode()
        result = 31 * result + keyEpoch
        result = 31 * result + adminFp.hashCode()
        result = 31 * result + adminSigningPub.contentHashCode()
        result = 31 * result + adminEcdhPub.contentHashCode()
        result = 31 * result + adminAlias.hashCode()
        return result
    }
}

/**
 * Optical QR Code serializer/deserializer for direct in-person group invitations (SEC-9).
 */
object GroupInviteCodec {
    private const val PREFIX = "p2chat-invite:"

    fun encode(data: GroupInviteData): String {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeLong(data.groupId)
        dos.writeUTF(data.groupName)
        require(data.groupKey.size == 32) { "Group key must be 32 bytes" }
        dos.write(data.groupKey)
        dos.writeInt(data.keyEpoch)
        dos.writeLong(data.adminFp)
        require(data.adminSigningPub.size == 65) { "Signing pubkey must be 65 bytes" }
        dos.write(data.adminSigningPub)
        require(data.adminEcdhPub.size == 65) { "ECDH pubkey must be 65 bytes" }
        dos.write(data.adminEcdhPub)
        dos.writeUTF(data.adminAlias)
        dos.flush()
        return PREFIX + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun isInvite(qrString: String): Boolean = qrString.startsWith(PREFIX)

    fun decode(qrString: String): Result<GroupInviteData> {
        if (!isInvite(qrString)) {
            return Result.failure(IllegalArgumentException("Not a valid P2Chat group invite"))
        }
        return try {
            val base64 = qrString.removePrefix(PREFIX)
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val groupId = dis.readLong()
            val groupName = dis.readUTF()
            val groupKey = ByteArray(32).also { dis.readFully(it) }
            val keyEpoch = dis.readInt()
            val adminFp = dis.readLong()
            val adminSigningPub = ByteArray(65).also { dis.readFully(it) }
            val adminEcdhPub = ByteArray(65).also { dis.readFully(it) }
            val adminAlias = dis.readUTF()
            Result.success(
                GroupInviteData(
                    groupId = groupId,
                    groupName = groupName,
                    groupKey = groupKey,
                    keyEpoch = keyEpoch,
                    adminFp = adminFp,
                    adminSigningPub = adminSigningPub,
                    adminEcdhPub = adminEcdhPub,
                    adminAlias = adminAlias
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
