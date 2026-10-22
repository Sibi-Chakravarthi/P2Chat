package com.p2chat.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.ble.BleState
import com.p2chat.ble.ConnectionPool
import com.p2chat.ble.DiscoveredPeer
import com.p2chat.ble.MeshController
import com.p2chat.ble.MeshScanner
import com.p2chat.ble.PeerConnectionState
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.util.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI model representing a peer node in the Radar view (NET-2, UI-2).
 */
data class DiscoveredPeerUiModel(
    val address: String,
    val alias: String,
    val shortTag: String,
    val rssi: Int,
    val signalBars: Int,
    val connectionState: PeerConnectionState,
    val mtu: Int,
    val isVerified: Boolean,
    val roleDescription: String,
    val lastSeenMs: Long
)

/**
 * State representing the Mesh Radar and Peer Discovery screen (T4.7, NET-2, NET-8, NET-13, UI-2).
 */
data class RadarUiState(
    val isMeshActive: Boolean = true,
    val isBeaconActive: Boolean = true,
    val isScanning: Boolean = true,
    val peersMap: Map<String, DiscoveredPeerUiModel> = emptyMap(),
    val peers: List<DiscoveredPeerUiModel> = emptyList(),
    val bufferOccupancy: Int = 0,
    val bufferMax: Int = 1000,
    val bufferPercent: Float = 0f,
    val cachedBytesKb: Long = 0L,
    val bleState: BleState = BleState.READY,
    val pid: Int = 0,
    val estimatedPowerMw: Int = 14
)

/**
 * ViewModel driving Mesh Radar peer discovery, buffer telemetry, and radio controls (T4.7).
 */
class RadarViewModel(
    private val meshScanner: MeshScanner,
    private val connectionPool: ConnectionPool,
    private val meshController: MeshController,
    private val bufferedPacketDao: BufferedPacketDao,
    private val peerDao: PeerDao,
    private val clock: Clock = Clock.System,
    private val processId: Int = try { android.os.Process.myPid() } catch (_: Throwable) { 8492 }
) : ViewModel() {

    private val _bleState = MutableStateFlow(BleState.READY)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private data class RadioState(
        val isMeshActive: Boolean,
        val isBeaconActive: Boolean,
        val isScanning: Boolean,
        val bleState: BleState
    )

    private val radioStateFlow = combine(
        meshController.isMeshActive,
        meshController.isBeaconBroadcasting,
        meshScanner.isScanning,
        _bleState
    ) { isMeshActive, isBeaconActive, isScanning, bleState ->
        RadioState(isMeshActive, isBeaconActive, isScanning, bleState)
    }

    private val peerModelsFlow = combine(
        meshScanner.discoveredPeers,
        connectionPool.connectionStates
    ) { rawPeers, connStates ->
        val map = LinkedHashMap<String, DiscoveredPeerUiModel>()
        rawPeers.forEach { peer ->
            val connState = connStates[peer.address] ?: peer.connectionState
            val roleDesc = peer.roleDescription ?: when (connState) {
                PeerConnectionState.CONNECTED -> "1-Hop direct"
                PeerConnectionState.CONNECTING -> "Relay (TTL 4)"
                PeerConnectionState.QUEUED -> "Edge sync"
                PeerConnectionState.DISCONNECTED -> if (peer.isVerified) "Direct link" else "Raw AD packet"
            }

            val persistentKey = peer.senderFp?.toString() ?: peer.address
            val existing = map[persistentKey]
            if (existing == null || peer.lastSeenMs >= existing.lastSeenMs) {
                map[persistentKey] = DiscoveredPeerUiModel(
                    address = peer.address,
                    alias = peer.displayAlias,
                    shortTag = peer.shortTag,
                    rssi = peer.rssi,
                    signalBars = peer.signalBars,
                    connectionState = connState,
                    mtu = peer.mtu,
                    isVerified = peer.isVerified,
                    roleDescription = roleDesc,
                    lastSeenMs = peer.lastSeenMs
                )
            }
        }
        map
    }

    val uiState: StateFlow<RadarUiState> = combine(
        radioStateFlow,
        peerModelsFlow,
        bufferedPacketDao.observeBufferCount()
    ) { radio, peerMap, bufferCount ->
        val occupancy = bufferCount.coerceIn(0, 1000)
        val percent = (occupancy.toFloat() / 1000f) * 100f
        val cachedKb = (occupancy.toLong() * 350L) / 1024L
        val peerList = peerMap.values.toList().sortedByDescending { it.rssi }

        RadarUiState(
            isMeshActive = radio.isMeshActive,
            isBeaconActive = radio.isBeaconActive,
            isScanning = radio.isScanning && radio.isMeshActive,
            peersMap = peerMap,
            peers = peerList,
            bufferOccupancy = occupancy,
            bufferMax = 1000,
            bufferPercent = percent,
            cachedBytesKb = cachedKb,
            bleState = radio.bleState,
            pid = processId,
            estimatedPowerMw = if (radio.isMeshActive) 14 else 2
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = RadarUiState(pid = processId)
    )

    init {
        if (meshController.isMeshActive.value) {
            meshScanner.startScan()
        }
    }

    fun toggleMesh() {
        val current = meshController.isMeshActive.value
        if (current) {
            meshController.stopMesh()
            meshScanner.stopScan()
            viewModelScope.launch { _snackbarEvent.emit("BLE mesh radio paused.") }
        } else {
            meshController.startMesh()
            meshScanner.startScan()
            viewModelScope.launch { _snackbarEvent.emit("BLE mesh radio activated.") }
        }
    }

    fun toggleBeacon() {
        val current = meshController.isBeaconBroadcasting.value
        meshController.setBeaconBroadcasting(!current)
        viewModelScope.launch {
            if (!current) {
                _snackbarEvent.emit("Presence beacon broadcasting started.")
            } else {
                _snackbarEvent.emit("Presence beacon disabled.")
            }
        }
    }

    fun setBleState(newState: BleState) {
        _bleState.value = newState
        if (newState != BleState.READY && meshScanner.isScanning.value) {
            meshScanner.stopScan()
        } else if (newState == BleState.READY && meshController.isMeshActive.value) {
            meshScanner.startScan()
        }
    }

    fun connectPeer(address: String) {
        val current = connectionPool.getConnectionState(address)
        if (current == PeerConnectionState.CONNECTED || current == PeerConnectionState.CONNECTING) {
            connectionPool.setConnectionState(address, PeerConnectionState.DISCONNECTED)
            try {
                com.p2chat.ble.MeshService.sharedGattManager?.disconnectPeer(address)
            } catch (_: Throwable) {}
            viewModelScope.launch { _snackbarEvent.emit("Disconnected from $address") }
        } else {
            connectionPool.setConnectionState(address, PeerConnectionState.CONNECTING)
            viewModelScope.launch { _snackbarEvent.emit("Initiating GATT connection to $address...") }
        }
    }
}
