package com.p2chat

import com.p2chat.ai.FilterVerdict
import com.p2chat.ai.score.CategoryScore
import com.p2chat.ai.score.NearestSource
import com.p2chat.ai.text.TextNormalizer
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.ble.DefaultConnectionPool
import com.p2chat.ble.DefaultMeshController
import com.p2chat.ble.DefaultMeshScanner
import com.p2chat.ble.DiscoveredPeer
import com.p2chat.ble.PeerConnectionState
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.crypto.invite.PairwiseChannel
import com.p2chat.crypto.rotation.GroupRemoveMemberPayload
import com.p2chat.crypto.rotation.RotationService
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.mesh.BufferedPacket
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.pipeline.ControlPacketHandler
import com.p2chat.ui.radar.RadarViewModel
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import com.p2chat.wire.WireCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair

@OptIn(ExperimentalCoroutinesApi::class)
class Phase2BugAuditVerificationTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── Issue 1: Group Member Removal Verification ───────────────────────────

    private class MockGroupDao : GroupDao {
        val groups = mutableMapOf<Long, GroupEntity>()
        override suspend fun insertGroup(group: GroupEntity): Long {
            groups[group.groupId] = group
            return group.groupId
        }
        override suspend fun updateGroup(group: GroupEntity): Int {
            groups[group.groupId] = group
            return 1
        }
        override suspend fun getGroup(groupId: Long): GroupEntity? = groups[groupId]
        override fun observeGroup(groupId: Long): Flow<GroupEntity?> = flowOf(groups[groupId])
        override fun observeAllGroups(): Flow<List<GroupEntity>> = flowOf(groups.values.toList())
        override suspend fun getAllGroups(): List<GroupEntity> = groups.values.toList()
        override suspend fun deleteGroup(groupId: Long): Int = if (groups.remove(groupId) != null) 1 else 0
        override suspend fun count(): Int = groups.size
    }

    private class MockRosterMemberDao : RosterMemberDao {
        val members = mutableMapOf<Pair<Long, Long>, RosterMember>()
        override suspend fun insertOrUpdate(member: RosterMember): Long {
            members[Pair(member.groupId, member.senderFp)] = member
            return member.senderFp
        }
        override suspend fun insertAll(membersList: List<RosterMember>) {
            membersList.forEach { insertOrUpdate(it) }
        }
        override suspend fun getMember(groupId: Long, senderFp: Long): RosterMember? = members[Pair(groupId, senderFp)]
        override fun observeMembers(groupId: Long): Flow<List<RosterMember>> =
            flowOf(members.filterKeys { it.first == groupId }.values.toList())
        override suspend fun getMembers(groupId: Long): List<RosterMember> =
            members.filterKeys { it.first == groupId }.values.toList()
        override suspend fun getActiveMembers(groupId: Long): List<RosterMember> =
            members.filterKeys { it.first == groupId }.values.filter { !it.revoked }
        override suspend fun revokeMember(groupId: Long, senderFp: Long): Int {
            val m = members[Pair(groupId, senderFp)] ?: return 0
            members[Pair(groupId, senderFp)] = m.copy(revoked = true)
            return 1
        }
        override suspend fun deleteMember(groupId: Long, senderFp: Long): Int =
            if (members.remove(Pair(groupId, senderFp)) != null) 1 else 0
        override suspend fun deleteGroupRoster(groupId: Long): Int {
            val toRemove = members.keys.filter { it.first == groupId }
            toRemove.forEach { members.remove(it) }
            return toRemove.size
        }
        override suspend fun countActiveMembers(groupId: Long): Int =
            members.filterKeys { it.first == groupId }.values.count { !it.revoked }
    }

    private class MockMessageDao : MessageDao {
        val messages = mutableListOf<MessageEntity>()
        override suspend fun insertMessage(message: MessageEntity): Long {
            messages.add(message)
            return messages.size.toLong()
        }
        override suspend fun getMessage(msgId: Long): MessageEntity? = messages.find { it.msgId == msgId }
        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> =
            flowOf(messages.filter { it.groupId == groupId })
        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> =
            messages.filter { it.groupId == groupId }
        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int = 1
        override suspend fun deleteMessage(msgId: Long): Int = if (messages.removeIf { it.msgId == msgId }) 1 else 0
        override suspend fun deleteGroupMessages(groupId: Long): Int {
            val countBefore = messages.size
            messages.removeIf { it.groupId == groupId }
            return countBefore - messages.size
        }
        override suspend fun count(): Int = messages.size
        override suspend fun countForGroup(groupId: Long): Int = messages.count { it.groupId == groupId }
    }

    private class MockLocalIdentityDao(var identity: LocalIdentity?) : LocalIdentityDao {
        override suspend fun insertIdentity(identity: LocalIdentity): Long {
            this.identity = identity
            return 1L
        }
        override suspend fun getIdentity(): LocalIdentity? = identity
        override fun observeIdentity(): Flow<LocalIdentity?> = flowOf(identity)
        override suspend fun updateAlias(senderFp: Long, newAlias: String): Int {
            identity = identity?.copy(alias = newAlias)
            return 1
        }
        override suspend fun hasIdentity(): Boolean = identity != null
        override suspend fun clearIdentity(): Int {
            val existed = identity != null
            identity = null
            return if (existed) 1 else 0
        }
    }

    private class MockPeerDao : PeerDao {
        val peers = mutableMapOf<Long, Peer>()
        override suspend fun insertRaw(peer: Peer): Long {
            peers[peer.senderFp] = peer
            return peer.senderFp
        }
        override suspend fun getPeer(senderFp: Long): Peer? = peers[senderFp]
        override fun observeAllPeers(): Flow<List<Peer>> = flowOf(peers.values.toList())
        override suspend fun getAllPeers(): List<Peer> = peers.values.toList()
        override suspend fun updateVerification(senderFp: Long, verified: Boolean): Int = 1
        override suspend fun updateAlias(senderFp: Long, alias: String): Int = 1
        override suspend fun deletePeer(senderFp: Long): Int = if (peers.remove(senderFp) != null) 1 else 0
        override suspend fun count(): Int = peers.size
    }

    private class MockBufferedPacketDao : BufferedPacketDao {
        override suspend fun insertIgnore(packet: BufferedPacket): Long = 1L
        override suspend fun delete(msgId: Long): Int = 1
        override suspend fun purgeExpired(nowMs: Long): Int = 0
        override suspend fun countBuffer(): Int = 0
        override fun observeBufferCount(): Flow<Int> = flowOf(0)
        override suspend fun allMsgIds(): List<Long> = emptyList()
        override suspend fun getPacket(msgId: Long): BufferedPacket? = null
        override suspend fun getPacketsToForward(limit: Int): List<BufferedPacket> = emptyList()
        override suspend fun clearAll(): Int = 0
    }

    @Test
    fun testIssue1_GroupRemoveMemberPacket_buildAndOpenRoundTrip() {
        val adminSignKp = SoftwareEcProvider.generateKeyPair()
        val adminEcdhKp = SoftwareEcProvider.generateKeyPair()
        val adminSignPub = EcProvider.encodeUncompressedPublicKey(adminSignKp.public)
        val adminEcdhPub = EcProvider.encodeUncompressedPublicKey(adminEcdhKp.public)
        val adminFp = EcProvider.computeSenderFp(adminSignPub)

        val targetSignKp = SoftwareEcProvider.generateKeyPair()
        val targetEcdhKp = SoftwareEcProvider.generateKeyPair()
        val targetSignPub = EcProvider.encodeUncompressedPublicKey(targetSignKp.public)
        val targetEcdhPub = EcProvider.encodeUncompressedPublicKey(targetEcdhKp.public)
        val targetFp = EcProvider.computeSenderFp(targetSignPub)

        val adminToTargetChannel = PairwiseChannel.derive(adminEcdhKp.private, targetEcdhPub)
        val targetFromAdminChannel = PairwiseChannel.derive(targetEcdhKp.private, adminEcdhPub)

        val groupId = 4242L
        val nowMs = 1792600000000L
        val payload = GroupRemoveMemberPayload(groupId = groupId, removedSenderFp = targetFp, reason = "REMOVED_BY_ADMIN")

        // Admin builds removal frame
        val frameBytes = RotationService.buildGroupRemoveMemberPacket(
            adminFp = adminFp,
            adminSigningPrivateKey = adminSignKp.private,
            removedMemberPairwiseChannel = adminToTargetChannel,
            payload = payload,
            sentAtMs = nowMs
        )

        // Verify wire frame header
        val wireFrame = WireCodec.decode(frameBytes).getOrThrow()
        assertEquals(PacketType.GROUP_REMOVE_MEMBER, wireFrame.header.packetType)
        assertEquals(BleConstants.PACKET_TYPE_GROUP_REMOVE_MEMBER, wireFrame.header.packetType.code)
        assertEquals(adminFp, wireFrame.header.senderFp)
        assertEquals(groupId, wireFrame.header.groupId)

        // Target opens removal frame
        val openResult = RotationService.openGroupRemoveMemberPacket(
            frameBytes = frameBytes,
            adminSigningPubkey = adminSignPub,
            removedMemberPairwiseChannel = targetFromAdminChannel
        )
        assertTrue("Expected openGroupRemoveMemberPacket to succeed", openResult.isSuccess)
        val (header, decodedPayload) = openResult.getOrThrow()
        assertEquals(groupId, decodedPayload.groupId)
        assertEquals(targetFp, decodedPayload.removedSenderFp)
        assertEquals("REMOVED_BY_ADMIN", decodedPayload.reason)
    }

    @Test
    fun testIssue1_ControlPacketHandler_removesGroupAndKicksUserOnTargetDevice() = runTest(testDispatcher) {
        val keyVault = KeyVault()

        val adminSignKp = SoftwareEcProvider.generateKeyPair()
        val adminEcdhKp = SoftwareEcProvider.generateKeyPair()
        val adminSignPub = EcProvider.encodeUncompressedPublicKey(adminSignKp.public)
        val adminEcdhPub = EcProvider.encodeUncompressedPublicKey(adminEcdhKp.public)
        val adminFp = EcProvider.computeSenderFp(adminSignPub)

        val targetSignKp = SoftwareEcProvider.generateKeyPair()
        val targetEcdhKp = SoftwareEcProvider.generateKeyPair()
        val targetSignPub = EcProvider.encodeUncompressedPublicKey(targetSignKp.public)
        val targetEcdhPub = EcProvider.encodeUncompressedPublicKey(targetEcdhKp.public)
        val targetFp = EcProvider.computeSenderFp(targetSignPub)

        val targetIdentity = LocalIdentity(
            senderFp = targetFp,
            alias = "TargetUser",
            signingPub = targetSignPub,
            wrappedSigningPriv = keyVault.wrap(targetSignKp.private.encoded),
            ecdhPub = targetEcdhPub,
            wrappedEcdhPriv = keyVault.wrap(targetEcdhKp.private.encoded)
        )

        val groupDao = MockGroupDao()
        val rosterMemberDao = MockRosterMemberDao()
        val messageDao = MockMessageDao()
        val localIdentityDao = MockLocalIdentityDao(targetIdentity)
        val peerDao = MockPeerDao()

        // Record admin in peerDao
        peerDao.insertRaw(
            Peer(
                senderFp = adminFp,
                signingPub = adminSignPub,
                ecdhPub = adminEcdhPub,
                alias = "Admin",
                verified = true,
                firstSeenMs = 1000L
            )
        )

        val groupId = 9999L
        // Insert group on target device
        groupDao.insertGroup(
            GroupEntity(
                groupId = groupId,
                name = "Tactical Squad",
                myRole = "MEMBER",
                currentEpoch = 1,
                wrappedCurrentKey = ByteArray(32),
                rosterVersion = 1
            )
        )
        rosterMemberDao.insertOrUpdate(
            RosterMember(
                groupId = groupId,
                senderFp = adminFp,
                signingPub = adminSignPub,
                ecdhPub = adminEcdhPub,
                alias = "Admin",
                revoked = false
            )
        )
        rosterMemberDao.insertOrUpdate(
            RosterMember(
                groupId = groupId,
                senderFp = targetFp,
                signingPub = targetSignPub,
                ecdhPub = targetEcdhPub,
                alias = "TargetUser",
                revoked = false
            )
        )
        messageDao.insertMessage(
            MessageEntity(
                msgId = 1L,
                groupId = groupId,
                senderFp = adminFp,
                keyEpoch = 1,
                sentAtMs = 1000L,
                receivedAtMs = 1001L,
                body = com.p2chat.data.message.EncryptedBody.of("Hello team"),
                sigOk = true,
                tier = 3,
                score = 0.1f,
                revealed = true
            )
        )

        assertEquals(1, groupDao.count())
        assertEquals(2, rosterMemberDao.getMembers(groupId).size)
        assertEquals(1, messageDao.countForGroup(groupId))

        var groupRemovedFired = false
        var removedGid: Long? = null

        val identityManager = IdentityManager(localIdentityDao, keyVault)
        val controlPacketHandler = ControlPacketHandler(
            groupDao = groupDao,
            rosterMemberDao = rosterMemberDao,
            identityManager = identityManager,
            peerDao = peerDao,
            messageDao = messageDao,
            onGroupRemoved = { gid ->
                groupRemovedFired = true
                removedGid = gid
            }
        )

        // Build admin removal packet
        val adminToTargetChannel = PairwiseChannel.derive(adminEcdhKp.private, targetEcdhPub)
        val frameBytes = RotationService.buildGroupRemoveMemberPacket(
            adminFp = adminFp,
            adminSigningPrivateKey = adminSignKp.private,
            removedMemberPairwiseChannel = adminToTargetChannel,
            payload = GroupRemoveMemberPayload(groupId = groupId, removedSenderFp = targetFp),
            sentAtMs = 2000L
        )

        val wireFrame = WireCodec.decode(frameBytes).getOrThrow()
        val acceptVerdict = com.p2chat.crypto.envelope.PacketVerdict.Accept(wireFrame.header, 2000L, "")

        // Target receives and handles control packet
        controlPacketHandler.handleControlPacket(acceptVerdict, frameBytes)

        // Verify group, roster, and messages are cleaned up and event fired
        assertTrue("onGroupRemoved callback must fire", groupRemovedFired)
        assertEquals(groupId, removedGid)
        assertNull("Group must be deleted from GroupDao", groupDao.getGroup(groupId))
        assertEquals(0, rosterMemberDao.getMembers(groupId).size)
        assertEquals(0, messageDao.countForGroup(groupId))
    }

    // ── Issue 3: Ghost Node Deduplication ────────────────────────────────────

    @Test
    fun testIssue3_DefaultMeshScanner_inPlaceRssiAndTimestampUpdate_preventsDuplicates() {
        val scanner = DefaultMeshScanner()
        val persistentFp = 0x1122334455667788L

        // Advertisement 1 from MAC A
        val ad1 = DiscoveredPeer(
            address = "AA:BB:CC:00:11:22",
            alias = "Node Alpha",
            senderFp = persistentFp,
            rssi = -75,
            lastSeenMs = 1000L
        )
        scanner.updatePeer(ad1)
        assertEquals(1, scanner.discoveredPeers.value.size)
        assertEquals(-75, scanner.discoveredPeers.value[0].rssi)
        assertEquals(1000L, scanner.discoveredPeers.value[0].lastSeenMs)

        // Advertisement 2: same MAC, updated RSSI and timestamp
        val ad2 = DiscoveredPeer(
            address = "AA:BB:CC:00:11:22",
            alias = "Node Alpha",
            senderFp = persistentFp,
            rssi = -55,
            lastSeenMs = 2000L
        )
        scanner.updatePeer(ad2)
        assertEquals(1, scanner.discoveredPeers.value.size)
        assertEquals(-55, scanner.discoveredPeers.value[0].rssi)
        assertEquals(2000L, scanner.discoveredPeers.value[0].lastSeenMs)

        // Advertisement 3: RPA rotates MAC to MAC B, but persistent senderFp remains the same
        val ad3 = DiscoveredPeer(
            address = "AA:BB:CC:99:88:77",
            alias = "Node Alpha",
            senderFp = persistentFp,
            rssi = -50,
            lastSeenMs = 3000L
        )
        scanner.updatePeer(ad3)
        assertEquals("RPA rotation must NOT spawn duplicate ghost nodes", 1, scanner.discoveredPeers.value.size)
        assertEquals("AA:BB:CC:99:88:77", scanner.discoveredPeers.value[0].address)
        assertEquals(-50, scanner.discoveredPeers.value[0].rssi)
        assertEquals(3000L, scanner.discoveredPeers.value[0].lastSeenMs)
    }

    @Test
    fun testIssue3_RadarViewModel_deduplicatesGhostNodesInMapAndList() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        val scanner = DefaultMeshScanner()
        val pool = DefaultConnectionPool()
        val controller = DefaultMeshController()
        val bufferDao = MockBufferedPacketDao()
        val peerDao = MockPeerDao()
        val clock = FakeClock(10000L)

        val viewModel = RadarViewModel(
            meshScanner = scanner,
            connectionPool = pool,
            meshController = controller,
            bufferedPacketDao = bufferDao,
            peerDao = peerDao,
            clock = clock,
            processId = 1234
        )

        advanceUntilIdle()

        val peer1 = DiscoveredPeer(
            address = "11:22:33:44:55:01",
            alias = "Drone-1",
            senderFp = 0x1111L,
            rssi = -70,
            lastSeenMs = 5000L
        )
        val peer2 = DiscoveredPeer(
            address = "11:22:33:44:55:02",
            alias = "Drone-2",
            senderFp = 0x2222L,
            rssi = -60,
            lastSeenMs = 6000L
        )

        scanner.updatePeer(peer1)
        scanner.updatePeer(peer2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.peersMap.size)
        assertEquals(2, state.peers.size)
        assertTrue(state.peersMap.containsKey("4369")) // 0x1111 in decimal string
        assertTrue(state.peersMap.containsKey("8738")) // 0x2222 in decimal string

        // Peer 1 rotates MAC: senderFp remains 0x1111L
        val peer1Rotated = DiscoveredPeer(
            address = "11:22:33:44:55:99",
            alias = "Drone-1",
            senderFp = 0x1111L,
            rssi = -52,
            lastSeenMs = 8000L
        )
        scanner.updatePeer(peer1Rotated)
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertEquals("Ghost node must not be added to peersMap", 2, updatedState.peersMap.size)
        assertEquals("Ghost node must not be added to peers list", 2, updatedState.peers.size)
        assertEquals("11:22:33:44:55:99", updatedState.peersMap["4369"]?.address)
        assertEquals(-52, updatedState.peersMap["4369"]?.rssi)

        Dispatchers.resetMain()
    }

    // ── Issue 5: TFLite Preprocessing & Toxic Mapping ─────────────────────────

    @Test
    fun testIssue5_TextPreprocessing_lowercasesAndStripsNonAlphanumeric() {
        val rawInput = "Watch OUT!! Threat at North Gate #1 (100% danger$$)!!"
        val expected = "watch out threat at north gate 1 100 danger"

        val preprocessedFromNormalizer = TextNormalizer.preprocess(rawInput)
        val preprocessedFromTokenizer = WordPieceTokenizer.preprocess(rawInput)

        assertEquals(expected, preprocessedFromNormalizer)
        assertEquals(expected, preprocessedFromTokenizer)
    }

    @Test
    fun testIssue5_FilterVerdict_isToxicMapping() {
        val dummyCategory = listOf(CategoryScore("threat", 0.9f))
        val dummySource = NearestSource.CENTROID

        // Tier 1 Quarantine is toxic
        val tier1 = FilterVerdict.Tier1Quarantine(score = 0.85f, nearestSource = dummySource, topCategories = dummyCategory)
        assertTrue(tier1.isToxic)

        // Tier 2 Warning is toxic
        val tier2 = FilterVerdict.Tier2Warning(score = 0.70f, nearestSource = dummySource, topCategories = dummyCategory)
        assertTrue(tier2.isToxic)

        // Tier 3 Clean below 0.65 is NOT toxic
        val tier3Clean = FilterVerdict.Tier3Clean(score = 0.25f)
        assertFalse(tier3Clean.isToxic)

        // Any score > 0.65f maps isToxic to true
        val tier3High = FilterVerdict.Tier3Clean(score = 0.68f)
        assertTrue(tier3High.isToxic)

        // NotScanned is not toxic by default
        val notScanned = FilterVerdict.NotScanned("Unavailable")
        assertFalse(notScanned.isToxic)
    }
}
