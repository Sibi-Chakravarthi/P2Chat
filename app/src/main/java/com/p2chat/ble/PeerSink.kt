package com.p2chat.ble

/**
 * Output sink representing an active connected BLE peer (T2.4, NET-6, NET-7).
 */
interface PeerSink {
    /**
     * Unique identifier or MAC address of the connected peer.
     */
    val peerId: String

    /**
     * Transmits a raw frame over the BLE connection.
     * Returns true if queued or delivered successfully, false if transmission failed.
     */
    suspend fun sendFrame(frameBytes: ByteArray): Boolean
}
