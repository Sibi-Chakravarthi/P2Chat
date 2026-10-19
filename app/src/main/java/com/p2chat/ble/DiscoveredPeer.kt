package com.p2chat.ble

/**
 * Lifecycle connection state of a peer node across the BLE GATT mesh (NET-2, NET-14).
 */
enum class PeerConnectionState {
    CONNECTED,
    CONNECTING,
    QUEUED,
    DISCONNECTED
}

/**
 * Discovered BLE peer node metadata and live radio telemetry (NET-2, UI-2).
 */
data class DiscoveredPeer(
    val address: String,
    val alias: String? = null,
    val senderFp: Long? = null,
    val rssi: Int = -60,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val mtu: Int = 517,
    val connectionState: PeerConnectionState = PeerConnectionState.DISCONNECTED,
    val isVerified: Boolean = false,
    val roleDescription: String? = null
) {
    /**
     * RSSI level normalized to 1..4 tactical bars:
     * 4 bars: >= -65 dBm (Strong)
     * 3 bars: -66 to -75 dBm (Good)
     * 2 bars: -76 to -85 dBm (Fair)
     * 1 bar:  < -85 dBm (Weak)
     */
    val signalBars: Int
        get() = when {
            rssi >= -65 -> 4
            rssi >= -75 -> 3
            rssi >= -85 -> 2
            else -> 1
        }

    val displayAlias: String
        get() = alias ?: "Node_${address.takeLast(5).replace(":", "")}"

    val shortTag: String
        get() = if (senderFp != null) {
            "%04X".format(senderFp and 0xFFFF)
        } else {
            address.takeLast(5)
        }
}
