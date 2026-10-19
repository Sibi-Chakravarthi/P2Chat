package com.p2chat.crypto.group

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.crypto.prim.Ecdsa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAndRosterTest {

    @Test
    fun testRosterCodec_roundTripSerialization() {
        val adminKey = SoftwareEcProvider.generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKey.public)
        val adminFp = EcProvider.computeSenderFp(adminPub)

        val members = listOf(
            RosterMemberInfo(adminFp, adminPub, adminPub, "Admin", false),
            RosterMemberInfo(12345L, ByteArray(65) { 1 }, ByteArray(65) { 2 }, "Bob", false),
            RosterMemberInfo(67890L, ByteArray(65) { 3 }, ByteArray(65) { 4 }, "Charlie", true)
        )

        val unsignedBytes = RosterCodec.encodeForSigning(42L, 1, adminFp, members)
        val signature = Ecdsa.sign(adminKey.private, unsignedBytes)
        val signedRoster = SignedRoster(42L, 1, adminFp, members, signature)

        val encodedBytes = RosterCodec.encode(signedRoster)
        val decodedResult = RosterCodec.decode(encodedBytes)

        assertTrue(decodedResult.isSuccess)
        val decoded = decodedResult.getOrThrow()
        assertEquals(signedRoster, decoded)
    }

    @Test
    fun testRosterVerifier_rejectsOutdatedVersion() {
        val adminKey = SoftwareEcProvider.generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKey.public)
        val adminFp = EcProvider.computeSenderFp(adminPub)

        val members = listOf(RosterMemberInfo(adminFp, adminPub, adminPub, "Admin", false))

        // Create roster with version 2
        val unsignedV2 = RosterCodec.encodeForSigning(10L, 2, adminFp, members)
        val sigV2 = Ecdsa.sign(adminKey.private, unsignedV2)
        val rosterV2 = SignedRoster(10L, 2, adminFp, members, sigV2)

        // If current version is already 2, proposing version 2 is rejected
        val verdictSame = RosterVerifier.verify(rosterV2, adminPub, currentRosterVersion = 2)
        assertTrue(verdictSame is RosterVerifier.RosterVerdict.OutdatedVersion)

        // Proposing version 1 when current is 2 is rejected
        val unsignedV1 = RosterCodec.encodeForSigning(10L, 1, adminFp, members)
        val sigV1 = Ecdsa.sign(adminKey.private, unsignedV1)
        val rosterV1 = SignedRoster(10L, 1, adminFp, members, sigV1)

        val verdictOlder = RosterVerifier.verify(rosterV1, adminPub, currentRosterVersion = 2)
        assertTrue(verdictOlder is RosterVerifier.RosterVerdict.OutdatedVersion)

        // Version 3 when current is 2 is accepted
        val unsignedV3 = RosterCodec.encodeForSigning(10L, 3, adminFp, members)
        val sigV3 = Ecdsa.sign(adminKey.private, unsignedV3)
        val rosterV3 = SignedRoster(10L, 3, adminFp, members, sigV3)

        val verdictNewer = RosterVerifier.verify(rosterV3, adminPub, currentRosterVersion = 2)
        assertTrue(verdictNewer is RosterVerifier.RosterVerdict.Valid)
    }

    @Test
    fun testRosterVerifier_rejectsForgedSignature() {
        val adminKey = SoftwareEcProvider.generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKey.public)
        val adminFp = EcProvider.computeSenderFp(adminPub)

        val members = listOf(RosterMemberInfo(adminFp, adminPub, adminPub, "Admin", false))
        val unsignedBytes = RosterCodec.encodeForSigning(10L, 2, adminFp, members)

        // Impostor signs instead of admin
        val impostorKey = SoftwareEcProvider.generateKeyPair()
        val forgedSig = Ecdsa.sign(impostorKey.private, unsignedBytes)
        val forgedRoster = SignedRoster(10L, 2, adminFp, members, forgedSig)

        val verdict = RosterVerifier.verify(forgedRoster, adminPub, currentRosterVersion = 1)
        assertTrue("Forged admin signature must be rejected", verdict is RosterVerifier.RosterVerdict.InvalidSignature)
    }

    @Test
    fun testRosterVerifier_rejectsMoreThan16Members() {
        val adminKey = SoftwareEcProvider.generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKey.public)
        val adminFp = EcProvider.computeSenderFp(adminPub)

        // 17 members
        val seventeenMembers = (1..17).map {
            RosterMemberInfo(it.toLong(), ByteArray(65) { 1 }, ByteArray(65) { 2 }, "User$it", false)
        }

        val dummySig = ByteArray(64)
        val rosterWith17 = SignedRoster(10L, 2, adminFp, seventeenMembers, dummySig)

        val verdict = RosterVerifier.verify(rosterWith17, adminPub, currentRosterVersion = 1)
        assertTrue("More than 16 members must be rejected", verdict is RosterVerifier.RosterVerdict.ExceedsMaxMembers)
    }

    @Test
    fun testKeyWrapping_storedGroupKeyIsEncryptedBlob() {
        val keyVault = KeyVault()
        val rawKey = ByteArray(32) { 0x77 }

        val wrapped = keyVault.wrap(rawKey)
        assertFalse("Wrapped key must differ from raw key bytes", wrapped.contentEquals(rawKey))
        assertEquals(12 + 32 + 16, wrapped.size) // 60 bytes

        val unwrapped = keyVault.unwrap(wrapped)
        assertTrue("Unwrapped key must match raw key", rawKey.contentEquals(unwrapped))
    }
}
