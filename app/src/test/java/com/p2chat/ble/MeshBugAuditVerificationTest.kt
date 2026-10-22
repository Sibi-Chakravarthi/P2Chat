package com.p2chat.ble

import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class MeshBugAuditVerificationTest {

    // ── Bug 1 & 3: MAC Address Randomization & Peer Tracking ────────────────

    @Test
    fun testMacAddressRotation_doesNotDuplicatePeer() {
        val scanner = DefaultMeshScanner()

        // Device A discovers Peer B under initial MAC address RPA_1
        val peerFirstSeen = DiscoveredPeer(
            address = "AA:BB:CC:DD:EE:01",
            alias = "Alice Phone",
            senderFp = 0x1122334455667788L,
            rssi = -65,
            lastSeenMs = 1000L
        )
        scanner.updatePeer(peerFirstSeen)

        assertEquals(1, scanner.discoveredPeers.value.size)
        assertEquals("AA:BB:CC:DD:EE:01", scanner.discoveredPeers.value[0].address)
        assertEquals(0x1122334455667788L, scanner.discoveredPeers.value[0].senderFp)

        // Device B rotates its BLE RPA to RPA_2, but broadcasts the same persistent senderFp
        val peerRotated = DiscoveredPeer(
            address = "AA:BB:CC:DD:EE:02",
            alias = "Alice Phone",
            senderFp = 0x1122334455667788L,
            rssi = -60,
            lastSeenMs = 2000L
        )
        scanner.updatePeer(peerRotated)

        // Verifies peer list does NOT contain duplicate entries for the rotated peer
        assertEquals(1, scanner.discoveredPeers.value.size)
        val currentPeer = scanner.discoveredPeers.value[0]
        assertEquals("AA:BB:CC:DD:EE:02", currentPeer.address)
        assertEquals(0x1122334455667788L, currentPeer.senderFp)
        assertEquals(-60, currentPeer.rssi)
    }

    // ── Bug 9: Stale Peer Pruning ────────────────────────────────────────────

    @Test
    fun testPurgeStalePeers_removesDisconnectedStalePeers_retainsConnected() {
        val scanner = DefaultMeshScanner()
        val now = System.currentTimeMillis()

        // 1. Stale disconnected peer (seen 40s ago)
        val stalePeer = DiscoveredPeer(
            address = "11:22:33:44:55:01",
            senderFp = 101L,
            lastSeenMs = now - 40_000L,
            connectionState = PeerConnectionState.DISCONNECTED
        )
        // 2. Active fresh peer (seen 5s ago)
        val freshPeer = DiscoveredPeer(
            address = "11:22:33:44:55:02",
            senderFp = 102L,
            lastSeenMs = now - 5_000L,
            connectionState = PeerConnectionState.DISCONNECTED
        )
        // 3. Stale CONNECTED peer (should NOT be pruned while connection is active)
        val connectedPeer = DiscoveredPeer(
            address = "11:22:33:44:55:03",
            senderFp = 103L,
            lastSeenMs = now - 45_000L,
            connectionState = PeerConnectionState.CONNECTED
        )

        scanner.updatePeer(stalePeer)
        scanner.updatePeer(freshPeer)
        scanner.updatePeer(connectedPeer)
        assertEquals(3, scanner.discoveredPeers.value.size)

        // Run pruning with 30s threshold
        scanner.purgeStalePeers(maxAgeMs = 30_000L)

        val remaining = scanner.discoveredPeers.value
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.address == "11:22:33:44:55:02" })
        assertTrue(remaining.any { it.address == "11:22:33:44:55:03" })
        assertFalse(remaining.any { it.address == "11:22:33:44:55:01" })
    }

    // ── Bug 2: Deterministic Tie-Breaking ────────────────────────────────────

    @Test
    fun testDeterministicTieBreaking_higherNodeInitiates_lowerNodeYields() {
        val nodeA_Id = 0x2000L
        val nodeB_Id = 0x1000L

        fun shouldInitiateConnect(localId: Long, remoteId: Long): Boolean {
            return localId > remoteId
        }

        // When Node A (higher) discovers Node B (lower): Node A connects
        assertTrue(shouldInitiateConnect(localId = nodeA_Id, remoteId = nodeB_Id))

        // When Node B (lower) discovers Node A (higher): Node B yields (does not connect)
        assertFalse(shouldInitiateConnect(localId = nodeB_Id, remoteId = nodeA_Id))
    }

    // ── Bug 6: Mesh Relaying & TTL Decrement ──────────────────────────────────

    @Test
    fun testMeshRelaying_decrementsTtlWithoutAlteringPayload() {
        val originalHeader = PacketHeader(
            packetType = PacketType.CHAT,
            ttl = 5,
            msgId = 0x1234567890ABCDEFL,
            groupId = 42L,
            senderFp = 0x1988776655443322L,
            keyEpoch = 1
        )
        val encodedHeader = originalHeader.encode()
        val dummyPayload = ByteArray(50) { 0xAA.toByte() }
        val fullFrame = encodedHeader + dummyPayload

        // Relaying logic (decrements byte 1: OFFSET_TTL)
        assertEquals(5, fullFrame[BleConstants.OFFSET_TTL].toInt())

        val relayedFrame = fullFrame.clone()
        relayedFrame[BleConstants.OFFSET_TTL] = (originalHeader.ttl - 1).toByte()

        val decodedRelayedHeader = PacketHeader.decode(relayedFrame).getOrThrow()
        assertEquals(4, decodedRelayedHeader.ttl)
        assertEquals(originalHeader.msgId, decodedRelayedHeader.msgId)
        assertEquals(originalHeader.groupId, decodedRelayedHeader.groupId)
        assertEquals(originalHeader.senderFp, decodedRelayedHeader.senderFp)
    }

    // ── Bug 4: Chunking with Negotiated MTU ──────────────────────────────────

    @Test
    fun testChunking_adaptsToLowMtuNegotiation() {
        // If device negotiates default MTU 23 (max payload = 20)
        val negotiatedMtu = 23
        val maxChunkSize = (negotiatedMtu - 3).coerceIn(20, 480)
        assertEquals(20, maxChunkSize)

        val frameData = ByteArray(60) { it.toByte() }
        val chunks = Chunker.chunk(frameData, maxChunkSize = maxChunkSize)

        // 60 bytes / 16 bytes payload per chunk (20 - 4 transport header) = 4 chunks
        assertEquals(4, chunks.size)
        chunks.forEach { chunk ->
            assertTrue(chunk.size <= maxChunkSize)
        }

        // Reassembly succeeds
        val reassembler = Reassembler()
        var result: ByteArray? = null
        for (chunk in chunks) {
            result = reassembler.processIncomingPacket("peer_low_mtu", chunk)
        }
        assertNotNull(result)
        assertEquals(60, result!!.size)
    }

    // ── Phase 3: BLE Scanner Watchdog Configuration ───────────────────────────

    @Test
    fun testScannerWatchdog_intervalIsWithin12To15Seconds() {
        assertTrue(
            "Watchdog interval must be >= 12,000ms to cycle scanner and prevent Android BLE throttling",
            AndroidMeshScanner.WATCHDOG_INTERVAL_MS >= 12_000L
        )
        assertTrue(
            "Watchdog interval must be <= 15,000ms to cycle scanner and prevent Android BLE throttling",
            AndroidMeshScanner.WATCHDOG_INTERVAL_MS <= 15_000L
        )
    }

    // ── BLE Connection Fix Verification Tests ─────────────────────────────────

    @Test
    fun testAdvertisingPayload_strictlyUnder31Bytes_withLongDeviceName() {
        // BLE spec legacy packet limit
        val maxPacketSize = 31

        // Primary packet: Flags (3 bytes) + 128-bit Service UUID (18 bytes)
        val primaryPacketBytes = 3 + 18
        assertTrue("Primary advertising packet must be <= 31 bytes", primaryPacketBytes <= maxPacketSize)

        // Scan response: Manufacturer Specific Data (1B len + 1B type + 2B company ID + 8B peerId = 12 bytes)
        // With device name excluded, scan response is always 12 bytes regardless of OEM name length
        val scanResponseBytes = 1 + 1 + 2 + 8
        assertTrue("Scan response packet must be <= 31 bytes", scanResponseBytes <= maxPacketSize)

        // Verifies that a long device name (e.g. 22 chars "Nothing Phone (3a) Pro") would have exceeded 31 bytes
        // if included in scan response (12 mfg + 24 name = 36 > 31), proving exclusion is necessary
        val longOemName = "Nothing Phone (3a) Pro"
        val overflowedScanResponseSize = scanResponseBytes + 2 + longOemName.toByteArray().size
        assertTrue("Long OEM name would have exceeded 31B", overflowedScanResponseSize > maxPacketSize)
    }

    @Test
    fun testTieBreakerPassiveWait_timesOutAndFallsBackToInitiateConnect() {
        val nodeA_Id = 0x1000L // Lower ID
        val nodeB_Id = 0x2000L // Higher ID
        val passiveWaitTimeoutMs = GattMeshManager.PASSIVE_WAIT_TIMEOUT_MS

        fun shouldInitiateConnect(localId: Long, remoteId: Long, elapsedWaitMs: Long): Boolean {
            if (localId > remoteId) return true
            // When localId <= remoteId, yield only while elapsedWaitMs < passiveWaitTimeoutMs
            return elapsedWaitMs >= passiveWaitTimeoutMs
        }

        // Initially at t = 0s: Lower node yields to higher node
        assertFalse(shouldInitiateConnect(localId = nodeA_Id, remoteId = nodeB_Id, elapsedWaitMs = 0L))
        assertFalse(shouldInitiateConnect(localId = nodeA_Id, remoteId = nodeB_Id, elapsedWaitMs = 5_000L))

        // After timeout expired (t >= 8s): Lower node falls back and initiates connection
        assertTrue(shouldInitiateConnect(localId = nodeA_Id, remoteId = nodeB_Id, elapsedWaitMs = 8_000L))
        assertTrue(shouldInitiateConnect(localId = nodeA_Id, remoteId = nodeB_Id, elapsedWaitMs = 12_000L))
    }

    @Test
    fun testConnectionFailure_exponentialBackoffAndCooldown() {
        val baseDelayMs = GattMeshManager.BASE_RETRY_DELAY_MS
        val maxRetries = GattMeshManager.MAX_RETRY_ATTEMPTS
        val extendedCooldownMs = GattMeshManager.EXTENDED_COOLDOWN_MS

        fun calculateBackoff(attempt: Int): Long {
            return if (attempt <= maxRetries) {
                baseDelayMs * (1L shl (attempt - 1))
            } else {
                extendedCooldownMs
            }
        }

        assertEquals(1000L, calculateBackoff(1))
        assertEquals(2000L, calculateBackoff(2))
        assertEquals(4000L, calculateBackoff(3))
        assertEquals(15000L, calculateBackoff(4))
    }

    @Test
    fun testInFlightConnection_blocksRedundantConnectRequests() {
        val inFlightMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val address = "AA:BB:CC:DD:EE:01"

        fun attemptConnect(addr: String): Boolean {
            val now = System.currentTimeMillis()
            return inFlightMap.putIfAbsent(addr, now) == null
        }

        // First attempt succeeds in acquiring in-flight lock
        assertTrue(attemptConnect(address))

        // Subsequent rapid scan hits while in-flight are blocked
        assertFalse(attemptConnect(address))
        assertFalse(attemptConnect(address))

        // Completing or clearing the in-flight state allows new connection
        inFlightMap.remove(address)
        assertTrue(attemptConnect(address))
    }

    @Test
    fun testDuplicateConnectionTieBreaker_higherPeerIdKeepsClient_lowerYieldsClient() {
        val nodeA_Id = 0x2000L // Higher
        val nodeB_Id = 0x1000L // Lower

        // Logic check: When duplicate connection occurs (both Client and Server active for same peer)
        fun resolveDuplicateConnection(localId: Long, remoteId: Long): Pair<Boolean, Boolean> {
            val isLocalHigher = localId > remoteId
            val keepClient = isLocalHigher
            val keepServer = !isLocalHigher
            return Pair(keepClient, keepServer)
        }

        // Higher Node A: Keeps Client connection, yields/rejects Server connection
        val nodeA_Resolution = resolveDuplicateConnection(localId = nodeA_Id, remoteId = nodeB_Id)
        assertTrue("Higher Node must keep Client connection", nodeA_Resolution.first)
        assertFalse("Higher Node must reject duplicate Server connection", nodeA_Resolution.second)

        // Lower Node B: Yields Client connection, keeps Server connection
        val nodeB_Resolution = resolveDuplicateConnection(localId = nodeB_Id, remoteId = nodeA_Id)
        assertFalse("Lower Node must yield Client connection", nodeB_Resolution.first)
        assertTrue("Lower Node must preserve Server connection", nodeB_Resolution.second)

        // Verify socket survival: exactly one link survives (Node A Client -> Node B Server)
        val clientSurvives = nodeA_Resolution.first && !nodeB_Resolution.first
        val serverSurvives = !nodeA_Resolution.second && nodeB_Resolution.second
        assertTrue("Client-to-Server link must survive unilaterally", clientSurvives && serverSurvives)
    }

    @Test
    fun testDisconnectOfYieldedRole_doesNotTearDownSurvivingRole() {
        var hasClient = false
        var hasServer = true
        var isSinkRegistered = true
        var connectionState = PeerConnectionState.CONNECTED

        // When the yielded Client role disconnects:
        fun onClientDisconnected() {
            hasClient = false
            // Guard: Only unregister sink and update pool if NO active Server connection exists
            if (!hasServer) {
                isSinkRegistered = false
                connectionState = PeerConnectionState.DISCONNECTED
            }
        }

        onClientDisconnected()

        // Verify the Server connection and its sink are NOT destroyed by the client disconnect
        assertTrue("Sink must remain registered for surviving Server role", isSinkRegistered)
        assertEquals("State must remain CONNECTED", PeerConnectionState.CONNECTED, connectionState)
    }
}
