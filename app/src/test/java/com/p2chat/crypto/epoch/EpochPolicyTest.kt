package com.p2chat.crypto.epoch

import com.p2chat.util.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochPolicyTest {

    @Test
    fun testGracePeriod_acceptedAt9Min59Sec_rejectedAt10Min1Sec() {
        val initialKey = ByteArray(32) { 0x11 }
        val rotatedKey = ByteArray(32) { 0x22 }
        val baseTimeMs = 1700000000000L
        val clock = FakeClock(baseTimeMs)

        var state = EpochState(
            currentEpoch = 1,
            currentKey = initialKey
        )

        // Rotate at baseTimeMs to epoch 2
        state = EpochPolicy.rotate(
            state = state,
            newKey = rotatedKey,
            removedSenderFp = null,
            nowMs = clock.currentTimeMillis()
        )

        assertEquals(2, state.currentEpoch)
        assertEquals(1, state.prevEpoch)
        val senderFp = 0xA1B2C3D4L

        // 1. Advance to 9 minutes 59 seconds (599,000 ms) -> ACCEPT with prevKey
        clock.advanceMillis(9 * 60 * 1000L + 59 * 1000L)
        val evalGrace = EpochPolicy.evaluate(state, senderFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertTrue("Epoch 1 message must be accepted within grace period", evalGrace is EpochEvaluation.UseKey)
        val useKeyGrace = evalGrace as EpochEvaluation.UseKey
        assertEquals(1, useKeyGrace.epoch)
        assertTrue(initialKey.contentEquals(useKeyGrace.key))

        // 2. Advance 2 more seconds -> total 10 minutes 1 second (601,000 ms) -> REJECT
        clock.advanceMillis(2000L)
        val evalExpired = EpochPolicy.evaluate(state, senderFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertEquals("Epoch 1 message must be rejected after 10-minute grace period lapses", EpochEvaluation.EpochExpired, evalExpired)

        // Pruning removes previous key after expiration
        val prunedState = EpochPolicy.pruneExpiredGrace(state, clock.currentTimeMillis())
        assertNull(prunedState.prevEpoch)
        assertNull(prunedState.prevKey)
    }

    @Test
    fun testRevokedSender_droppedImmediatelyAtSecondZero() {
        val keyEpoch1 = ByteArray(32) { 0x01 }
        val keyEpoch2 = ByteArray(32) { 0x02 }
        val baseTimeMs = 1700000000000L
        val clock = FakeClock(baseTimeMs)

        var state = EpochState(
            currentEpoch = 1,
            currentKey = keyEpoch1
        )

        val badActorFp = 0xDEADBEEFL
        val goodActorFp = 0xCAFEBABEFL

        // Rotate at second zero and revoke badActorFp
        state = EpochPolicy.rotate(
            state = state,
            newKey = keyEpoch2,
            removedSenderFp = badActorFp,
            nowMs = clock.currentTimeMillis()
        )

        // At second 0, bad actor is dropped immediately on BOTH epochs
        val evalBadActorPrev = EpochPolicy.evaluate(state, badActorFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertEquals("Revoked sender must be dropped immediately on epoch 1", EpochEvaluation.RevokedSender, evalBadActorPrev)

        val evalBadActorCurrent = EpochPolicy.evaluate(state, badActorFp, packetEpoch = 2, nowMs = clock.currentTimeMillis())
        assertEquals("Revoked sender must be dropped immediately on epoch 2", EpochEvaluation.RevokedSender, evalBadActorCurrent)

        // Non-revoked sender is accepted within grace on epoch 1 and current on epoch 2
        val evalGoodActorPrev = EpochPolicy.evaluate(state, goodActorFp, packetEpoch = 1, nowMs = clock.currentTimeMillis())
        assertTrue("Good actor must be accepted on epoch 1 within grace", evalGoodActorPrev is EpochEvaluation.UseKey)

        val evalGoodActorCurrent = EpochPolicy.evaluate(state, goodActorFp, packetEpoch = 2, nowMs = clock.currentTimeMillis())
        assertTrue("Good actor must be accepted on epoch 2", evalGoodActorCurrent is EpochEvaluation.UseKey)
    }

    @Test
    fun testFutureEpoch_heldThenReleasedUponRotation() {
        val key1 = ByteArray(32) { 0x11 }
        val key2 = ByteArray(32) { 0x22 }
        val nowMs = 1700000000000L

        var state = EpochState(currentEpoch = 1, currentKey = key1)
        val peerFp = 0x12345L

        // Incoming packet declared with future epoch 2 -> HOLD
        val evalFuture = EpochPolicy.evaluate(state, peerFp, packetEpoch = 2, nowMs = nowMs)
        assertEquals(EpochEvaluation.HoldFutureEpoch, evalFuture)

        // Rotation packet arrives and installs key2 for epoch 2
        state = EpochPolicy.rotate(state, key2, null, nowMs)

        // Now evaluate the same held packet -> ACCEPT with key2
        val evalReleased = EpochPolicy.evaluate(state, peerFp, packetEpoch = 2, nowMs = nowMs)
        assertTrue(evalReleased is EpochEvaluation.UseKey)
        val useKey = evalReleased as EpochEvaluation.UseKey
        assertEquals(2, useKey.epoch)
        assertTrue(key2.contentEquals(useKey.key))
    }

    @Test
    fun testHeldPacket_expiresAfter48Hours() {
        val storedAtMs = 1700000000000L
        val within48h = storedAtMs + (47 * 3600 * 1000L)
        val exactly48h = storedAtMs + (48 * 3600 * 1000L)
        val after48h1s = storedAtMs + (48 * 3600 * 1000L + 1000L)

        assertFalse(EpochPolicy.isHeldPacketExpired(storedAtMs, within48h))
        assertFalse(EpochPolicy.isHeldPacketExpired(storedAtMs, exactly48h))
        assertTrue(EpochPolicy.isHeldPacketExpired(storedAtMs, after48h1s))
    }
}
