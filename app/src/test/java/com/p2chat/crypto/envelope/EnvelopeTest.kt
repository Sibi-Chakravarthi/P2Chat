package com.p2chat.crypto.envelope

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.util.FakeClock
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStreamReader

class EnvelopeTest {

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun testAllPositiveVectors_verifyAndDecryptSuccessfully() {
        val stream = javaClass.classLoader?.getResourceAsStream("vectors/crypto_vectors.json")
            ?: error("crypto_vectors.json not found")
        val json = JSONObject(InputStreamReader(stream).readText())
        val vectors = json.getJSONArray("vectors")

        for (i in 0 until vectors.length()) {
            val vec = vectors.getJSONObject(i)
            if (vec.getString("tamper_mode") != "none") continue

            val crypto = vec.getJSONObject("crypto")
            val payload = vec.getJSONObject("payload")

            val frame = hexToBytes(vec.getString("frame_hex"))
            val groupKey = hexToBytes(crypto.getString("group_key_hex"))
            val pubKey = hexToBytes(crypto.getString("signing_pubkey_uncompressed_hex"))

            val sentAtMs = payload.getLong("sent_at_ms")
            val expectedText = payload.getString("plaintext")

            // Test clock aligned with vector timestamp
            val clock = FakeClock(sentAtMs)

            val verdict = EnvelopeVerifier.verifyAndOpen(frame, groupKey, pubKey, clock)
            assertTrue("Vector $i (${vec.getString("description")}) must be ACCEPT, got $verdict", verdict is PacketVerdict.Accept)

            val accepted = verdict as PacketVerdict.Accept
            assertEquals(sentAtMs, accepted.sentAtMs)
            assertEquals(expectedText, accepted.plaintext)
        }
    }

    @Test
    fun testAllNegativeVectors_returnExpectedVerdicts() {
        val stream = javaClass.classLoader?.getResourceAsStream("vectors/crypto_vectors.json")
            ?: error("crypto_vectors.json not found")
        val json = JSONObject(InputStreamReader(stream).readText())
        val vectors = json.getJSONArray("vectors")

        for (i in 0 until vectors.length()) {
            val vec = vectors.getJSONObject(i)
            if (vec.getString("tamper_mode") == "none") continue

            val expectedVerdictStr = vec.getString("expected_verdict")
            val crypto = vec.getJSONObject("crypto")
            val payload = vec.getJSONObject("payload")

            val frame = hexToBytes(vec.getString("frame_hex"))
            val groupKey = hexToBytes(crypto.getString("group_key_hex"))
            val pubKey = hexToBytes(crypto.getString("signing_pubkey_uncompressed_hex"))
            val sentAtMs = payload.getLong("sent_at_ms")
            val clock = FakeClock(sentAtMs)

            val verdict = EnvelopeVerifier.verifyAndOpen(frame, groupKey, pubKey, clock)

            val actualVerdictName = when (verdict) {
                is PacketVerdict.Accept -> "ACCEPT"
                is PacketVerdict.DropMalformed -> "DROP_MALFORMED"
                is PacketVerdict.DropTtl -> "DROP_TTL"
                is PacketVerdict.DropDuplicate -> "DROP_DUPLICATE"
                is PacketVerdict.NotMemberOpaque -> "NOT_MEMBER_OPAQUE"
                is PacketVerdict.HoldFutureEpoch -> "HOLD_FUTURE_EPOCH"
                is PacketVerdict.DropEpochExpired -> "DROP_EPOCH_EXPIRED"
                is PacketVerdict.DropUnknownSender -> "DROP_UNKNOWN_SENDER"
                is PacketVerdict.DropRevokedSender -> "DROP_REVOKED_SENDER"
                is PacketVerdict.DropBadSignature -> "DROP_BAD_SIGNATURE"
                is PacketVerdict.DropAuthFail -> "DROP_AUTH_FAIL"
                is PacketVerdict.DropStale -> "DROP_STALE"
            }

            assertEquals(
                "Negative vector $i (${vec.getString("description")}) expected $expectedVerdictStr, got $actualVerdictName",
                expectedVerdictStr,
                actualVerdictName
            )
        }
    }

    @Test
    fun testTtlDecrement_doesNotInvalidateSignatureOrGcm() {
        // SEC-5: Relays decrement TTL without invalidating cryptographic integrity
        val keyPair = SoftwareEcProvider.generateKeyPair()
        val pubBytes = EcProvider.encodeUncompressedPublicKey(keyPair.public)
        val groupKey = ByteArray(32) { 0x33 }
        val sentAtMs = 1700000000000L
        val text = "Relay hop test message"

        val header = PacketHeader(
            packetType = PacketType.CHAT,
            ttl = 7,
            msgId = 0x123456789L,
            groupId = 1L,
            senderFp = EcProvider.computeSenderFp(pubBytes),
            keyEpoch = 1
        )

        val frame = EnvelopeBuilder.buildChatPacket(
            header = header,
            sentAtMs = sentAtMs,
            plaintext = text,
            groupKey = groupKey,
            signingPrivateKey = keyPair.private
        )

        val clock = FakeClock(sentAtMs)

        // 1. Initial frame with TTL=7 verifies
        val verdict1 = EnvelopeVerifier.verifyAndOpen(frame, groupKey, pubBytes, clock)
        assertTrue(verdict1 is PacketVerdict.Accept)

        // 2. Decrement TTL to 6 (simulate hop 1)
        val hop1Frame = frame.copyOf()
        hop1Frame[1] = 6.toByte()

        val verdictHop1 = EnvelopeVerifier.verifyAndOpen(hop1Frame, groupKey, pubBytes, clock)
        assertTrue("Hop 1 (TTL=6) must verify despite modified TTL byte", verdictHop1 is PacketVerdict.Accept)
        assertEquals(6, (verdictHop1 as PacketVerdict.Accept).header.ttl)

        // 3. Decrement TTL to 1 (simulate last hop)
        val hopLastFrame = frame.copyOf()
        hopLastFrame[1] = 1.toByte()

        val verdictHopLast = EnvelopeVerifier.verifyAndOpen(hopLastFrame, groupKey, pubBytes, clock)
        assertTrue("Hop last (TTL=1) must verify", verdictHopLast is PacketVerdict.Accept)
        assertEquals(1, (verdictHopLast as PacketVerdict.Accept).header.ttl)
    }

    @Test
    fun testComposerBoundary_rejectsOver350Bytes() {
        val text350 = "A".repeat(350)
        val validBytes = MessageLimits.validatePlaintext(text350)
        assertEquals(350, validBytes.size)

        val text351 = "A".repeat(351)
        try {
            MessageLimits.validatePlaintext(text351)
            fail("MessageLimits must reject 351 bytes")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testStaleTimestamps_returnDropStale() {
        val keyPair = SoftwareEcProvider.generateKeyPair()
        val pubBytes = EcProvider.encodeUncompressedPublicKey(keyPair.public)
        val groupKey = ByteArray(32) { 0x44 }
        val senderFp = EcProvider.computeSenderFp(pubBytes)

        val baseNowMs = 1700000000000L
        val clock = FakeClock(baseNowMs)

        // 1. Fresh message at nowMs -> ACCEPT
        val header = PacketHeader(PacketType.CHAT, 7, 100L, 1L, senderFp, 1)
        val frameFresh = EnvelopeBuilder.buildChatPacket(header, baseNowMs, "Fresh", groupKey, keyPair.private)
        assertTrue(EnvelopeVerifier.verifyAndOpen(frameFresh, groupKey, pubBytes, clock) is PacketVerdict.Accept)

        // 2. 48h + 1s in the past -> DROP_STALE
        val pastStaleMs = baseNowMs - (48 * 3600 * 1000L + 1000L)
        val framePast = EnvelopeBuilder.buildChatPacket(header, pastStaleMs, "Past", groupKey, keyPair.private)
        val verdictPast = EnvelopeVerifier.verifyAndOpen(framePast, groupKey, pubBytes, clock)
        assertEquals(PacketVerdict.DropStale, verdictPast)

        // 3. 5 min + 1s in the future -> DROP_STALE
        val futureStaleMs = baseNowMs + (5 * 60 * 1000L + 1000L)
        val frameFuture = EnvelopeBuilder.buildChatPacket(header, futureStaleMs, "Future", groupKey, keyPair.private)
        val verdictFuture = EnvelopeVerifier.verifyAndOpen(frameFuture, groupKey, pubBytes, clock)
        assertEquals(PacketVerdict.DropStale, verdictFuture)
    }
}
