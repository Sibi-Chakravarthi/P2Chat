package com.p2chat.crypto.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

class EcProviderTest {

    @Test
    fun testSenderFingerprint_matchesVectorFromPython() {
        val pubkeyHex = "04d8cd12ea5c67f2f8a00c1124893edcfa6754c4d6cede6be13bdf2295c810a97fa5a89d2d2a360c0ca9a4d6c7c9ed4b28d3e199d6627f2e696d689c310a5b0f48"
        val expectedFpHex = "a7fdc9626f7aba83"

        val pubkeyBytes = pubkeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val calculatedFp = EcProvider.computeSenderFp(pubkeyBytes)
        val calculatedFpHex = EcProvider.senderFpHex(calculatedFp)

        assertEquals("Calculated fingerprint hex must match Python vector exactly", expectedFpHex, calculatedFpHex)
    }

    @Test
    fun testPublicKeyEncodeDecode_roundTrip() {
        val keyPair = SoftwareEcProvider.generateKeyPair()
        val encoded = EcProvider.encodeUncompressedPublicKey(keyPair.public)

        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])

        val decoded = EcProvider.decodeUncompressedPublicKey(encoded)
        assertNotNull(decoded)

        val reEncoded = EcProvider.encodeUncompressedPublicKey(decoded)
        assertTrue(encoded.contentEquals(reEncoded))
    }

    @Test
    fun testEcdhAgreement_producesIdenticalSharedSecret() {
        val alice = SoftwareEcProvider.generateKeyPair()
        val bob = SoftwareEcProvider.generateKeyPair()

        val secretAlice = SoftwareEcProvider.deriveSharedSecret(alice.private, bob.public)
        val secretBob = SoftwareEcProvider.deriveSharedSecret(bob.private, alice.public)

        assertEquals(32, secretAlice.size)
        assertEquals(32, secretBob.size)
        assertTrue("Alice and Bob must derive identical 32-byte shared secrets", secretAlice.contentEquals(secretBob))
    }

    @Test
    fun testKeyVault_wrappedKeyDoesNotParseAsRawEcPrivateKey() {
        val keyVault = KeyVault()
        val keyPair = SoftwareEcProvider.generateKeyPair()

        val rawPrivBytes = keyPair.private.encoded
        val wrappedBytes = keyVault.wrapPrivateKey(keyPair.private)

        assertFalse("Wrapped bytes must differ from raw encoded bytes", wrappedBytes.contentEquals(rawPrivBytes))

        // Attempting to parse wrapped bytes as raw PKCS8 EC key must fail
        val kf = KeyFactory.getInstance("EC")
        try {
            kf.generatePrivate(PKCS8EncodedKeySpec(wrappedBytes))
            fail("Parsing wrapped private key as raw EC key must fail!")
        } catch (e: Exception) {
            // Expected failure: wrapped blob is AES-256-GCM ciphertext
        }

        // Unwrapping restores the valid private key
        val unwrappedPriv = keyVault.unwrapPrivateKey(wrappedBytes)
        assertEquals(keyPair.private.algorithm, unwrappedPriv.algorithm)
        assertTrue(rawPrivBytes.contentEquals(unwrappedPriv.encoded))
    }
}
