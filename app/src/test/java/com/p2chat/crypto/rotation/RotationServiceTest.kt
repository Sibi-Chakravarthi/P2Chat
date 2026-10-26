package com.p2chat.crypto.rotation

import com.p2chat.crypto.envelope.EnvelopeBuilder
import com.p2chat.crypto.envelope.EnvelopeVerifier
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.epoch.EpochEvaluation
import com.p2chat.crypto.epoch.EpochPolicy
import com.p2chat.crypto.epoch.EpochState
import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.group.RosterMemberInfo
import com.p2chat.crypto.group.SignedRoster
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

class RotationServiceTest {

    private data class SimulatedNode(
        val alias: String,
        val signingKeyPair: KeyPair,
        val ecdhKeyPair: KeyPair,
        val signingPub: ByteArray,
        val ecdhPub: ByteArray,
        val senderFp: Long,
        val identity: LocalIdentity
    ) {
        companion object {
            fun create(alias: String): SimulatedNode {
                val signingKp = SoftwareEcProvider.generateKeyPair()
                val ecdhKp = SoftwareEcProvider.generateKeyPair()
                val signingPub = EcProvider.encodeUncompressedPublicKey(signingKp.public)
                val ecdhPub = EcProvider.encodeUncompressedPublicKey(ecdhKp.public)
                val fp = EcProvider.computeSenderFp(signingPub)

                val identity = LocalIdentity(
                    senderFp = fp,
                    alias = alias,
                    signingPub = signingPub,
                    ecdhPub = ecdhPub,
                    wrappedSigningPriv = ByteArray(32),
                    wrappedEcdhPriv = ByteArray(32)
                )

                return SimulatedNode(
                    alias = alias,
                    signingKeyPair = signingKp,
                    ecdhKeyPair = ecdhKp,
                    signingPub = signingPub,
                    ecdhPub = ecdhPub,
                    senderFp = fp,
                    identity = identity
                )
            }
        }
    }

    @Test
    fun testFourNodeSimulation_adminRemoval_heldPacketRelease_isolation() = runBlocking {
        val baseTimeMs = 1700000000000L
        val clock = FakeClock(baseTimeMs)

        // 1. Setup 4 in-memory nodes: Admin, Member 1, Member 2, Removed Member
        val admin = SimulatedNode.create("Admin")
        val member1 = SimulatedNode.create("Member1")
        val member2 = SimulatedNode.create("Member2")
        val removed = SimulatedNode.create("RemovedMember")

        val groupId = 0xDEADBEEFL
        val initialEpoch = 1
        val initialGroupKey = ByteArray(32) { 0x11 }

        val initialMembers = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false),
            RosterMemberInfo(member1.senderFp, member1.signingPub, member1.ecdhPub, member1.alias, false),
            RosterMemberInfo(member2.senderFp, member2.signingPub, member2.ecdhPub, member2.alias, false),
            RosterMemberInfo(removed.senderFp, removed.signingPub, removed.ecdhPub, removed.alias, false)
        )

        val rosterVersion1 = 1
        val unsignedRoster1 = RosterCodec.encodeForSigning(groupId, rosterVersion1, admin.senderFp, initialMembers)
        val adminRosterSig1 = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsignedRoster1)
        val initialSignedRoster = SignedRoster(groupId, rosterVersion1, admin.senderFp, initialMembers, adminRosterSig1)

        // All nodes start at epoch 1 with initialGroupKey
        var adminEpochState = EpochState(currentEpoch = initialEpoch, currentKey = initialGroupKey)
        var m1EpochState = EpochState(currentEpoch = initialEpoch, currentKey = initialGroupKey)
        var m2EpochState = EpochState(currentEpoch = initialEpoch, currentKey = initialGroupKey)
        val removedEpochState = EpochState(currentEpoch = initialEpoch, currentKey = initialGroupKey)

        val rotationService = RotationService()

        // 2. Admin removes member 'removed'
        val removalResult = rotationService.removeMember(
            groupId = groupId,
            currentEpoch = initialEpoch,
            currentRoster = initialSignedRoster,
            removedSenderFp = removed.senderFp,
            adminIdentity = admin.identity,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            adminEcdhPrivateKey = admin.ecdhKeyPair.private,
            nowMs = clock.currentTimeMillis()
        ).getOrThrow()

        // Verify result properties
        assertEquals(2, removalResult.newEpoch)
        assertEquals(32, removalResult.newGroupKey.size)
        assertEquals(2, removalResult.updatedRoster.rosterVersion)

        // Requirement check: Number of KEY_ROTATE packets equals remaining non-admin members (3 - 1 = 2)
        assertEquals(2, removalResult.keyRotatePackets.size)

        // Verify roster update: 'removed' is marked revoked
        val updatedRemovedInfo = removalResult.updatedRoster.members.find { it.senderFp == removed.senderFp }
        assertNotNull(updatedRemovedInfo)
        assertTrue("Removed member must be marked revoked in roster", updatedRemovedInfo!!.revoked)

        // Admin updates local epoch state
        adminEpochState = EpochPolicy.rotate(
            state = adminEpochState,
            newKey = removalResult.newGroupKey,
            removedSenderFp = removed.senderFp,
            nowMs = clock.currentTimeMillis()
        )

        // 3. Admin creates and broadcasts an Epoch 2 CHAT message
        val epoch2Plaintext = "Secret message in Epoch 2"
        val epoch2ChatHeader = PacketHeader(
            packetType = PacketType.CHAT,
            ttl = BleConstants.DEFAULT_TTL,
            msgId = 0x2222L,
            groupId = groupId,
            senderFp = admin.senderFp,
            keyEpoch = 2
        )
        val epoch2ChatFrame = EnvelopeBuilder.buildChatPacket(
            header = epoch2ChatHeader,
            sentAtMs = clock.currentTimeMillis(),
            plaintext = epoch2Plaintext,
            groupKey = removalResult.newGroupKey,
            signingPrivateKey = admin.signingKeyPair.private
        )

        // 4. Test Future Epoch Holding: Member 2 receives Epoch 2 message BEFORE receiving KEY_ROTATE packet
        val evalM2BeforeKey = EpochPolicy.evaluate(m2EpochState, admin.senderFp, packetEpoch = 2, nowMs = clock.currentTimeMillis())
        assertEquals("Epoch 2 message must be held for future epoch", EpochEvaluation.HoldFutureEpoch, evalM2BeforeKey)
        val m2HeldPackets = listOf(epoch2ChatFrame)

        // 5. Member 1 processes its KEY_ROTATE packet
        val pairwiseAdminM1 = PairwiseChannel.derive(member1.ecdhKeyPair.private, admin.ecdhPub)
        // Find M1's rotate packet
        var m1Payload: KeyRotatePayload? = null
        for (packet in removalResult.keyRotatePackets) {
            val opened = RotationService.openKeyRotatePacket(packet, admin.signingPub, pairwiseAdminM1)
            if (opened.isSuccess) {
                m1Payload = opened.getOrThrow().second
                break
            }
        }
        assertNotNull("Member 1 must be able to open its KEY_ROTATE packet", m1Payload)
        assertEquals(2, m1Payload!!.keyEpoch)
        assertTrue(removalResult.newGroupKey.contentEquals(m1Payload.newGroupKey))
        assertEquals(removed.senderFp, m1Payload.removedSenderFp)

        m1EpochState = EpochPolicy.rotate(m1EpochState, m1Payload.newGroupKey, m1Payload.removedSenderFp, clock.currentTimeMillis())

        // Member 1 can now decrypt the Epoch 2 message
        val m1Verdict = EnvelopeVerifier.verifyAndOpen(epoch2ChatFrame, m1EpochState.currentKey, admin.signingPub, clock)
        assertTrue("Member 1 must decrypt epoch 2 message", m1Verdict is PacketVerdict.Accept)
        assertEquals(epoch2Plaintext, (m1Verdict as PacketVerdict.Accept).plaintext)

        // 6. Member 2 receives and processes its KEY_ROTATE packet, releasing held packets
        val pairwiseAdminM2 = PairwiseChannel.derive(member2.ecdhKeyPair.private, admin.ecdhPub)
        var m2Payload: KeyRotatePayload? = null
        for (packet in removalResult.keyRotatePackets) {
            val opened = RotationService.openKeyRotatePacket(packet, admin.signingPub, pairwiseAdminM2)
            if (opened.isSuccess) {
                m2Payload = opened.getOrThrow().second
                break
            }
        }
        assertNotNull("Member 2 must be able to open its KEY_ROTATE packet", m2Payload)

        val memberPubkeys = mapOf(
            admin.senderFp to admin.signingPub,
            member1.senderFp to member1.signingPub,
            member2.senderFp to member2.signingPub,
            removed.senderFp to removed.signingPub
        )

        val m2ProcessResult = RotationService.applyRotationAndReleaseHeldPackets(
            currentEpochState = m2EpochState,
            payload = m2Payload!!,
            heldPackets = m2HeldPackets,
            memberSigningPubkeys = memberPubkeys,
            nowMs = clock.currentTimeMillis(),
            clock = clock
        )

        m2EpochState = m2ProcessResult.newEpochState
        assertEquals(2, m2EpochState.currentEpoch)
        assertEquals("Held Epoch 2 packet must be released and decrypted", 1, m2ProcessResult.decryptedPackets.size)
        assertEquals(epoch2Plaintext, m2ProcessResult.decryptedPackets[0].plaintext)

        // 7. Verify Removed Node Isolation:
        // (a) Removed node attempts to decrypt epoch 2 message with epoch 1 key -> DropAuthFail
        val removedVerdict = EnvelopeVerifier.verifyAndOpen(
            frameBytes = epoch2ChatFrame,
            groupKey = removedEpochState.currentKey,
            senderSigningPubkey = admin.signingPub,
            clock = clock
        )
        assertEquals("Removed member must NOT be able to decrypt Epoch 2 message", PacketVerdict.DropAuthFail, removedVerdict)

        // (b) Removed node tries to decrypt KEY_ROTATE packets -> fails (not encrypted for removed node)
        for (packet in removalResult.keyRotatePackets) {
            val pairwiseAdminRemoved = PairwiseChannel.derive(removed.ecdhKeyPair.private, admin.ecdhPub)
            val openAttempt = RotationService.openKeyRotatePacket(packet, admin.signingPub, pairwiseAdminRemoved)
            assertFalse("Removed member must NOT decrypt KEY_ROTATE of other members", openAttempt.isSuccess)
        }

        // (c) Removed node tries to send an Epoch 1 message -> other nodes drop it immediately (RevokedSender)
        val evalRemoved = EpochPolicy.evaluate(m1EpochState, removed.senderFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertEquals("Packets from revoked sender must be dropped immediately", EpochEvaluation.RevokedSender, evalRemoved)

        // 8. Grace Window: Member 1 sends an Epoch 1 message at now + 5 minutes
        clock.advanceMillis(5 * 60 * 1000L) // 5 minutes in
        val evalM1Epoch1 = EpochPolicy.evaluate(m2EpochState, member1.senderFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertTrue("Epoch 1 message from active member accepted within grace window", evalM1Epoch1 is EpochEvaluation.UseKey)
        val useKey = evalM1Epoch1 as EpochEvaluation.UseKey
        assertEquals(1, useKey.epoch)
        assertTrue(initialGroupKey.contentEquals(useKey.key))

        // 9. After 10-minute grace window: Epoch 1 messages rejected
        clock.advanceMillis(6 * 60 * 1000L) // total 11 minutes
        val evalExpired = EpochPolicy.evaluate(m2EpochState, member1.senderFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertEquals("Epoch 1 message rejected after 10-minute grace period", EpochEvaluation.EpochExpired, evalExpired)

        // 10. ROSTER_SYNC roundtrip: M1 opens ROSTER_SYNC
        val openRosterResult = RotationService.openRosterSyncPacket(
            frameBytes = removalResult.rosterSyncPacket,
            adminSigningPubkey = admin.signingPub,
            groupKey = m1EpochState.currentKey
        )
        assertTrue("ROSTER_SYNC must open with new group key", openRosterResult.isSuccess)
        val syncedRoster = openRosterResult.getOrThrow().second
        assertEquals(2, syncedRoster.rosterVersion)
        assertTrue(syncedRoster.members.find { it.senderFp == removed.senderFp }!!.revoked)
    }

    @Test
    fun testKeyRotatePacket_tamperedSignature_rejected() {
        val admin = SimulatedNode.create("Admin")
        val member = SimulatedNode.create("Member")
        val pairwise = PairwiseChannel.derive(admin.ecdhKeyPair.private, member.ecdhPub)
        val pairwiseRecv = PairwiseChannel.derive(member.ecdhKeyPair.private, admin.ecdhPub)

        val payload = KeyRotatePayload(
            groupId = 0x1234L,
            keyEpoch = 2,
            newGroupKey = ByteArray(32) { 0x55 },
            removedSenderFp = null
        )

        val validPacket = RotationService.buildKeyRotatePacket(
            adminFp = admin.senderFp,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            recipientPairwiseChannel = pairwise,
            payload = payload,
            sentAtMs = 1700000000000L
        )

        // Tamper signature (last byte)
        val tamperedSig = validPacket.copyOf()
        tamperedSig[tamperedSig.size - 1] = (tamperedSig[tamperedSig.size - 1].toInt() xor 0xFF).toByte()

        val openResult = RotationService.openKeyRotatePacket(tamperedSig, admin.signingPub, pairwiseRecv)
        assertFalse(openResult.isSuccess)
        assertTrue(openResult.exceptionOrNull() is com.p2chat.crypto.prim.CryptoError.BadSignature)
    }

    @Test
    fun testKeyRotatePacket_tamperedCiphertext_rejected() {
        val admin = SimulatedNode.create("Admin")
        val member = SimulatedNode.create("Member")
        val pairwise = PairwiseChannel.derive(admin.ecdhKeyPair.private, member.ecdhPub)
        val pairwiseRecv = PairwiseChannel.derive(member.ecdhKeyPair.private, admin.ecdhPub)

        val payload = KeyRotatePayload(
            groupId = 0x1234L,
            keyEpoch = 2,
            newGroupKey = ByteArray(32) { 0x55 },
            removedSenderFp = null
        )

        val validPacket = RotationService.buildKeyRotatePacket(
            adminFp = admin.senderFp,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            recipientPairwiseChannel = pairwise,
            payload = payload,
            sentAtMs = 1700000000000L
        )

        // Tamper ciphertext byte (offset around 60)
        val tamperedCt = validPacket.copyOf()
        tamperedCt[60] = (tamperedCt[60].toInt() xor 0xFF).toByte()

        val openResult = RotationService.openKeyRotatePacket(tamperedCt, admin.signingPub, pairwiseRecv)
        assertFalse(openResult.isSuccess)
    }

    @Test
    fun testRosterSyncPacket_tamperedSignature_rejected() {
        val admin = SimulatedNode.create("Admin")
        val member = SimulatedNode.create("Member")
        val groupKey = ByteArray(32) { 0x77 }

        val members = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false),
            RosterMemberInfo(member.senderFp, member.signingPub, member.ecdhPub, member.alias, false)
        )
        val unsigned = RosterCodec.encodeForSigning(0x1234L, 1, admin.senderFp, members)
        val sig = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsigned)
        val signedRoster = SignedRoster(0x1234L, 1, admin.senderFp, members, sig)

        val packet = RotationService.buildRosterSyncPacket(
            adminFp = admin.senderFp,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            signedRoster = signedRoster,
            groupKey = groupKey,
            keyEpoch = 1,
            sentAtMs = 1700000000000L
        )

        val tamperedSig = packet.copyOf()
        tamperedSig[tamperedSig.size - 1] = (tamperedSig[tamperedSig.size - 1].toInt() xor 0xFF).toByte()

        val openResult = RotationService.openRosterSyncPacket(tamperedSig, admin.signingPub, groupKey)
        assertFalse(openResult.isSuccess)
        assertTrue(openResult.exceptionOrNull() is com.p2chat.crypto.prim.CryptoError.BadSignature)
    }

    @Test
    fun testManualKeyRotation_withoutRemoval() = runBlocking {
        val admin = SimulatedNode.create("Admin")
        val m1 = SimulatedNode.create("M1")
        val m2 = SimulatedNode.create("M2")
        val groupKey1 = ByteArray(32) { 0x11 }

        val members = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false),
            RosterMemberInfo(m1.senderFp, m1.signingPub, m1.ecdhPub, m1.alias, false),
            RosterMemberInfo(m2.senderFp, m2.signingPub, m2.ecdhPub, m2.alias, false)
        )
        val unsigned = RosterCodec.encodeForSigning(0x5555L, 1, admin.senderFp, members)
        val sig = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsigned)
        val signedRoster = SignedRoster(0x5555L, 1, admin.senderFp, members, sig)

        val rotationService = RotationService()
        val rotationResult = rotationService.rotateKey(
            groupId = 0x5555L,
            currentEpoch = 1,
            currentRoster = signedRoster,
            adminIdentity = admin.identity,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            adminEcdhPrivateKey = admin.ecdhKeyPair.private,
            nowMs = 1700000000000L
        ).getOrThrow()

        assertEquals(2, rotationResult.newEpoch)
        assertEquals(2, rotationResult.keyRotatePackets.size) // For M1 and M2
        assertNotNull(rotationResult.rosterSyncPacket)
    }

    @Test
    fun testAdminCannotRemoveSelf() = runBlocking {
        val admin = SimulatedNode.create("Admin")
        val m1 = SimulatedNode.create("M1")

        val members = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false),
            RosterMemberInfo(m1.senderFp, m1.signingPub, m1.ecdhPub, m1.alias, false)
        )
        val unsigned = RosterCodec.encodeForSigning(0x5555L, 1, admin.senderFp, members)
        val sig = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsigned)
        val signedRoster = SignedRoster(0x5555L, 1, admin.senderFp, members, sig)

        val rotationService = RotationService()
        val result = rotationService.removeMember(
            groupId = 0x5555L,
            currentEpoch = 1,
            currentRoster = signedRoster,
            removedSenderFp = admin.senderFp,
            adminIdentity = admin.identity,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            adminEcdhPrivateKey = admin.ecdhKeyPair.private,
            nowMs = 1700000000000L
        )

        assertFalse("Admin removing themselves must fail", result.isSuccess)
    }

    @Test
    fun testRemoveMemberWithEmptyEcdhPub_SucceedsWithoutThrowing() = runBlocking {
        val admin = SimulatedNode.create("Admin")
        val autoRegisteredPeerFp = 0x123456789ABCDEF0L

        val members = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false),
            RosterMemberInfo(autoRegisteredPeerFp, ByteArray(0), ByteArray(0), "Peer-DEF0", false)
        )
        val unsigned = RosterCodec.encodeForSigning(0x7777L, 1, admin.senderFp, members)
        val sig = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsigned)
        val signedRoster = SignedRoster(0x7777L, 1, admin.senderFp, members, sig)

        val rotationService = RotationService()
        val result = rotationService.removeMember(
            groupId = 0x7777L,
            currentEpoch = 1,
            currentRoster = signedRoster,
            removedSenderFp = autoRegisteredPeerFp,
            adminIdentity = admin.identity,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            adminEcdhPrivateKey = admin.ecdhKeyPair.private,
            nowMs = 1700000000000L
        )

        assertTrue("Removing auto-registered member with empty ecdhPub must succeed", result.isSuccess)
        val removal = result.getOrThrow()
        assertEquals(2, removal.newEpoch)
        assertNotNull("Remove packet must be built with group key fallback", removal.removeMemberPacket)

        // Verify the remove packet can be opened using groupKey
        val openResult = RotationService.openGroupRemoveMemberPacket(
            frameBytes = removal.removeMemberPacket!!,
            adminSigningPubkey = admin.signingPub,
            groupKey = removal.newGroupKey
        )
        assertTrue("Opening remove member packet with group key must succeed", openResult.isSuccess)
        val (_, payload) = openResult.getOrThrow()
        assertEquals(autoRegisteredPeerFp, payload.removedSenderFp)
        assertEquals(0x7777L, payload.groupId)
    }

    @Test
    fun testOpenRosterSyncPacketWithEmptyAdminSigningPub_SucceedsViaGroupKey() = runBlocking {
        val admin = SimulatedNode.create("Admin")
        val groupKey = ByteArray(32) { 0x33 }
        val members = listOf(
            RosterMemberInfo(admin.senderFp, admin.signingPub, admin.ecdhPub, admin.alias, false)
        )
        val unsigned = RosterCodec.encodeForSigning(0x8888L, 1, admin.senderFp, members)
        val sig = com.p2chat.crypto.prim.Ecdsa.sign(admin.signingKeyPair.private, unsigned)
        val signedRoster = SignedRoster(0x8888L, 1, admin.senderFp, members, sig)

        val syncPacket = RotationService.buildRosterSyncPacket(
            adminFp = admin.senderFp,
            adminSigningPrivateKey = admin.signingKeyPair.private,
            signedRoster = signedRoster,
            groupKey = groupKey,
            keyEpoch = 1,
            sentAtMs = 1000L
        )

        val openResult = RotationService.openRosterSyncPacket(
            frameBytes = syncPacket,
            adminSigningPubkey = ByteArray(0),
            groupKey = groupKey
        )
        assertTrue("Opening ROSTER_SYNC with empty signing pub must succeed via groupKey GCM tag", openResult.isSuccess)
        val (header, decodedRoster) = openResult.getOrThrow()
        assertEquals(0x8888L, header.groupId)
        assertEquals(1, decodedRoster.members.size)
        assertEquals(admin.senderFp, decodedRoster.members[0].senderFp)
    }
}
