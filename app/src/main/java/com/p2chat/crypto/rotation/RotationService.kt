package com.p2chat.crypto.rotation

import com.p2chat.crypto.envelope.EnvelopeVerifier
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.epoch.EpochEvaluation
import com.p2chat.crypto.epoch.EpochPolicy
import com.p2chat.crypto.epoch.EpochState
import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.group.RosterMemberInfo
import com.p2chat.crypto.group.SignedRoster
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.prim.AesGcm
import com.p2chat.crypto.prim.CryptoError
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.util.Clock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.CoreBlob
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import com.p2chat.wire.WireFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom

data class MemberRemovalResult(
    val newEpoch: Int,
    val newGroupKey: ByteArray,
    val updatedRoster: SignedRoster,
    val keyRotatePackets: List<ByteArray>,
    val rosterSyncPacket: ByteArray,
    val removeMemberPacket: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemberRemovalResult

        if (newEpoch != other.newEpoch) return false
        if (!newGroupKey.contentEquals(other.newGroupKey)) return false
        if (updatedRoster != other.updatedRoster) return false
        if (keyRotatePackets.size != other.keyRotatePackets.size) return false
        for (i in keyRotatePackets.indices) {
            if (!keyRotatePackets[i].contentEquals(other.keyRotatePackets[i])) return false
        }
        if (!rosterSyncPacket.contentEquals(other.rosterSyncPacket)) return false
        if (removeMemberPacket != null) {
            if (other.removeMemberPacket == null || !removeMemberPacket.contentEquals(other.removeMemberPacket)) return false
        } else if (other.removeMemberPacket != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = newEpoch
        result = 31 * result + newGroupKey.contentHashCode()
        result = 31 * result + updatedRoster.hashCode()
        result = 31 * result + keyRotatePackets.hashCode()
        result = 31 * result + rosterSyncPacket.contentHashCode()
        result = 31 * result + (removeMemberPacket?.contentHashCode() ?: 0)
        return result
    }
}

data class GroupRemoveMemberPayload(
    val groupId: Long,
    val removedSenderFp: Long,
    val reason: String = "REMOVED_BY_ADMIN"
) {
    companion object {
        fun serialize(sentAtMs: Long, payload: GroupRemoveMemberPayload): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeLong(sentAtMs)
            dos.writeInt((payload.groupId and 0xFFFFFFFFL).toInt())
            dos.writeLong(payload.removedSenderFp)
            val reasonBytes = payload.reason.toByteArray(Charsets.UTF_8)
            dos.writeInt(reasonBytes.size)
            dos.write(reasonBytes)
            dos.flush()
            return baos.toByteArray()
        }

        fun deserialize(bytes: ByteArray): Pair<Long, GroupRemoveMemberPayload> {
            val bais = ByteArrayInputStream(bytes)
            val dis = DataInputStream(bais)
            val sentAtMs = dis.readLong()
            val groupId = dis.readInt().toLong() and 0xFFFFFFFFL
            val removedSenderFp = dis.readLong()
            val reasonLen = dis.readInt()
            val reasonBytes = ByteArray(reasonLen)
            dis.readFully(reasonBytes)
            val reason = String(reasonBytes, Charsets.UTF_8)
            return Pair(sentAtMs, GroupRemoveMemberPayload(groupId, removedSenderFp, reason))
        }
    }
}

data class KeyRotationResult(
    val newEpoch: Int,
    val newGroupKey: ByteArray,
    val keyRotatePackets: List<ByteArray>,
    val rosterSyncPacket: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeyRotationResult

        if (newEpoch != other.newEpoch) return false
        if (!newGroupKey.contentEquals(other.newGroupKey)) return false
        if (keyRotatePackets.size != other.keyRotatePackets.size) return false
        for (i in keyRotatePackets.indices) {
            if (!keyRotatePackets[i].contentEquals(other.keyRotatePackets[i])) return false
        }
        if (!rosterSyncPacket.contentEquals(other.rosterSyncPacket)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = newEpoch
        result = 31 * result + newGroupKey.contentHashCode()
        result = 31 * result + keyRotatePackets.hashCode()
        result = 31 * result + rosterSyncPacket.contentHashCode()
        return result
    }
}

data class DecryptedHeldPacket(
    val header: PacketHeader,
    val sentAtMs: Long,
    val plaintext: String
)

data class ProcessRotationResult(
    val newEpochState: EpochState,
    val decryptedPackets: List<DecryptedHeldPacket>
)

/**
 * Service managing group key rotation, admin removal of members, KEY_ROTATE dispatch,
 * and future-epoch packet unbuffering (SEC-7, SEC-11, SEC-12, SEC-13, T1.11).
 */
class RotationService(
    private val groupDao: GroupDao? = null,
    private val rosterMemberDao: RosterMemberDao? = null,
    private val keyVault: KeyVault = KeyVault(),
    private val secureRandom: SecureRandom = SecureRandom()
) {

    /**
     * Admin operation: Removes a member from the group.
     * 1. Increments key_epoch.
     * 2. Generates a fresh 256-bit AES key.
     * 3. Adds the removed signing key to the revoked list and bumps roster version.
     * 4. Signs the new roster.
     * 5. Creates one KEY_ROTATE packet per remaining active member sealed under their pairwise secret (N-1 packets).
     * 6. Creates a ROSTER_SYNC packet carrying the new signed roster.
     */
    suspend fun removeMember(
        groupId: Long,
        currentEpoch: Int,
        currentRoster: SignedRoster,
        removedSenderFp: Long,
        adminIdentity: LocalIdentity,
        adminSigningPrivateKey: PrivateKey,
        adminEcdhPrivateKey: PrivateKey,
        nowMs: Long
    ): Result<MemberRemovalResult> {
        if (adminIdentity.senderFp != currentRoster.adminFp) {
            return Result.failure(IllegalStateException("Caller is not the group admin"))
        }
        if (removedSenderFp == adminIdentity.senderFp) {
            return Result.failure(IllegalArgumentException("Admin cannot remove themselves from group"))
        }

        val memberToRemove = currentRoster.members.find { it.senderFp == removedSenderFp }
            ?: return Result.failure(IllegalArgumentException("Member to remove not found in roster"))

        val newEpoch = currentEpoch + 1
        val newGroupKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val newRosterVersion = currentRoster.rosterVersion + 1

        val updatedMembers = currentRoster.members.map { member ->
            if (member.senderFp == removedSenderFp) member.copy(revoked = true)
            else member
        }

        val unsignedRosterBytes = RosterCodec.encodeForSigning(
            groupId = groupId,
            rosterVersion = newRosterVersion,
            adminFp = adminIdentity.senderFp,
            members = updatedMembers
        )
        val adminRosterSig = Ecdsa.sign(adminSigningPrivateKey, unsignedRosterBytes)
        val updatedRoster = SignedRoster(
            groupId = groupId,
            rosterVersion = newRosterVersion,
            adminFp = adminIdentity.senderFp,
            members = updatedMembers,
            signature = adminRosterSig
        )

        // Generate KEY_ROTATE for all remaining active non-admin members (N - 1)
        val remainingActiveMembers = updatedMembers.filter { !it.revoked && it.senderFp != adminIdentity.senderFp }
        val keyRotatePackets = ArrayList<ByteArray>(remainingActiveMembers.size)

        for (member in remainingActiveMembers) {
            runCatching {
                if (member.ecdhPub.isNotEmpty()) {
                    val pairwise = PairwiseChannel.derive(adminEcdhPrivateKey, member.ecdhPub)
                    val payload = KeyRotatePayload(
                        groupId = groupId,
                        keyEpoch = newEpoch,
                        newGroupKey = newGroupKey,
                        removedSenderFp = removedSenderFp,
                        rosterVersion = newRosterVersion
                    )
                    val packet = buildKeyRotatePacket(
                        adminFp = adminIdentity.senderFp,
                        adminSigningPrivateKey = adminSigningPrivateKey,
                        recipientPairwiseChannel = pairwise,
                        payload = payload,
                        sentAtMs = nowMs
                    )
                    keyRotatePackets.add(packet)
                }
            }
        }

        // Build ROSTER_SYNC packet
        val rosterSyncPacket = buildRosterSyncPacket(
            adminFp = adminIdentity.senderFp,
            adminSigningPrivateKey = adminSigningPrivateKey,
            signedRoster = updatedRoster,
            groupKey = newGroupKey,
            keyEpoch = newEpoch,
            sentAtMs = nowMs
        )

        // Build GROUP_REMOVE_MEMBER control packet targeted at the removed member
        val removedMemberPairwise = if (memberToRemove.ecdhPub.isNotEmpty()) {
            runCatching {
                PairwiseChannel.derive(adminEcdhPrivateKey, memberToRemove.ecdhPub)
            }.getOrNull()
        } else null

        val removePayload = GroupRemoveMemberPayload(
            groupId = groupId,
            removedSenderFp = removedSenderFp
        )
        val removePacket = runCatching {
            buildGroupRemoveMemberPacket(
                adminFp = adminIdentity.senderFp,
                adminSigningPrivateKey = adminSigningPrivateKey,
                removedMemberPairwiseChannel = removedMemberPairwise,
                groupKey = newGroupKey,
                payload = removePayload,
                sentAtMs = nowMs
            )
        }.getOrNull()

        // If DAOs are available, persist changes
        if (groupDao != null) {
            val currentGroup = groupDao.getGroup(groupId)
            if (currentGroup != null) {
                val wrappedNewKey = keyVault.wrap(newGroupKey)
                val updatedGroup = currentGroup.copy(
                    currentEpoch = newEpoch,
                    wrappedCurrentKey = wrappedNewKey,
                    prevEpoch = currentEpoch,
                    wrappedPrevKey = currentGroup.wrappedCurrentKey,
                    graceExpiresMs = nowMs + EpochPolicy.GRACE_PERIOD_MS,
                    rosterVersion = newRosterVersion
                )
                groupDao.updateGroup(updatedGroup)
            }
        }
        if (rosterMemberDao != null) {
            rosterMemberDao.revokeMember(groupId, removedSenderFp)
        }

        return Result.success(
            MemberRemovalResult(
                newEpoch = newEpoch,
                newGroupKey = newGroupKey,
                updatedRoster = updatedRoster,
                keyRotatePackets = keyRotatePackets,
                rosterSyncPacket = rosterSyncPacket,
                removeMemberPacket = removePacket
            )
        )
    }

    /**
     * Admin operation: Manual key rotation without removing members.
     */
    suspend fun rotateKey(
        groupId: Long,
        currentEpoch: Int,
        currentRoster: SignedRoster,
        adminIdentity: LocalIdentity,
        adminSigningPrivateKey: PrivateKey,
        adminEcdhPrivateKey: PrivateKey,
        nowMs: Long
    ): Result<KeyRotationResult> {
        if (adminIdentity.senderFp != currentRoster.adminFp) {
            return Result.failure(IllegalStateException("Caller is not the group admin"))
        }

        val newEpoch = currentEpoch + 1
        val newGroupKey = ByteArray(32).also { secureRandom.nextBytes(it) }

        val activeMembers = currentRoster.members.filter { !it.revoked && it.senderFp != adminIdentity.senderFp }
        val keyRotatePackets = ArrayList<ByteArray>(activeMembers.size)

        for (member in activeMembers) {
            val pairwise = PairwiseChannel.derive(adminEcdhPrivateKey, member.ecdhPub)
            val payload = KeyRotatePayload(
                groupId = groupId,
                keyEpoch = newEpoch,
                newGroupKey = newGroupKey,
                removedSenderFp = null,
                rosterVersion = currentRoster.rosterVersion
            )
            val packet = buildKeyRotatePacket(
                adminFp = adminIdentity.senderFp,
                adminSigningPrivateKey = adminSigningPrivateKey,
                recipientPairwiseChannel = pairwise,
                payload = payload,
                sentAtMs = nowMs
            )
            keyRotatePackets.add(packet)
        }

        val rosterSyncPacket = buildRosterSyncPacket(
            adminFp = adminIdentity.senderFp,
            adminSigningPrivateKey = adminSigningPrivateKey,
            signedRoster = currentRoster,
            groupKey = newGroupKey,
            keyEpoch = newEpoch,
            sentAtMs = nowMs
        )

        if (groupDao != null) {
            val currentGroup = groupDao.getGroup(groupId)
            if (currentGroup != null) {
                val wrappedNewKey = keyVault.wrap(newGroupKey)
                val updatedGroup = currentGroup.copy(
                    currentEpoch = newEpoch,
                    wrappedCurrentKey = wrappedNewKey,
                    prevEpoch = currentEpoch,
                    wrappedPrevKey = currentGroup.wrappedCurrentKey,
                    graceExpiresMs = nowMs + EpochPolicy.GRACE_PERIOD_MS
                )
                groupDao.updateGroup(updatedGroup)
            }
        }

        return Result.success(
            KeyRotationResult(
                newEpoch = newEpoch,
                newGroupKey = newGroupKey,
                keyRotatePackets = keyRotatePackets,
                rosterSyncPacket = rosterSyncPacket
            )
        )
    }

    companion object {

        /**
         * Builds a KEY_ROTATE packet encrypted with pairwise secret and signed by the admin (SEC-7).
         */
        fun buildKeyRotatePacket(
            adminFp: Long,
            adminSigningPrivateKey: PrivateKey,
            recipientPairwiseChannel: PairwiseChannel,
            payload: KeyRotatePayload,
            sentAtMs: Long,
            msgId: Long = ThreadLocalRandom.current().nextLong()
        ): ByteArray {
            val innerPayload = KeyRotatePayload.serialize(sentAtMs, payload)

            val header = PacketHeader(
                packetType = PacketType.KEY_ROTATE,
                ttl = BleConstants.DEFAULT_TTL,
                msgId = msgId,
                groupId = payload.groupId,
                senderFp = adminFp,
                keyEpoch = payload.keyEpoch
            )

            val aad = header.computeAad()
            val sealed = recipientPairwiseChannel.seal(innerPayload, aad)

            val coreBlob = CoreBlob(sealed.nonce, sealed.tag, sealed.ciphertext)
            val signedData = aad + coreBlob.encode()
            val signature = Ecdsa.sign(adminSigningPrivateKey, signedData)

            val frame = WireFrame(header, coreBlob, signature)
            return WireCodec.encode(frame)
        }

        /**
         * Opens and verifies a KEY_ROTATE packet (SEC-6, SEC-7).
         * Enforces strict signature verification BEFORE attempting decryption.
         */
        fun openKeyRotatePacket(
            frameBytes: ByteArray,
            adminSigningPubkey: ByteArray,
            recipientPairwiseChannel: PairwiseChannel
        ): Result<Pair<PacketHeader, KeyRotatePayload>> {
            val wireFrame = WireCodec.decode(frameBytes).getOrElse { return Result.failure(it) }
            if (wireFrame.header.packetType != PacketType.KEY_ROTATE) {
                return Result.failure(
                    IllegalArgumentException("Expected KEY_ROTATE packet, got ${wireFrame.header.packetType}")
                )
            }

            // 1. Strict verification: verify admin signature before decryption (SEC-6)
            val aad = wireFrame.header.computeAad()
            val coreBlobBytes = wireFrame.coreBlob.encode()
            val signedData = aad + coreBlobBytes

            val sigValid = Ecdsa.verify(adminSigningPubkey, signedData, wireFrame.signature)
            if (!sigValid) {
                return Result.failure(CryptoError.BadSignature("Invalid admin signature on KEY_ROTATE packet"))
            }

            // 2. Decrypt with recipient's pairwise channel
            val openResult = recipientPairwiseChannel.open(
                nonce = wireFrame.coreBlob.nonce,
                ciphertext = wireFrame.coreBlob.ciphertext,
                tag = wireFrame.coreBlob.tag,
                aad = aad
            )

            if (openResult.isFailure) {
                return Result.failure(openResult.exceptionOrNull()!!)
            }

            val decryptedPayload = openResult.getOrThrow()
            return try {
                val (_, payload) = KeyRotatePayload.deserialize(decryptedPayload)
                Result.success(Pair(wireFrame.header, payload))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Builds a ROSTER_SYNC packet broadcast encrypted with group key and signed by admin (SEC-7).
         */
        fun buildRosterSyncPacket(
            adminFp: Long,
            adminSigningPrivateKey: PrivateKey,
            signedRoster: SignedRoster,
            groupKey: ByteArray,
            keyEpoch: Int,
            sentAtMs: Long,
            msgId: Long = ThreadLocalRandom.current().nextLong()
        ): ByteArray {
            val rosterBytes = RosterCodec.encode(signedRoster)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeLong(sentAtMs)
            dos.write(rosterBytes)
            dos.flush()
            val innerPayload = baos.toByteArray()

            val header = PacketHeader(
                packetType = PacketType.ROSTER_SYNC,
                ttl = BleConstants.DEFAULT_TTL,
                msgId = msgId,
                groupId = signedRoster.groupId,
                senderFp = adminFp,
                keyEpoch = keyEpoch
            )

            val aad = header.computeAad()
            val sealed = AesGcm.seal(groupKey, innerPayload, aad)

            val coreBlob = CoreBlob(sealed.nonce, sealed.tag, sealed.ciphertext)
            val signedData = aad + coreBlob.encode()
            val signature = Ecdsa.sign(adminSigningPrivateKey, signedData)

            val frame = WireFrame(header, coreBlob, signature)
            return WireCodec.encode(frame)
        }

        /**
         * Opens and verifies a ROSTER_SYNC packet (SEC-6, SEC-7, SEC-10).
         * If adminSigningPubkey is present, verifies signature before decryption.
         * Otherwise verifies via groupKey GCM tag or sender signingPub from decrypted roster.
         */
        fun openRosterSyncPacket(
            frameBytes: ByteArray,
            adminSigningPubkey: ByteArray,
            groupKey: ByteArray
        ): Result<Pair<PacketHeader, SignedRoster>> {
            val wireFrame = WireCodec.decode(frameBytes).getOrElse { return Result.failure(it) }
            if (wireFrame.header.packetType != PacketType.ROSTER_SYNC) {
                return Result.failure(
                    IllegalArgumentException("Expected ROSTER_SYNC packet, got ${wireFrame.header.packetType}")
                )
            }

            // 1. Strict verification: verify admin signature before decryption if non-empty (SEC-6)
            val aad = wireFrame.header.computeAad()
            val coreBlobBytes = wireFrame.coreBlob.encode()
            val signedData = aad + coreBlobBytes

            if (adminSigningPubkey.isNotEmpty()) {
                val sigValid = Ecdsa.verify(adminSigningPubkey, signedData, wireFrame.signature)
                if (!sigValid) {
                    return Result.failure(CryptoError.BadSignature("Invalid admin signature on ROSTER_SYNC packet"))
                }
            }

            // 2. Decrypt with group key (AES-256-GCM auth tag check against aad)
            val openResult = AesGcm.open(
                key = groupKey,
                nonce = wireFrame.coreBlob.nonce,
                ciphertext = wireFrame.coreBlob.ciphertext,
                tag = wireFrame.coreBlob.tag,
                aad = aad
            )

            if (openResult.isFailure) {
                return Result.failure(openResult.exceptionOrNull()!!)
            }

            val innerPayload = openResult.getOrThrow()
            if (innerPayload.size < 8) {
                return Result.failure(IllegalArgumentException("ROSTER_SYNC inner payload truncated"))
            }

            val bais = ByteArrayInputStream(innerPayload)
            val dis = DataInputStream(bais)
            val sentAtMs = dis.readLong()
            val rosterBytes = ByteArray(innerPayload.size - 8).also { dis.readFully(it) }

            val rosterResult = RosterCodec.decode(rosterBytes)
            if (rosterResult.isFailure) {
                return Result.failure(rosterResult.exceptionOrNull()!!)
            }

            val signedRoster = rosterResult.getOrThrow()

            // If adminSigningPubkey was empty, check if sender is in signedRoster.members and verify signature
            if (adminSigningPubkey.isEmpty()) {
                val senderMember = signedRoster.members.find { it.senderFp == wireFrame.header.senderFp }
                if (senderMember != null && senderMember.signingPub.isNotEmpty()) {
                    val sigValid = Ecdsa.verify(senderMember.signingPub, signedData, wireFrame.signature)
                    if (!sigValid) {
                        return Result.failure(CryptoError.BadSignature("Invalid member signature on ROSTER_SYNC packet"))
                    }
                }
            }

            return Result.success(Pair(wireFrame.header, signedRoster))
        }

        /**
         * Member operation: Installs new key, advances epoch state with 10-minute grace window,
         * and decrypts any packets previously held for this future epoch (SEC-12, SEC-13).
         */
        fun applyRotationAndReleaseHeldPackets(
            currentEpochState: EpochState,
            payload: KeyRotatePayload,
            heldPackets: List<ByteArray>,
            memberSigningPubkeys: Map<Long, ByteArray>,
            nowMs: Long,
            clock: Clock = Clock.System
        ): ProcessRotationResult {
            val newEpochState = EpochPolicy.rotate(
                state = currentEpochState,
                newKey = payload.newGroupKey,
                removedSenderFp = payload.removedSenderFp,
                nowMs = nowMs
            )

            val decryptedPackets = mutableListOf<DecryptedHeldPacket>()
            for (frameBytes in heldPackets) {
                val headerResult = PacketHeader.decode(frameBytes, 0)
                if (headerResult.isFailure) continue
                val header = headerResult.getOrThrow()

                val eval = EpochPolicy.evaluate(newEpochState, header.senderFp, header.keyEpoch, nowMs)
                if (eval is EpochEvaluation.UseKey) {
                    val pubkey = memberSigningPubkeys[header.senderFp] ?: continue
                    val verdict = EnvelopeVerifier.verifyAndOpen(frameBytes, eval.key, pubkey, clock)
                    if (verdict is PacketVerdict.Accept) {
                        decryptedPackets.add(DecryptedHeldPacket(verdict.header, verdict.sentAtMs, verdict.plaintext))
                    }
                }
            }

            return ProcessRotationResult(newEpochState, decryptedPackets)
        }

        /**
         * Builds a GROUP_REMOVE_MEMBER packet targeted at a removed member, encrypted with
         * their pairwise channel or groupKey, and signed by the group admin.
         */
        fun buildGroupRemoveMemberPacket(
            adminFp: Long,
            adminSigningPrivateKey: PrivateKey,
            removedMemberPairwiseChannel: PairwiseChannel? = null,
            groupKey: ByteArray? = null,
            payload: GroupRemoveMemberPayload,
            sentAtMs: Long,
            msgId: Long = ThreadLocalRandom.current().nextLong()
        ): ByteArray {
            val innerPayload = GroupRemoveMemberPayload.serialize(sentAtMs, payload)

            val header = PacketHeader(
                packetType = PacketType.GROUP_REMOVE_MEMBER,
                ttl = BleConstants.DEFAULT_TTL,
                msgId = msgId,
                groupId = payload.groupId,
                senderFp = adminFp,
                keyEpoch = 1
            )

            val aad = header.computeAad()
            val sealed = when {
                removedMemberPairwiseChannel != null -> removedMemberPairwiseChannel.seal(innerPayload, aad)
                groupKey != null -> AesGcm.seal(groupKey, innerPayload, aad)
                else -> throw IllegalArgumentException("Either pairwise channel or groupKey must be provided")
            }

            val coreBlob = CoreBlob(sealed.nonce, sealed.tag, sealed.ciphertext)
            val signedData = aad + coreBlob.encode()
            val signature = Ecdsa.sign(adminSigningPrivateKey, signedData)

            val frame = WireFrame(header, coreBlob, signature)
            return WireCodec.encode(frame)
        }

        /**
         * Opens and verifies a GROUP_REMOVE_MEMBER packet.
         * Verifies admin signature if non-empty, and attempts pairwise or groupKey decryption.
         */
        fun openGroupRemoveMemberPacket(
            frameBytes: ByteArray,
            adminSigningPubkey: ByteArray,
            removedMemberPairwiseChannel: PairwiseChannel? = null,
            groupKey: ByteArray? = null
        ): Result<Pair<PacketHeader, GroupRemoveMemberPayload>> {
            val wireFrame = WireCodec.decode(frameBytes).getOrElse { return Result.failure(it) }
            if (wireFrame.header.packetType != PacketType.GROUP_REMOVE_MEMBER) {
                return Result.failure(
                    IllegalArgumentException("Expected GROUP_REMOVE_MEMBER packet, got ${wireFrame.header.packetType}")
                )
            }

            val aad = wireFrame.header.computeAad()
            val coreBlobBytes = wireFrame.coreBlob.encode()
            val signedData = aad + coreBlobBytes

            if (adminSigningPubkey.isNotEmpty()) {
                val sigValid = Ecdsa.verify(adminSigningPubkey, signedData, wireFrame.signature)
                if (!sigValid) {
                    return Result.failure(CryptoError.BadSignature("Invalid admin signature on GROUP_REMOVE_MEMBER packet"))
                }
            }

            var decryptedPayload: ByteArray? = null
            if (removedMemberPairwiseChannel != null) {
                val openResult = removedMemberPairwiseChannel.open(
                    nonce = wireFrame.coreBlob.nonce,
                    ciphertext = wireFrame.coreBlob.ciphertext,
                    tag = wireFrame.coreBlob.tag,
                    aad = aad
                )
                if (openResult.isSuccess) {
                    decryptedPayload = openResult.getOrNull()
                }
            }

            if (decryptedPayload == null && groupKey != null) {
                val openResult = AesGcm.open(
                    key = groupKey,
                    nonce = wireFrame.coreBlob.nonce,
                    ciphertext = wireFrame.coreBlob.ciphertext,
                    tag = wireFrame.coreBlob.tag,
                    aad = aad
                )
                if (openResult.isSuccess) {
                    decryptedPayload = openResult.getOrNull()
                }
            }

            if (decryptedPayload == null) {
                return Result.failure(CryptoError.AuthFailed("Failed to decrypt GROUP_REMOVE_MEMBER via pairwise or group key"))
            }

            return try {
                val (_, removePayload) = GroupRemoveMemberPayload.deserialize(decryptedPayload)
                Result.success(Pair(wireFrame.header, removePayload))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
