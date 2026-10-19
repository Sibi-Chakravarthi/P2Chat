package com.p2chat.crypto.epoch

import com.p2chat.util.Clock

/**
 * Pure logic state machine enforcing key epoch boundaries, rotation, grace periods,
 * and revoked sender isolation (SEC-12, SEC-13, PRD §5.2.1, T1.10).
 */
object EpochPolicy {

    const val GRACE_PERIOD_MS: Long = 10 * 60 * 1000L      // 10 minutes (SEC-12)
    const val MAX_FUTURE_HOLD_MS: Long = 48 * 60 * 60 * 1000L // 48 hours (SEC-13)

    /**
     * Evaluates which key to use or what drop reason to apply for an incoming frame.
     */
    fun evaluate(
        state: EpochState,
        senderFp: Long,
        packetEpoch: Int,
        nowMs: Long
    ): EpochEvaluation {
        // 1. Revoked senders are dropped immediately regardless of epoch (SEC-12)
        if (state.revokedSenders.contains(senderFp)) {
            return EpochEvaluation.RevokedSender
        }

        // 2. Current epoch is accepted immediately
        if (packetEpoch == state.currentEpoch) {
            return EpochEvaluation.UseKey(state.currentKey, state.currentEpoch)
        }

        // 3. Previous epoch within 10-minute grace window
        if (state.prevEpoch != null && packetEpoch == state.prevEpoch && state.prevKey != null) {
            val expires = state.graceExpiresMs
            return if (expires != null && nowMs <= expires) {
                EpochEvaluation.UseKey(state.prevKey, state.prevEpoch)
            } else {
                EpochEvaluation.EpochExpired
            }
        }

        // 4. Future epoch (packet epoch > current epoch) -> hold in buffer
        if (packetEpoch > state.currentEpoch) {
            return EpochEvaluation.HoldFutureEpoch
        }

        // 5. Older than previous epoch -> expired
        return EpochEvaluation.EpochExpired
    }

    /**
     * State transition: Rotates group key to next epoch N+1 upon member removal or admin trigger.
     */
    fun rotate(
        state: EpochState,
        newKey: ByteArray,
        removedSenderFp: Long?,
        nowMs: Long
    ): EpochState {
        val updatedRevoked = if (removedSenderFp != null) {
            state.revokedSenders + removedSenderFp
        } else {
            state.revokedSenders
        }

        return EpochState(
            currentEpoch = state.currentEpoch + 1,
            currentKey = newKey,
            prevEpoch = state.currentEpoch,
            prevKey = state.currentKey,
            graceExpiresMs = nowMs + GRACE_PERIOD_MS,
            revokedSenders = updatedRevoked
        )
    }

    /**
     * Checks if a buffered packet held for a future epoch has exceeded 48h expiration.
     */
    fun isHeldPacketExpired(storedAtMs: Long, nowMs: Long): Boolean {
        return (nowMs - storedAtMs) > MAX_FUTURE_HOLD_MS
    }

    /**
     * State transition: Prunes previous epoch key once the 10-minute grace window has elapsed.
     */
    fun pruneExpiredGrace(state: EpochState, nowMs: Long): EpochState {
        val expires = state.graceExpiresMs
        return if (expires != null && nowMs > expires) {
            state.copy(
                prevEpoch = null,
                prevKey = null,
                graceExpiresMs = null
            )
        } else {
            state
        }
    }
}
