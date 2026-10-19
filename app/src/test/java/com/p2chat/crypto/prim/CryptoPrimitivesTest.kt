package com.p2chat.crypto.prim

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CryptoPrimitivesTest {

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testAesGcm_reproducesPythonVectors() {
        val stream = javaClass.classLoader?.getResourceAsStream("vectors/crypto_vectors.json")
            ?: error("crypto_vectors.json not found")
        val json = JSONObject(InputStreamReader(stream).readText())
        val vectors = json.getJSONArray("vectors")

        for (i in 0 until vectors.length()) {
            val vec = vectors.getJSONObject(i)
            if (vec.getString("tamper_mode") != "none") continue

            val crypto = vec.getJSONObject("crypto")
            val payload = vec.getJSONObject("payload")

            val groupKey = hexToBytes(crypto.getString("group_key_hex"))
            val nonce = hexToBytes(crypto.getString("nonce_hex"))
            val expectedTag = hexToBytes(crypto.getString("tag_hex"))
            val expectedCiphertext = hexToBytes(crypto.getString("ciphertext_hex"))
            val aad = hexToBytes(crypto.getString("aad_hex"))

            val sentAtMs = payload.getLong("sent_at_ms")
            val plaintext = payload.getString("plaintext")
            val innerPayload = ByteBuffer.allocate(8 + plaintext.toByteArray(Charsets.UTF_8).size)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(sentAtMs)
                .put(plaintext.toByteArray(Charsets.UTF_8))
                .array()

            // 1. Seal and compare against Python vector
            val sealed = AesGcm.seal(groupKey, innerPayload, aad, explicitNonce = nonce)
            assertEquals("Ciphertext mismatch for vector $i", expectedCiphertext.toHex(), sealed.ciphertext.toHex())
            assertEquals("Tag mismatch for vector $i", expectedTag.toHex(), sealed.tag.toHex())

            // 2. Open and compare decrypted output
            val opened = AesGcm.open(groupKey, nonce, sealed.ciphertext, sealed.tag, aad)
            assertTrue("Open should succeed for vector $i", opened.isSuccess)
            assertTrue("Decrypted payload matches", innerPayload.contentEquals(opened.getOrThrow()))
        }
    }

    @Test
    fun testEcdsa_verifiesPythonGeneratedSignatures() {
        val stream = javaClass.classLoader?.getResourceAsStream("vectors/crypto_vectors.json")
            ?: error("crypto_vectors.json not found")
        val json = JSONObject(InputStreamReader(stream).readText())
        val vectors = json.getJSONArray("vectors")

        for (i in 0 until vectors.length()) {
            val vec = vectors.getJSONObject(i)
            if (vec.getString("tamper_mode") != "none") continue

            val crypto = vec.getJSONObject("crypto")
            val pubKeyBytes = hexToBytes(crypto.getString("signing_pubkey_uncompressed_hex"))
            val signature = hexToBytes(crypto.getString("signature_hex"))
            val aad = hexToBytes(crypto.getString("aad_hex"))
            val nonce = hexToBytes(crypto.getString("nonce_hex"))
            val tag = hexToBytes(crypto.getString("tag_hex"))
            val ct = hexToBytes(crypto.getString("ciphertext_hex"))

            val ctLenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ct.size).array()
            val coreBlob = nonce + tag + ctLenBytes + ct
            val signedData = aad + coreBlob

            val valid = Ecdsa.verify(pubKeyBytes, signedData, signature)
            assertTrue("Python signature must verify for vector $i", valid)

            // Bit flip signature must fail
            val tamperedSig = signature.copyOf()
            tamperedSig[tamperedSig.size - 1] = (tamperedSig[tamperedSig.size - 1] xor 1).toByte()
            assertFalse("Tampered signature must not verify", Ecdsa.verify(pubKeyBytes, signedData, tamperedSig))
        }
    }

    @Test
    fun testAesGcm_tamperedInputsReturnAuthFailed() {
        val key = ByteArray(32) { 0x01 }
        val aad = byteArrayOf(0x01, 0x02, 0x03)
        val plaintext = "Authenticity integrity test".toByteArray()

        val sealed = AesGcm.seal(key, plaintext, aad)

        // 1. Wrong key
        val wrongKey = ByteArray(32) { 0x02 }
        val wrongKeyResult = AesGcm.open(wrongKey, sealed.nonce, sealed.ciphertext, sealed.tag, aad)
        assertTrue(wrongKeyResult.isFailure)

        // 2. Tampered tag
        val badTag = sealed.tag.copyOf()
        badTag[0] = (badTag[0] xor 1).toByte()
        val badTagResult = AesGcm.open(key, sealed.nonce, sealed.ciphertext, badTag, aad)
        assertTrue(badTagResult.isFailure)

        // 3. Tampered AAD
        val badAad = byteArrayOf(0x01, 0x02, 0x04)
        val badAadResult = AesGcm.open(key, sealed.nonce, sealed.ciphertext, sealed.tag, badAad)
        assertTrue(badAadResult.isFailure)

        // 4. Tampered ciphertext
        val badCt = sealed.ciphertext.copyOf()
        badCt[0] = (badCt[0] xor 1).toByte()
        val badCtResult = AesGcm.open(key, sealed.nonce, badCt, sealed.tag, aad)
        assertTrue(badCtResult.isFailure)
    }

    @Test
    fun testHkdfSha256_matchesKnownRfc5869Vector() {
        // RFC 5869 Test Case 1
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val okm = Hkdf.deriveKey(ikm, salt, info, 42)
        assertEquals(expectedOkm, bytesToHex(okm))
    }

    @Test
    fun testAesGcm_10000SealsProduceNoRepeatedNonces() {
        val key = ByteArray(32) { 0xAA.toByte() }
        val aad = ByteArray(0)
        val pt = byteArrayOf(0x01)

        val seenNonces = HashSet<String>(10000)
        for (i in 0 until 10000) {
            val sealed = AesGcm.seal(key, pt, aad)
            val nonceHex = bytesToHex(sealed.nonce)
            val added = seenNonces.add(nonceHex)
            assertTrue("Nonce collision detected at iteration $i", added)
        }
        assertEquals(10000, seenNonces.size)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private infix fun Byte.xor(other: Int): Int = (this.toInt() xor other)
}
