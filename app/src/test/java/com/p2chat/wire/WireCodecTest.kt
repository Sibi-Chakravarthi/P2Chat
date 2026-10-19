package com.p2chat.wire

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.util.Random

class WireCodecTest {

    private fun loadTestVectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("vectors/crypto_vectors.json")
            ?: throw IllegalStateException("Resource vectors/crypto_vectors.json not found")
        val content = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        return JSONObject(content)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    @Test
    fun testChatFrameMeasuresExactly478BytesForMaxPlaintext() {
        // SEC-19: Max 350-byte text + 8-byte sent_at = 358-byte inner payload
        // 24 (Header) + 12 (Nonce) + 16 (Tag) + 4 (Len) + 358 (Ciphertext) + 64 (Sig) = 478 bytes
        val header = PacketHeader(
            packetType = PacketType.CHAT,
            ttl = BleConstants.DEFAULT_TTL,
            msgId = 0x123456789ABCDEFL,
            groupId = 1L,
            senderFp = 0xA1B2C3D4E5F60718UL.toLong(),
            keyEpoch = 1
        )
        val dummyNonce = ByteArray(BleConstants.GCM_NONCE_SIZE_BYTES) { 0x01 }
        val dummyTag = ByteArray(BleConstants.GCM_TAG_SIZE_BYTES) { 0x02 }
        val dummyCiphertext = ByteArray(BleConstants.MAX_INNER_PAYLOAD_BYTES) { 0x55 } // 358 bytes
        val dummySig = ByteArray(BleConstants.ECDSA_SIGNATURE_SIZE_BYTES) { 0x77 }

        val frame = WireFrame(
            header = header,
            coreBlob = CoreBlob(dummyNonce, dummyTag, dummyCiphertext),
            signature = dummySig
        )

        val encoded = WireCodec.encode(frame)
        assertEquals(478, encoded.size)
        assertEquals(BleConstants.CHAT_FRAME_SIZE_BYTES, encoded.size)
    }

    @Test
    fun testDecodeAllPositiveVectorsFromJson() {
        val root = loadTestVectors()
        val vectorsArray = root.getJSONArray("vectors")
        var positiveCount = 0

        for (i in 0 until vectorsArray.length()) {
            val vec = vectorsArray.getJSONObject(i)
            val tamperMode = vec.getString("tamper_mode")
            if (tamperMode != "none") continue

            positiveCount++
            val frameHex = vec.getString("frame_hex")
            val frameBytes = hexToBytes(frameHex)

            val decodeResult = WireCodec.decode(frameBytes)
            assertTrue("Failed to decode positive vector #${i + 1}: ${vec.getString("description")}", decodeResult.isSuccess)

            val frame = decodeResult.getOrThrow()
            val headerJson = vec.getJSONObject("header")

            assertEquals(headerJson.getInt("packet_type").toByte(), frame.header.packetType.code)
            assertEquals(headerJson.getInt("ttl"), frame.header.ttl)
            assertEquals(headerJson.getString("msg_id_hex"), String.format("%016x", frame.header.msgId))
            assertEquals(headerJson.getString("group_id_hex"), String.format("%08x", frame.header.groupId))
            assertEquals(headerJson.getString("sender_fp_hex"), String.format("%016x", frame.header.senderFp))
            assertEquals(headerJson.getInt("key_epoch"), frame.header.keyEpoch)

            val cryptoJson = vec.getJSONObject("crypto")
            assertEquals(cryptoJson.getString("nonce_hex"), bytesToHex(frame.coreBlob.nonce))
            assertEquals(cryptoJson.getString("tag_hex"), bytesToHex(frame.coreBlob.tag))
            assertEquals(cryptoJson.getInt("ciphertext_len"), frame.coreBlob.ciphertext.size)
            assertEquals(cryptoJson.getString("ciphertext_hex"), bytesToHex(frame.coreBlob.ciphertext))
            assertEquals(cryptoJson.getString("signature_hex"), bytesToHex(frame.signature))

            // Check AAD calculation parity
            val aadCalculated = frame.header.computeAad()
            assertEquals(cryptoJson.getString("aad_hex"), bytesToHex(aadCalculated))

            // Verify round-trip idempotency: decode(encode(frame)) == frame
            val reEncoded = WireCodec.encode(frame)
            assertArrayEquals(frameBytes, reEncoded)
            val reDecoded = WireCodec.decode(reEncoded).getOrThrow()
            assertEquals(frame, reDecoded)
        }

        assertTrue("Expected at least 20 positive vectors, got $positiveCount", positiveCount >= 20)
    }

    @Test
    fun testNegativeVectorsHandledCorrectly() {
        val root = loadTestVectors()
        val vectorsArray = root.getJSONArray("vectors")
        var negativeCount = 0

        for (i in 0 until vectorsArray.length()) {
            val vec = vectorsArray.getJSONObject(i)
            val tamperMode = vec.getString("tamper_mode")
            if (tamperMode == "none") continue

            negativeCount++
            val frameHex = vec.getString("frame_hex")
            val frameBytes = hexToBytes(frameHex)

            val decodeResult = WireCodec.decode(frameBytes)

            // Depending on the tamper mode, wire-level codec errors should be caught:
            when (tamperMode) {
                "ttl_zero", "ttl_exceeded", "unknown_packet_type", "truncated_frame", "len_overrun" -> {
                    assertTrue("Wire codec should fail on $tamperMode", decodeResult.isFailure)
                    val errorMsg = decodeResult.exceptionOrNull()?.message
                    assertNotNull(errorMsg)
                }
                "tamper_ciphertext", "tamper_tag", "tamper_nonce", "tamper_signature",
                "tamper_header_msg_id", "tamper_header_sender_fp", "tamper_header_key_epoch" -> {
                    // Frame framing itself is syntactically valid; crypto verifier will reject signature
                    assertTrue("Wire framing is syntactically well-formed for $tamperMode", decodeResult.isSuccess)
                }
            }
        }

        assertTrue("Expected at least 10 negative vectors, got $negativeCount", negativeCount >= 10)
    }

    @Test
    fun testFuzzing1000RandomByteStringsNeverThrowsUncaughtException() {
        val rng = Random(42) // Fixed seed for reproducible fuzzing
        for (i in 0 until 1000) {
            val length = rng.nextInt(1200) // 0 to 1199 bytes
            val randomBytes = ByteArray(length)
            rng.nextBytes(randomBytes)

            try {
                val result = WireCodec.decode(randomBytes)
                // If by some astronomical chance it successfully parsed, re-encode must not throw
                if (result.isSuccess) {
                    WireCodec.encode(result.getOrThrow())
                }
            } catch (t: Throwable) {
                throw AssertionError("Uncaught exception during fuzz test on frame size $length at iteration $i", t)
            }
        }
    }
}
