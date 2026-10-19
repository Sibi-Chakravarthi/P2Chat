package com.p2chat.crypto.group

import com.p2chat.wire.BleConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary serializer and deserializer for group membership rosters (SEC-10, T1.8).
 */
object RosterCodec {

    private const val ROSTER_MAGIC: Byte = 0x52 // 'R'
    private const val ROSTER_WIRE_VERSION: Byte = 0x01

    fun encodeForSigning(
        groupId: Long,
        rosterVersion: Int,
        adminFp: Long,
        members: List<RosterMemberInfo>
    ): ByteArray {
        require(members.size <= BleConstants.MAX_ROSTER_MEMBERS) {
            "Roster cannot exceed ${BleConstants.MAX_ROSTER_MEMBERS} members (got ${members.size})"
        }

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeByte(ROSTER_MAGIC.toInt())
        dos.writeByte(ROSTER_WIRE_VERSION.toInt())
        dos.writeInt((groupId and 0xFFFFFFFFL).toInt())
        dos.writeInt(rosterVersion)
        dos.writeLong(adminFp)
        dos.writeByte(members.size)

        for (member in members) {
            dos.writeLong(member.senderFp)
            require(member.signingPub.size == 65) { "Signing pubkey must be 65 bytes" }
            dos.write(member.signingPub)
            require(member.ecdhPub.size == 65) { "ECDH pubkey must be 65 bytes" }
            dos.write(member.ecdhPub)

            val aliasBytes = member.alias.toByteArray(Charsets.UTF_8)
            require(aliasBytes.size <= 255) { "Alias too long" }
            dos.writeByte(aliasBytes.size)
            dos.write(aliasBytes)

            dos.writeBoolean(member.revoked)
        }

        dos.flush()
        return baos.toByteArray()
    }

    fun encode(signedRoster: SignedRoster): ByteArray {
        val payload = encodeForSigning(
            signedRoster.groupId,
            signedRoster.rosterVersion,
            signedRoster.adminFp,
            signedRoster.members
        )
        require(signedRoster.signature.size == BleConstants.ECDSA_SIGNATURE_SIZE_BYTES) {
            "Signature must be exactly 64 bytes"
        }
        val out = ByteArray(payload.size + BleConstants.ECDSA_SIGNATURE_SIZE_BYTES)
        System.arraycopy(payload, 0, out, 0, payload.size)
        System.arraycopy(signedRoster.signature, 0, out, payload.size, BleConstants.ECDSA_SIGNATURE_SIZE_BYTES)
        return out
    }

    fun decode(bytes: ByteArray): Result<SignedRoster> {
        if (bytes.size < 19 + BleConstants.ECDSA_SIGNATURE_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("Roster payload too short: ${bytes.size} bytes"))
        }

        return try {
            val payloadLen = bytes.size - BleConstants.ECDSA_SIGNATURE_SIZE_BYTES
            val signature = bytes.copyOfRange(payloadLen, bytes.size)

            val bais = ByteArrayInputStream(bytes, 0, payloadLen)
            val dis = DataInputStream(bais)

            val magic = dis.readByte()
            val version = dis.readByte()
            if (magic != ROSTER_MAGIC || version != ROSTER_WIRE_VERSION) {
                return Result.failure(IllegalArgumentException("Invalid roster magic: $magic, $version"))
            }

            val groupId = dis.readInt().toLong() and 0xFFFFFFFFL
            val rosterVersion = dis.readInt()
            val adminFp = dis.readLong()
            val memberCount = dis.readByte().toInt() and 0xFF

            if (memberCount > BleConstants.MAX_ROSTER_MEMBERS) {
                return Result.failure(IllegalArgumentException("Roster member count exceeds 16: $memberCount"))
            }

            val members = ArrayList<RosterMemberInfo>(memberCount)
            for (i in 0 until memberCount) {
                val senderFp = dis.readLong()
                val signingPub = ByteArray(65).also { dis.readFully(it) }
                val ecdhPub = ByteArray(65).also { dis.readFully(it) }

                val aliasLen = dis.readByte().toInt() and 0xFF
                val aliasBytes = ByteArray(aliasLen).also { dis.readFully(it) }
                val alias = String(aliasBytes, Charsets.UTF_8)
                val revoked = dis.readBoolean()

                members.add(RosterMemberInfo(senderFp, signingPub, ecdhPub, alias, revoked))
            }

            Result.success(SignedRoster(groupId, rosterVersion, adminFp, members, signature))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
