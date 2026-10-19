package com.p2chat.crypto.invite

import com.p2chat.crypto.group.RosterCodec
import com.p2chat.crypto.group.RosterMemberInfo
import com.p2chat.crypto.group.SignedRoster
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.crypto.prim.Ecdsa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteAndPairwiseTest {

    @Test
    fun testQrPayload_roundTripAndSizeBounds() {
        val keyPairSign = SoftwareEcProvider.generateKeyPair()
        val keyPairEcdh = SoftwareEcProvider.generateKeyPair()

        val signPub = EcProvider.encodeUncompressedPublicKey(keyPairSign.public)
        val ecdhPub = EcProvider.encodeUncompressedPublicKey(keyPairEcdh.public)
        val alias = "AliceInBorderland"

        val payload = QrPayload(signPub, ecdhPub, alias)
        val encodedBytes = QrPayloadCodec.encode(payload)

        // Record byte size: Magic (2B) + Ver (1B) + SignPub (65B) + EcdhPub (65B) + Len (1B) + Alias (17B) = 151 bytes
        println("QR Payload size for 17-char alias: ${encodedBytes.size} bytes")
        assertTrue("Encoded QR bytes must be <= 165 bytes", encodedBytes.size <= 165)

        val decodedResult = QrPayloadCodec.decode(encodedBytes)
        assertTrue(decodedResult.isSuccess)
        val decoded = decodedResult.getOrThrow()
        assertEquals(payload, decoded)

        // Base64 roundtrip
        val b64 = QrPayloadCodec.encodeToBase64(payload)
        println("QR Payload Base64 URL length: ${b64.length} chars")
        val decodedB64 = QrPayloadCodec.decodeFromBase64(b64)
        assertTrue(decodedB64.isSuccess)
        assertEquals(payload, decodedB64.getOrThrow())
    }

    @Test
    fun testPairwiseChannel_matchesBothSides() {
        val alice = SoftwareEcProvider.generateKeyPair()
        val bob = SoftwareEcProvider.generateKeyPair()

        val channelAliceToBob = PairwiseChannel.derive(alice.private, bob.public)
        val channelBobToAlice = PairwiseChannel.derive(bob.private, alice.public)

        assertTrue(
            "Both parties must derive identical pairwise AES-256 session keys",
            channelAliceToBob.pairwiseKey.contentEquals(channelBobToAlice.pairwiseKey)
        )

        // Alice seals, Bob opens
        val secretMessage = "Pairwise channel handshake test".toByteArray()
        val aad = byteArrayOf(0x04, 0x00, 0x01)
        val sealed = channelAliceToBob.seal(secretMessage, aad)

        val openedByBob = channelBobToAlice.open(sealed.nonce, sealed.ciphertext, sealed.tag, aad)
        assertTrue(openedByBob.isSuccess)
        assertTrue(secretMessage.contentEquals(openedByBob.getOrThrow()))
    }

    @Test
    fun testSealedInvite_opensOnlyWithInviteesKey() {
        val adminKeySign = SoftwareEcProvider.generateKeyPair()
        val adminKeyEcdh = SoftwareEcProvider.generateKeyPair()
        val adminPubBytes = EcProvider.encodeUncompressedPublicKey(adminKeySign.public)
        val adminFp = EcProvider.computeSenderFp(adminPubBytes)

        val inviteeKeyEcdh = SoftwareEcProvider.generateKeyPair()
        val eavesdropperKeyEcdh = SoftwareEcProvider.generateKeyPair()

        // Admin derives pairwise channel with invitee
        val adminToInviteeChannel = PairwiseChannel.derive(adminKeyEcdh.private, inviteeKeyEcdh.public)
        val inviteeChannel = PairwiseChannel.derive(inviteeKeyEcdh.private, adminKeyEcdh.public)
        val eavesdropperChannel = PairwiseChannel.derive(eavesdropperKeyEcdh.private, adminKeyEcdh.public)

        val groupKey = ByteArray(32) { 0x99.toByte() }
        val dummyUnsigned = RosterCodec.encodeForSigning(1234L, 1, adminFp, emptyList<RosterMemberInfo>())
        val dummySig = Ecdsa.sign(adminKeySign.private, dummyUnsigned)
        val dummyRoster = SignedRoster(1234L, 1, adminFp, emptyList(), dummySig)

        val invitePayload = InvitePayload(
            groupId = 1234L,
            groupKey = groupKey,
            keyEpoch = 1,
            signedRoster = dummyRoster
        )

        val inviteFrame = InviteService.buildInvitePacket(
            adminFp = adminFp,
            adminSigningPrivateKey = adminKeySign.private,
            inviteePairwiseChannel = adminToInviteeChannel,
            invitePayload = invitePayload,
            sentAtMs = 1700000000000L
        )

        // 1. Legitimate invitee opens packet -> SUCCESS
        val openResult = InviteService.openInvitePacket(inviteFrame, adminPubBytes, inviteeChannel)
        assertTrue("Invitee must successfully open invite", openResult.isSuccess)
        val (header, parsedInvite) = openResult.getOrThrow()
        assertEquals(1234L, parsedInvite.groupId)
        assertTrue(groupKey.contentEquals(parsedInvite.groupKey))

        // 2. Eavesdropper tries to open packet -> FAILURE (AuthFailed)
        val eavesdropperResult = InviteService.openInvitePacket(inviteFrame, adminPubBytes, eavesdropperChannel)
        assertTrue("Eavesdropper must fail to open invite", eavesdropperResult.isFailure)
    }
}
