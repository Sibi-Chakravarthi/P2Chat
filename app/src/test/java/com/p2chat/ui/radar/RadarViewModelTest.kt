package com.p2chat.ui.radar

import com.p2chat.ble.BleState
import com.p2chat.ble.ConnectionPool
import com.p2chat.ble.DefaultConnectionPool
import com.p2chat.ble.DefaultMeshController
import com.p2chat.ble.DefaultMeshScanner
import com.p2chat.ble.DiscoveredPeer
import com.p2chat.ble.PeerConnectionState
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.mesh.BufferedPacket
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.util.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeBufferedPacketDao : BufferedPacketDao {
        val countFlow = MutableStateFlow(342)

        override suspend fun insertIgnore(packet: BufferedPacket): Long = 1L
        override suspend fun delete(msgId: Long): Int = 1
        override suspend fun purgeExpired(nowMs: Long): Int = 0
        override suspend fun countBuffer(): Int = countFlow.value
        override fun observeBufferCount(): Flow<Int> = countFlow
        override suspend fun allMsgIds(): List<Long> = emptyList()
        override suspend fun getPacket(msgId: Long): BufferedPacket? = null
        override suspend fun getPacketsToForward(limit: Int): List<BufferedPacket> = emptyList()
        override suspend fun clearAll(): Int = 0
    }

    private class FakePeerDao : PeerDao {
        override suspend fun insertRaw(peer: Peer): Long = 1L
        override suspend fun getPeer(senderFp: Long): Peer? = null
        override fun observeAllPeers(): Flow<List<Peer>> = flowOf(emptyList())
        override suspend fun getAllPeers(): List<Peer> = emptyList()
        override suspend fun updateVerification(senderFp: Long, verified: Boolean): Int = 1
        override suspend fun updateAlias(senderFp: Long, alias: String): Int = 1
        override suspend fun deletePeer(senderFp: Long): Int = 1
        override suspend fun count(): Int = 0
    }

    private lateinit var meshScanner: DefaultMeshScanner
    private lateinit var connectionPool: DefaultConnectionPool
    private lateinit var meshController: DefaultMeshController
    private lateinit var bufferDao: FakeBufferedPacketDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var clock: FakeClock
    private lateinit var viewModel: RadarViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        meshScanner = DefaultMeshScanner()
        connectionPool = DefaultConnectionPool()
        meshController = DefaultMeshController()
        bufferDao = FakeBufferedPacketDao()
        peerDao = FakePeerDao()
        clock = FakeClock(1700000000000L)

        viewModel = RadarViewModel(
            meshScanner = meshScanner,
            connectionPool = connectionPool,
            meshController = meshController,
            bufferedPacketDao = bufferDao,
            peerDao = peerDao,
            clock = clock,
            processId = 8492
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState_reflectsMeshActiveAndBufferOccupancy() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertTrue(state.isMeshActive)
        assertTrue(state.isScanning)
        assertTrue(state.isBeaconActive)
        assertEquals(342, state.bufferOccupancy)
        assertEquals(34.2f, state.bufferPercent, 0.01f)
        assertEquals(116L, state.cachedBytesKb) // 342 * 350 / 1024 = 116 KB
        assertEquals(8492, state.pid)
        assertEquals(BleState.READY, state.bleState)
    }

    @Test
    fun testToggleMesh_pausesAndResumesRadio() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMeshActive)

        // Stop mesh
        viewModel.toggleMesh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isMeshActive)
        assertFalse(viewModel.uiState.value.isScanning)

        // Resume mesh
        viewModel.toggleMesh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMeshActive)
        assertTrue(viewModel.uiState.value.isScanning)
    }

    @Test
    fun testToggleBeacon_togglesPresenceBroadcast() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isBeaconActive)

        viewModel.toggleBeacon()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBeaconActive)

        viewModel.toggleBeacon()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isBeaconActive)
    }

    @Test
    fun testDiscoveredPeers_reflectsRssiAndConnectionState() = runTest(testDispatcher) {
        advanceUntilIdle()

        val peer1 = DiscoveredPeer(
            address = "C4:7B:12:88:9A:01",
            alias = "Kestrel-9",
            senderFp = 0x8F4C,
            rssi = -58,
            mtu = 517,
            connectionState = PeerConnectionState.CONNECTED,
            isVerified = true
        )
        val peer2 = DiscoveredPeer(
            address = "E2:11:90:3A:4C:55",
            alias = "Cipher-Relay",
            senderFp = 0x3A4C,
            rssi = -72,
            mtu = 517,
            connectionState = PeerConnectionState.CONNECTING
        )

        meshScanner.updatePeer(peer1)
        meshScanner.updatePeer(peer2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.peers.size)

        val first = state.peers[0]
        assertEquals("Kestrel-9", first.alias)
        assertEquals(-58, first.rssi)
        assertEquals(4, first.signalBars)
        assertEquals(PeerConnectionState.CONNECTED, first.connectionState)
        assertTrue(first.isVerified)

        val second = state.peers[1]
        assertEquals("Cipher-Relay", second.alias)
        assertEquals(-72, second.rssi)
        assertEquals(3, second.signalBars)
        assertEquals(PeerConnectionState.CONNECTING, second.connectionState)
    }

    @Test
    fun testBleStateUpdate_pausesScanningWhenUnavailable() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.setBleState(BleState.BLUETOOTH_OFF)
        advanceUntilIdle()
        assertEquals(BleState.BLUETOOTH_OFF, viewModel.uiState.value.bleState)
        assertFalse(viewModel.uiState.value.isScanning)

        viewModel.setBleState(BleState.READY)
        advanceUntilIdle()
        assertEquals(BleState.READY, viewModel.uiState.value.bleState)
        assertTrue(viewModel.uiState.value.isScanning)
    }

    @Test
    fun testConnectPeer_initiatesAndDisconnectsGatt() = runTest(testDispatcher) {
        advanceUntilIdle()
        val address = "FA:88:31:02:5B:19"

        viewModel.connectPeer(address)
        advanceUntilIdle()
        assertEquals(PeerConnectionState.CONNECTING, connectionPool.getConnectionState(address))

        // Set to connected
        connectionPool.setConnectionState(address, PeerConnectionState.CONNECTED)
        advanceUntilIdle()
        assertEquals(PeerConnectionState.CONNECTED, connectionPool.getConnectionState(address))

        // Toggle again to disconnect
        viewModel.connectPeer(address)
        advanceUntilIdle()
        assertEquals(PeerConnectionState.DISCONNECTED, connectionPool.getConnectionState(address))
    }
}
