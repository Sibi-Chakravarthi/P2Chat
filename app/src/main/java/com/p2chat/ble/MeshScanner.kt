package com.p2chat.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for BLE GATT mesh discovery and periodic RSSI scans (NET-2, T2.7, UI-2).
 */
interface MeshScanner {
    val discoveredPeers: StateFlow<List<DiscoveredPeer>>
    val isScanning: StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun updatePeer(peer: DiscoveredPeer)
    fun removePeer(address: String)
    fun purgeStalePeers(maxAgeMs: Long = 30_000L)
    fun clear()
}

/**
 * Thread-safe default in-memory MeshScanner implementation.
 */
class DefaultMeshScanner : MeshScanner {
    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    override fun startScan() {
        _isScanning.value = true
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun updatePeer(peer: DiscoveredPeer) {
        // Handle MAC address rotation: if senderFp matches an existing peer with a different address,
        // prune the old MAC address entry to prevent duplicate ghost nodes in UI (Issue 3).
        if (peer.senderFp != null) {
            val staleEntry = peersMap.entries.firstOrNull { it.value.senderFp == peer.senderFp && it.key != peer.address }
            if (staleEntry != null) {
                peersMap.remove(staleEntry.key)
            }
        }
        val existing = peersMap[peer.address]
        val updated = if (existing != null) {
            existing.copy(
                alias = peer.alias ?: existing.alias,
                senderFp = peer.senderFp ?: existing.senderFp,
                rssi = peer.rssi,
                lastSeenMs = peer.lastSeenMs,
                mtu = if (peer.mtu != 517) peer.mtu else existing.mtu,
                connectionState = peer.connectionState,
                isVerified = peer.isVerified || existing.isVerified,
                roleDescription = peer.roleDescription ?: existing.roleDescription
            )
        } else {
            peer
        }
        peersMap[peer.address] = updated
        _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
    }

    override fun removePeer(address: String) {
        peersMap.remove(address)
        _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
    }

    override fun purgeStalePeers(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        var changed = false
        peersMap.entries.removeIf { entry ->
            val peer = entry.value
            val isStale = (now - peer.lastSeenMs > maxAgeMs) &&
                    (peer.connectionState == PeerConnectionState.DISCONNECTED)
            if (isStale) {
                changed = true
            }
            isStale
        }
        if (changed) {
            _discoveredPeers.value = peersMap.values.toList().sortedByDescending { it.rssi }
        }
    }

    override fun clear() {
        peersMap.clear()
        _discoveredPeers.value = emptyList()
    }
}
