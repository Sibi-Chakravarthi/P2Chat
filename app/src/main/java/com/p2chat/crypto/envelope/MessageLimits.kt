package com.p2chat.crypto.envelope

import com.p2chat.wire.BleConstants

/**
 * Protocol limits, message boundaries, and expiration windows (SEC-14, SEC-19).
 */
object MessageLimits {
    const val MAX_PLAINTEXT_BYTES = BleConstants.MAX_PLAINTEXT_BYTES       // 350 bytes
    const val MAX_INNER_PAYLOAD_BYTES = BleConstants.MAX_INNER_PAYLOAD_BYTES // 358 bytes
    const val INNER_TIMESTAMP_SIZE_BYTES = BleConstants.INNER_TIMESTAMP_SIZE_BYTES // 8 bytes
    const val CHAT_FRAME_SIZE_BYTES = BleConstants.CHAT_FRAME_SIZE_BYTES   // 478 bytes
    const val MAX_FRAME_SIZE_BYTES = BleConstants.MAX_FRAME_SIZE_BYTES     // 1024 bytes
    const val MAX_ROSTER_MEMBERS = BleConstants.MAX_ROSTER_MEMBERS         // 16 members
    const val DEFAULT_TTL = BleConstants.DEFAULT_TTL                       // 7
    const val MAX_TTL = BleConstants.MAX_TTL                               // 15

    // Timing bounds for replay and freshness protection (SEC-14)
    const val MAX_CLOCK_SKEW_FUTURE_MS = 5 * 60 * 1000L   // 5 minutes
    const val MAX_MESSAGE_AGE_MS = 48 * 60 * 60 * 1000L     // 48 hours

    fun validatePlaintext(plaintext: String): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT_BYTES) {
            "Plaintext message exceeds maximum allowed $MAX_PLAINTEXT_BYTES bytes (was ${bytes.size} bytes)"
        }
        return bytes
    }
}
