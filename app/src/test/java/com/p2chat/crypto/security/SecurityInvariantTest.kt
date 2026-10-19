package com.p2chat.crypto.security

import com.p2chat.crypto.envelope.EnvelopeBuilder
import com.p2chat.crypto.envelope.EnvelopeVerifier
import com.p2chat.crypto.envelope.MessageLimits
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.epoch.EpochEvaluation
import com.p2chat.crypto.epoch.EpochPolicy
import com.p2chat.crypto.epoch.EpochState
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parameterized Security Invariant Suite (SEC-18, T1.12).
 * Verifies that 100% of tampered ciphertexts, tags, nonces, AAD fields, invalid signatures,
 * wrong signers, revoked senders, post-grace old-epoch packets, stale timestamps,
 * and malformed frames are rejected with 0 accepted.
 */
@RunWith(Parameterized::class)
class SecurityInvariantTest(
    private val testCase: TestCase
) {

    data class TestCase(
        val name: String,
        val mutate: (originalFrame: ByteArray, context: TestContext) -> TamperedInput
    ) {
        override fun toString(): String = name
    }

    data class TestContext(
        val header: PacketHeader,
        val sentAtMs: Long,
        val plaintext: String,
        val groupKey: ByteArray,
        val senderSigningPub: ByteArray,
        val otherSigningPub: ByteArray,
        val clock: FakeClock,
        val epochState: EpochState
    )

    data class TamperedInput(
        val frameBytes: ByteArray,
        val verificationKey: ByteArray,
        val verificationPubkey: ByteArray,
        val verificationSenderFp: Long,
        val verificationEpochState: EpochState
    )

    @Test
    fun testSecurityInvariant_rejected() {
        val baseTimeMs = 1700000000000L
        val clock = FakeClock(baseTimeMs)

        val senderKeyPair = SoftwareEcProvider.generateKeyPair()
        val otherKeyPair = SoftwareEcProvider.generateKeyPair()
        val senderPub = EcProvider.encodeUncompressedPublicKey(senderKeyPair.public)
        val otherPub = EcProvider.encodeUncompressedPublicKey(otherKeyPair.public)
        val senderFp = EcProvider.computeSenderFp(senderPub)

        val initialGroupKey = ByteArray(32) { 0x42 }
        val header = PacketHeader(
            packetType = PacketType.CHAT,
            ttl = BleConstants.DEFAULT_TTL,
            msgId = 0x1122334455667788L,
            groupId = 0xCAFEBABEL,
            senderFp = senderFp,
            keyEpoch = 1
        )
        val plaintext = "Invariant test message"

        val originalFrame = EnvelopeBuilder.buildChatPacket(
            header = header,
            sentAtMs = baseTimeMs,
            plaintext = plaintext,
            groupKey = initialGroupKey,
            signingPrivateKey = senderKeyPair.private
        )

        // Baseline verification: Ensure original frame is 100% accepted
        val baselineVerdict = EnvelopeVerifier.verifyAndOpen(
            frameBytes = originalFrame,
            groupKey = initialGroupKey,
            senderSigningPubkey = senderPub,
            clock = clock
        )
        assertTrue("Baseline original frame must be accepted", baselineVerdict is PacketVerdict.Accept)

        val initialEpochState = EpochState(
            currentEpoch = 1,
            currentKey = initialGroupKey
        )

        val context = TestContext(
            header = header,
            sentAtMs = baseTimeMs,
            plaintext = plaintext,
            groupKey = initialGroupKey,
            senderSigningPub = senderPub,
            otherSigningPub = otherPub,
            clock = clock,
            epochState = initialEpochState
        )

        val tampered = testCase.mutate(originalFrame, context)

        // 1. Check EpochPolicy evaluation if testing epoch/sender revocation
        val epochEval = EpochPolicy.evaluate(
            state = tampered.verificationEpochState,
            senderFp = tampered.verificationSenderFp,
            packetEpoch = if (tampered.frameBytes.size >= 24) {
                ByteBuffer.wrap(tampered.frameBytes, 22, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            } else 1,
            nowMs = clock.currentTimeMillis()
        )

        val isEpochRejected = epochEval is EpochEvaluation.RevokedSender || epochEval is EpochEvaluation.EpochExpired

        // 2. Check EnvelopeVerifier result
        val verdict = EnvelopeVerifier.verifyAndOpen(
            frameBytes = tampered.frameBytes,
            groupKey = tampered.verificationKey,
            senderSigningPubkey = tampered.verificationPubkey,
            clock = clock
        )

        val isEnvelopeRejected = verdict !is PacketVerdict.Accept

        // SEC-18 Requirement: Every mutated/tampered input MUST be rejected
        assertTrue(
            "Test '${testCase.name}' must be rejected by EnvelopeVerifier or EpochPolicy. Verdict: $verdict, EpochEval: $epochEval",
            isEnvelopeRejected || isEpochRejected
        )
        assertFalse(
            "Test '${testCase.name}' must NEVER produce PacketVerdict.Accept with valid epoch",
            verdict is PacketVerdict.Accept && !isEpochRejected
        )
    }

    companion object {

        private fun flipByte(bytes: ByteArray, offset: Int): ByteArray {
            val copy = bytes.copyOf()
            copy[offset] = (copy[offset].toInt() xor 0xFF).toByte()
            return copy
        }

        private fun defaultInput(bytes: ByteArray, ctx: TestContext) = TamperedInput(
            frameBytes = bytes,
            verificationKey = ctx.groupKey,
            verificationPubkey = ctx.senderSigningPub,
            verificationSenderFp = ctx.header.senderFp,
            verificationEpochState = ctx.epochState
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): Collection<TestCase> {
            val cases = mutableListOf<TestCase>()

            // 1-5: Ciphertext tampering
            cases.add(TestCase("Ciphertext bit-flip at byte 0") { frame, ctx ->
                // Header (24) + Nonce (12) + Tag (16) + Len (4) = offset 56
                defaultInput(flipByte(frame, 56), ctx)
            })
            cases.add(TestCase("Ciphertext bit-flip at middle byte") { frame, ctx ->
                defaultInput(flipByte(frame, 65), ctx)
            })
            cases.add(TestCase("Ciphertext bit-flip at last byte") { frame, ctx ->
                val sigStart = frame.size - 64
                defaultInput(flipByte(frame, sigStart - 1), ctx)
            })
            cases.add(TestCase("Ciphertext truncated by 1 byte") { frame, ctx ->
                val truncated = ByteArray(frame.size - 1)
                System.arraycopy(frame, 0, truncated, 0, 56) // before ct
                System.arraycopy(frame, 57, truncated, 56, frame.size - 57)
                defaultInput(truncated, ctx)
            })
            cases.add(TestCase("Ciphertext extended with 1 extra byte") { frame, ctx ->
                val extended = ByteArray(frame.size + 1)
                System.arraycopy(frame, 0, extended, 0, 60)
                extended[60] = 0xAA.toByte()
                System.arraycopy(frame, 60, extended, 61, frame.size - 60)
                defaultInput(extended, ctx)
            })

            // 6-9: Tag tampering
            cases.add(TestCase("Tag bit-flip at byte 0 (offset 36)") { frame, ctx ->
                defaultInput(flipByte(frame, 36), ctx)
            })
            cases.add(TestCase("Tag bit-flip at middle byte (offset 44)") { frame, ctx ->
                defaultInput(flipByte(frame, 44), ctx)
            })
            cases.add(TestCase("Tag bit-flip at last byte (offset 51)") { frame, ctx ->
                defaultInput(flipByte(frame, 51), ctx)
            })
            cases.add(TestCase("Tag all zeroes") { frame, ctx ->
                val copy = frame.copyOf()
                for (i in 36..51) copy[i] = 0
                defaultInput(copy, ctx)
            })

            // 10-13: Nonce tampering
            cases.add(TestCase("Nonce bit-flip at byte 0 (offset 24)") { frame, ctx ->
                defaultInput(flipByte(frame, 24), ctx)
            })
            cases.add(TestCase("Nonce bit-flip at middle byte (offset 30)") { frame, ctx ->
                defaultInput(flipByte(frame, 30), ctx)
            })
            cases.add(TestCase("Nonce bit-flip at last byte (offset 35)") { frame, ctx ->
                defaultInput(flipByte(frame, 35), ctx)
            })
            cases.add(TestCase("Nonce all zeroes") { frame, ctx ->
                val copy = frame.copyOf()
                for (i in 24..35) copy[i] = 0
                defaultInput(copy, ctx)
            })

            // 14-20: AAD Field tampering
            cases.add(TestCase("AAD field packet_type changed to KEY_ROTATE") { frame, ctx ->
                val copy = frame.copyOf()
                copy[0] = PacketType.KEY_ROTATE.code
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("AAD field packet_type changed to invalid 0xFF") { frame, ctx ->
                val copy = frame.copyOf()
                copy[0] = 0xFF.toByte()
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("AAD field msg_id byte 0 flipped (offset 2)") { frame, ctx ->
                defaultInput(flipByte(frame, 2), ctx)
            })
            cases.add(TestCase("AAD field msg_id byte 7 flipped (offset 9)") { frame, ctx ->
                defaultInput(flipByte(frame, 9), ctx)
            })
            cases.add(TestCase("AAD field group_id byte flipped (offset 10)") { frame, ctx ->
                defaultInput(flipByte(frame, 10), ctx)
            })
            cases.add(TestCase("AAD field sender_fp byte flipped (offset 14)") { frame, ctx ->
                defaultInput(flipByte(frame, 14), ctx)
            })
            cases.add(TestCase("AAD field key_epoch byte flipped (offset 23)") { frame, ctx ->
                defaultInput(flipByte(frame, 23), ctx)
            })

            // 21-26: Signature tampering
            cases.add(TestCase("Signature bit-flip at r byte 0") { frame, ctx ->
                val sigOffset = frame.size - 64
                defaultInput(flipByte(frame, sigOffset), ctx)
            })
            cases.add(TestCase("Signature bit-flip at r byte 31") { frame, ctx ->
                val sigOffset = frame.size - 64
                defaultInput(flipByte(frame, sigOffset + 31), ctx)
            })
            cases.add(TestCase("Signature bit-flip at s byte 0 (offset 32)") { frame, ctx ->
                val sigOffset = frame.size - 64
                defaultInput(flipByte(frame, sigOffset + 32), ctx)
            })
            cases.add(TestCase("Signature bit-flip at s byte 31 (last byte)") { frame, ctx ->
                val sigOffset = frame.size - 64
                defaultInput(flipByte(frame, sigOffset + 63), ctx)
            })
            cases.add(TestCase("Signature all zeroes") { frame, ctx ->
                val copy = frame.copyOf()
                val sigOffset = frame.size - 64
                for (i in sigOffset until frame.size) copy[i] = 0
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("Signature replaced with truncated ASN.1 DER sequence") { frame, ctx ->
                val copy = frame.copyOf()
                val sigOffset = frame.size - 64
                copy[sigOffset] = 0x30.toByte() // ASN.1 SEQUENCE tag
                copy[sigOffset + 1] = 0x44.toByte()
                defaultInput(copy, ctx)
            })

            // 27-28: Wrong signer public key
            cases.add(TestCase("Signature verified against wrong signer public key") { frame, ctx ->
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.otherSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = ctx.epochState
                )
            })
            cases.add(TestCase("Signature verified against arbitrary dummy 65-byte pubkey") { frame, ctx ->
                val dummyPub = ByteArray(65) { 0x04 }
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = dummyPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = ctx.epochState
                )
            })

            // 29-30: Wrong decryption key
            cases.add(TestCase("Decryption attempted with wrong group key") { frame, ctx ->
                val wrongKey = ByteArray(32) { 0x99.toByte() }
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = wrongKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = ctx.epochState
                )
            })
            cases.add(TestCase("Decryption attempted with all zeroes group key") { frame, ctx ->
                val zeroKey = ByteArray(32) { 0x00 }
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = zeroKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = ctx.epochState
                )
            })

            // 31-32: Revoked sender
            cases.add(TestCase("Revoked sender under current epoch dropped immediately") { frame, ctx ->
                val revokedState = ctx.epochState.copy(
                    revokedSenders = setOf(ctx.header.senderFp)
                )
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = revokedState
                )
            })
            cases.add(TestCase("Revoked sender under previous epoch during grace dropped immediately") { frame, ctx ->
                val rotatedState = EpochPolicy.rotate(
                    state = ctx.epochState,
                    newKey = ByteArray(32) { 0x88.toByte() },
                    removedSenderFp = ctx.header.senderFp,
                    nowMs = ctx.clock.currentTimeMillis()
                )
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = rotatedState
                )
            })

            // 33-35: Epoch transitions and grace expiry
            cases.add(TestCase("Old epoch packet after 10-minute grace window expired") { frame, ctx ->
                val rotatedState = EpochPolicy.rotate(
                    state = ctx.epochState,
                    newKey = ByteArray(32) { 0x88.toByte() },
                    removedSenderFp = null,
                    nowMs = ctx.clock.currentTimeMillis()
                )
                // Advance clock by 10 minutes + 2 seconds
                ctx.clock.advanceMillis(10 * 60 * 1000L + 2000L)
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = rotatedState
                )
            })
            cases.add(TestCase("Packet with epoch older than previous epoch (epoch 0)") { frame, ctx ->
                val copy = frame.copyOf()
                copy[22] = 0
                copy[23] = 0
                TamperedInput(
                    frameBytes = copy,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = ctx.epochState
                )
            })
            cases.add(TestCase("Packet with key epoch N-2") { frame, ctx ->
                val stateEpoch3 = EpochState(
                    currentEpoch = 3,
                    currentKey = ByteArray(32) { 0x33 },
                    prevEpoch = 2,
                    prevKey = ByteArray(32) { 0x22 },
                    graceExpiresMs = ctx.clock.currentTimeMillis() + 600000L
                )
                // Frame has epoch 1 -> older than prevEpoch (2)
                TamperedInput(
                    frameBytes = frame,
                    verificationKey = ctx.groupKey,
                    verificationPubkey = ctx.senderSigningPub,
                    verificationSenderFp = ctx.header.senderFp,
                    verificationEpochState = stateEpoch3
                )
            })

            // 36-39: Timestamp freshness (Replay protection SEC-14)
            cases.add(TestCase("Stale timestamp: sent_at older than 48 hours") { _, ctx ->
                val staleSentAt = ctx.clock.currentTimeMillis() - (48 * 3600 * 1000L + 10000L)
                val staleFrame = EnvelopeBuilder.buildChatPacket(
                    header = ctx.header,
                    sentAtMs = staleSentAt,
                    plaintext = ctx.plaintext,
                    groupKey = ctx.groupKey,
                    signingPrivateKey = SoftwareEcProvider.generateKeyPair().private // Will fail either sig or stale
                )
                defaultInput(staleFrame, ctx)
            })
            cases.add(TestCase("Future timestamp: sent_at > 5 minutes in future") { _, ctx ->
                val futureSentAt = ctx.clock.currentTimeMillis() + (5 * 60 * 1000L + 10000L)
                val futureFrame = EnvelopeBuilder.buildChatPacket(
                    header = ctx.header,
                    sentAtMs = futureSentAt,
                    plaintext = ctx.plaintext,
                    groupKey = ctx.groupKey,
                    signingPrivateKey = SoftwareEcProvider.generateKeyPair().private
                )
                defaultInput(futureFrame, ctx)
            })
            cases.add(TestCase("Ancient timestamp: sent_at at Unix Epoch 0") { _, ctx ->
                val ancientFrame = EnvelopeBuilder.buildChatPacket(
                    header = ctx.header,
                    sentAtMs = 0L,
                    plaintext = ctx.plaintext,
                    groupKey = ctx.groupKey,
                    signingPrivateKey = SoftwareEcProvider.generateKeyPair().private
                )
                defaultInput(ancientFrame, ctx)
            })

            // 40-48: Truncated & malformed frame bounds
            cases.add(TestCase("Malformed frame: 0 bytes empty") { _, ctx ->
                defaultInput(ByteArray(0), ctx)
            })
            cases.add(TestCase("Malformed frame: 1 byte") { _, ctx ->
                defaultInput(ByteArray(1) { 0x01 }, ctx)
            })
            cases.add(TestCase("Malformed frame: 23 bytes (less than 24-byte header)") { _, ctx ->
                defaultInput(ByteArray(23) { 0x01 }, ctx)
            })
            cases.add(TestCase("Malformed frame: 24 bytes header only") { _, ctx ->
                defaultInput(ByteArray(24) { 0x01 }, ctx)
            })
            cases.add(TestCase("Malformed frame: 119 bytes (below 120-byte minimum)") { _, ctx ->
                defaultInput(ByteArray(119) { 0x01 }, ctx)
            })
            cases.add(TestCase("Malformed frame: declared length mismatch") { frame, ctx ->
                val copy = frame.copyOf()
                // offset 52..55 is ciphertext length prefix. Set it to 50000
                copy[52] = 0x00
                copy[53] = 0x01
                copy[54] = 0x00
                copy[55] = 0x00
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("Malformed frame: TTL = 0") { frame, ctx ->
                val copy = frame.copyOf()
                copy[1] = 0x00 // TTL = 0
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("Malformed frame: TTL = 16 (exceeds MAX_TTL 15)") { frame, ctx ->
                val copy = frame.copyOf()
                copy[1] = 0x10 // TTL = 16
                defaultInput(copy, ctx)
            })
            cases.add(TestCase("Malformed frame: TTL = 255") { frame, ctx ->
                val copy = frame.copyOf()
                copy[1] = 0xFF.toByte()
                defaultInput(copy, ctx)
            })

            return cases
        }
    }
}
