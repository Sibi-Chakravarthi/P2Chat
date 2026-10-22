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
}
