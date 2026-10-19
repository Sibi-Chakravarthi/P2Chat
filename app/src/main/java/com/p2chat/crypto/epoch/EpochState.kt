package com.p2chat.crypto.epoch

/**
 * In-memory representation of group epoch state and active keys (SEC-12, SEC-13, T1.10).
 */
data class EpochState(
    val currentEpoch: Int,
    val currentKey: ByteArray,
    val prevEpoch: Int? = null,
    val prevKey: ByteArray? = null,
    val graceExpiresMs: Long? = null,
    val revokedSenders: Set<Long> = emptySet()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EpochState

        if (currentEpoch != other.currentEpoch) return false
        if (!currentKey.contentEquals(other.currentKey)) return false
        if (prevEpoch != other.prevEpoch) return false
        if (prevKey != null) {
            if (other.prevKey == null) return false
            if (!prevKey.contentEquals(other.prevKey)) return false
        } else if (other.prevKey != null) return false
        if (graceExpiresMs != other.graceExpiresMs) return false
        if (revokedSenders != other.revokedSenders) return false

        return true
    }

    override fun hashCode(): Int {
        var result = currentEpoch
        result = 31 * result + currentKey.contentHashCode()
        result = 31 * result + (prevEpoch ?: 0)
        result = 31 * result + (prevKey?.contentHashCode() ?: 0)
        result = 31 * result + (graceExpiresMs?.hashCode() ?: 0)
        result = 31 * result + revokedSenders.hashCode()
        return result
    }
}

sealed class EpochEvaluation {
    data class UseKey(val key: ByteArray, val epoch: Int) : EpochEvaluation()
    object RevokedSender : EpochEvaluation()
    object EpochExpired : EpochEvaluation()
    object HoldFutureEpoch : EpochEvaluation()
}
