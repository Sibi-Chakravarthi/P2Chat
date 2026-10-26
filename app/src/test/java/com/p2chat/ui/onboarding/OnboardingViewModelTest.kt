package com.p2chat.ui.onboarding

import com.p2chat.crypto.invite.QrPayload
import com.p2chat.crypto.invite.QrPayloadCodec
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.identity.PeerPinningResult
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocalIdentityDao : LocalIdentityDao {
        var identity: LocalIdentity? = null

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
            identity = null
            return 1
        }
    }

    private class FakePeerDao : PeerDao {
        val peers = mutableMapOf<Long, Peer>()

        override suspend fun insertRaw(peer: Peer): Long {
            peers[peer.senderFp] = peer
            return 1L
        }

        override suspend fun getPeer(senderFp: Long): Peer? = peers[senderFp]

        override fun observeAllPeers(): Flow<List<Peer>> = flowOf(peers.values.toList())
        override suspend fun getAllPeers(): List<Peer> = peers.values.toList()

        override suspend fun updateVerification(senderFp: Long, verified: Boolean): Int {
            val p = peers[senderFp] ?: return 0
            peers[senderFp] = p.copy(verified = verified)
            return 1
        }

        override suspend fun updateAlias(senderFp: Long, alias: String): Int {
            val p = peers[senderFp] ?: return 0
            peers[senderFp] = p.copy(alias = alias)
            return 1
        }

        override suspend fun deletePeer(senderFp: Long): Int {
            return if (peers.remove(senderFp) != null) 1 else 0
        }

        override suspend fun count(): Int = peers.size
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAliasValidation_1to32Chars_andRejectionOf33Chars() {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        // 1. Empty alias -> invalid
        assertFalse("Empty alias must be invalid", viewModel.validateAlias(""))
        assertNotNull(viewModel.aliasError.value)

        // 2. Exactly 1 char -> valid
        assertTrue("1 character alias must be valid", viewModel.validateAlias("A"))
        assertNull(viewModel.aliasError.value)

        // 3. Exactly 32 chars -> valid
        val valid32 = "A".repeat(32)
        assertTrue("32 character alias must be valid", viewModel.validateAlias(valid32))
        assertNull(viewModel.aliasError.value)

        // 4. 33 chars -> rejected (PRD ID-3, T1.14 bar)
        val invalid33 = "A".repeat(33)
        assertFalse("33 character alias must be rejected", viewModel.validateAlias(invalid33))
        assertEquals("Alias cannot exceed 32 characters", viewModel.aliasError.value)
    }

    @Test
    fun testIdentityGeneration_success_createsFingerprintAndQr() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        viewModel.setAlias("TestNode")
        viewModel.generateIdentity()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("UI state must be Created after generation", state is OnboardingUiState.Created)
        val created = state as OnboardingUiState.Created

        assertEquals("TestNode", created.identity.alias)
        assertNotNull(created.identity.signingPub)
        assertNotNull(created.identity.ecdhPub)

        // Fingerprint format check (e.g. A4F9:82C1:09E3:77B2)
        val expectedHex = EcProvider.senderFpHex(created.identity.senderFp).uppercase().chunked(4).joinToString(":")
        assertEquals("Formatted fingerprint must match expected hex", expectedHex, created.formattedFingerprint)

        // QR code payload round-trip check
        val decodedQrResult = QrPayloadCodec.decodeFromBase64(created.qrBase64)
        assertTrue("QR payload must decode cleanly", decodedQrResult.isSuccess)
        val qrPayload = decodedQrResult.getOrThrow()
        assertEquals("TestNode", qrPayload.alias)
        assertTrue(created.identity.signingPub.contentEquals(qrPayload.signingPub))
        assertTrue(created.identity.ecdhPub.contentEquals(qrPayload.ecdhPub))
    }

    @Test
    fun testRelaunch_withExistingIdentity_restoresCreatedStateImmediately() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)

        // Pre-create identity
        identityManager.getOrCreateIdentity("PersistentUser")

        // Instantiate new ViewModel as if app relaunched
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Relaunch must skip Idle and immediately restore Created state", state is OnboardingUiState.Created)
        assertEquals("PersistentUser", (state as OnboardingUiState.Created).identity.alias)
    }

    @Test
    fun testQrScanning_pinsPeerAndDetectsKeyConflict() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        viewModel.setAlias("LocalUser")
        viewModel.generateIdentity()
        advanceUntilIdle()

        // Generate peer's identity and QR payload
        val peerSigningKp = SoftwareEcProvider.generateKeyPair()
        val peerEcdhKp = SoftwareEcProvider.generateKeyPair()
        val peerSigningPub = EcProvider.encodeUncompressedPublicKey(peerSigningKp.public)
        val peerEcdhPub = EcProvider.encodeUncompressedPublicKey(peerEcdhKp.public)

        val peerQrPayload = QrPayload(
            signingPub = peerSigningPub,
            ecdhPub = peerEcdhPub,
            alias = "Alice"
        )
        val peerQrBase64 = QrPayloadCodec.encodeToBase64(peerQrPayload)

        // 1. Scan peer QR for first time (TOFU)
        viewModel.processScannedQr(peerQrBase64)
        advanceUntilIdle()

        var state = viewModel.uiState.value as OnboardingUiState.Created
        assertNotNull("Scanned peer must be attached to state", state.scannedPeer)
        assertEquals("Alice", state.scannedPeer!!.alias)
        assertTrue("Peer must be marked verified after QR scan", state.scannedPeer!!.verified)
        assertNull("No conflict warning on first pin", state.keyConflictWarning)

        // 2. Scan same peer with CHANGED keys (simulating impersonation / MITM)
        val forgedSigningKp = SoftwareEcProvider.generateKeyPair()
        val forgedSigningPub = EcProvider.encodeUncompressedPublicKey(forgedSigningKp.public)
        val changedQrPayload = QrPayload(
            signingPub = peerSigningPub, // Same declared fingerprint...
            ecdhPub = peerSigningPub,    // but modified key bytes
            alias = "Alice_Impostor"
        )
        // Note: in PeerPinning, senderFp is derived from signingPub.
        // If an attacker uses the same senderFp with a different signing key:
        val changedPeer = Peer(
            senderFp = state.scannedPeer!!.senderFp,
            signingPub = forgedSigningPub,
            ecdhPub = peerEcdhPub,
            alias = "Alice",
            verified = true,
            firstSeenMs = System.currentTimeMillis()
        )
        // Trigger key conflict in DAO
        val conflictResult = fakePeerDao.pinOrVerifyPeer(changedPeer)
        assertTrue("Must detect key conflict", conflictResult is PeerPinningResult.KeyConflict)
    }

    @Test
    fun testRandomAlias_generatesValidAliasWithin32Chars() {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        for (i in 1..20) {
            viewModel.generateRandomAlias()
            val alias = viewModel.alias.value
            assertTrue("Random alias must be non-empty", alias.isNotEmpty())
            assertTrue("Random alias must be <= 32 chars", alias.length <= 32)
            assertNull("Random alias must have no error", viewModel.aliasError.value)
        }
    }

    @Test
    fun testScanGroupInvite_joinsGroupAndBroadcastsRosterSync() = runTest(testDispatcher) {
        javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x01 }, "AES").let {
            com.p2chat.crypto.keys.KeyVault.testKeyOverride = it
        }
        val keyVault = com.p2chat.crypto.keys.KeyVault()
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()

        // Create self identity
        val selfKp = SoftwareEcProvider.generateKeyPair()
        val selfPub = EcProvider.encodeUncompressedPublicKey(selfKp.public)
        val selfFp = EcProvider.computeSenderFp(selfPub)
        val selfIdentity = LocalIdentity(
            senderFp = selfFp,
            alias = "SelfUser",
            signingPub = selfPub,
            ecdhPub = selfPub,
            wrappedSigningPriv = keyVault.wrap(selfKp.private.encoded),
            wrappedEcdhPriv = keyVault.wrap(selfKp.private.encoded)
        )
        fakeIdentityDao.insertIdentity(selfIdentity)
        val identityManager = IdentityManager(fakeIdentityDao, keyVault)

        // Mock DAOs for GroupManager & SendPipeline
        val groups = mutableMapOf<Long, com.p2chat.data.group.GroupEntity>()
        val fakeGroupDao = object : com.p2chat.data.group.GroupDao {
            override suspend fun insertGroup(group: com.p2chat.data.group.GroupEntity): Long {
                groups[group.groupId] = group
                return group.groupId
            }
            override suspend fun getGroup(groupId: Long) = groups[groupId]
            override fun observeGroup(groupId: Long) = flowOf(groups[groupId])
            override fun observeAllGroups() = flowOf(groups.values.toList())
            override suspend fun getAllGroups() = groups.values.toList()
            override suspend fun updateGroup(group: com.p2chat.data.group.GroupEntity): Int {
                groups[group.groupId] = group
                return 1
            }
            override suspend fun deleteGroup(groupId: Long) = 0
            override suspend fun count() = groups.size
        }

        val members = mutableMapOf<Pair<Long, Long>, com.p2chat.data.group.RosterMember>()
        val fakeRosterDao = object : com.p2chat.data.group.RosterMemberDao {
            override suspend fun insertOrUpdate(member: com.p2chat.data.group.RosterMember): Long {
                members[Pair(member.groupId, member.senderFp)] = member
                return member.senderFp
            }
            override suspend fun insertAll(memberList: List<com.p2chat.data.group.RosterMember>) {
                memberList.forEach { members[Pair(it.groupId, it.senderFp)] = it }
            }
            override suspend fun getMember(groupId: Long, senderFp: Long) = members[Pair(groupId, senderFp)]
            override fun observeMembers(groupId: Long) = flowOf(members.values.filter { it.groupId == groupId })
            override suspend fun getMembers(groupId: Long) = members.values.filter { it.groupId == groupId }
            override suspend fun getActiveMembers(groupId: Long) = members.values.filter { it.groupId == groupId && !it.revoked }
            override suspend fun revokeMember(groupId: Long, senderFp: Long): Int {
                val m = members[Pair(groupId, senderFp)] ?: return 0
                members[Pair(groupId, senderFp)] = m.copy(revoked = true)
                return 1
            }
            override suspend fun deleteMember(groupId: Long, senderFp: Long) = 0
            override suspend fun deleteGroupRoster(groupId: Long) = 0
            override suspend fun countActiveMembers(groupId: Long) = members.values.count { it.groupId == groupId && !it.revoked }
        }

        val groupManager = com.p2chat.crypto.group.GroupManager(fakeGroupDao, fakeRosterDao, keyVault)

        val bufferedPackets = mutableListOf<com.p2chat.data.mesh.BufferedPacket>()
        val fakeBufferedPacketDao = object : com.p2chat.data.mesh.BufferedPacketDao {
            override suspend fun insertIgnore(packet: com.p2chat.data.mesh.BufferedPacket): Long {
                bufferedPackets.add(packet)
                return packet.msgId
            }
            override suspend fun delete(msgId: Long): Int = 0
            override suspend fun purgeExpired(nowMs: Long): Int = 0
            override suspend fun countBuffer(): Int = bufferedPackets.size
            override fun observeBufferCount(): Flow<Int> = flowOf(bufferedPackets.size)
            override suspend fun allMsgIds(): List<Long> = bufferedPackets.map { it.msgId }
            override suspend fun getPacket(msgId: Long): com.p2chat.data.mesh.BufferedPacket? = bufferedPackets.find { it.msgId == msgId }
            override suspend fun getPacketsToForward(limit: Int): List<com.p2chat.data.mesh.BufferedPacket> = bufferedPackets.take(limit)
            override suspend fun clearAll(): Int = 0
        }

        val fakeSeenMessageDao = object : com.p2chat.data.mesh.SeenMessageDao {
            override suspend fun insertIgnore(seen: com.p2chat.data.mesh.SeenMessage): Long = 1L
            override suspend fun delete(msgId: Long): Int = 0
            override suspend fun purgeExpired(cutoffMs: Long): Int = 0
            override suspend fun allMsgIds(): List<Long> = emptyList()
            override suspend fun count(): Int = 0
            override suspend fun exists(msgId: Long): Boolean = false
        }
        val fakeMessageDao = object : com.p2chat.data.message.MessageDao {
            override suspend fun insertMessage(message: com.p2chat.data.message.MessageEntity): Long = 1L
            override fun observeMessagesForGroup(groupId: Long): Flow<List<com.p2chat.data.message.MessageEntity>> = flowOf(emptyList())
            override suspend fun getMessagesForGroup(groupId: Long): List<com.p2chat.data.message.MessageEntity> = emptyList()
            override suspend fun getMessage(msgId: Long): com.p2chat.data.message.MessageEntity? = null
            override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int = 1
            override suspend fun deleteMessage(msgId: Long): Int = 0
            override suspend fun deleteGroupMessages(groupId: Long): Int = 0
            override suspend fun count(): Int = 0
            override suspend fun countForGroup(groupId: Long): Int = 0
        }

        val sendPipeline = com.p2chat.pipeline.SendPipeline(
            groupDao = fakeGroupDao,
            localIdentityDao = fakeIdentityDao,
            messageDao = fakeMessageDao,
            seenMessageDao = fakeSeenMessageDao,
            bufferedPacketDao = fakeBufferedPacketDao,
            keyVault = keyVault
        )

        val viewModel = OnboardingViewModel(
            identityManager = identityManager,
            peerDao = fakePeerDao,
            groupManager = groupManager,
            sendPipeline = sendPipeline
        )

        // Admin invite QR payload
        val adminKp = SoftwareEcProvider.generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKp.public)
        val adminFp = EcProvider.computeSenderFp(adminPub)
        val inviteData = com.p2chat.crypto.invite.GroupInviteData(
            groupId = 0x9999L,
            groupName = "Alpha Squad",
            groupKey = ByteArray(32) { 0x44 },
            keyEpoch = 1,
            adminFp = adminFp,
            adminSigningPub = adminPub,
            adminEcdhPub = adminPub,
            adminAlias = "AlphaAdmin"
        )
        val inviteQr = com.p2chat.crypto.invite.GroupInviteCodec.encode(inviteData)

        // Process scanned invite QR
        val job = viewModel.processScannedQr(inviteQr)
        job.join()
        advanceUntilIdle()

        // Verify group joined
        assertNotNull(fakeGroupDao.getGroup(0x9999L))
        assertEquals(2, fakeRosterDao.getMembers(0x9999L).size)

        // Verify ROSTER_SYNC broadcasted via sendPipeline into bufferedPacketDao
        assertEquals("ROSTER_SYNC packet must be queued in send buffer", 1, fakeBufferedPacketDao.countBuffer())
        val queuedPacket = bufferedPackets.first()
        val wireFrame = com.p2chat.wire.WireCodec.decode(queuedPacket.frame).getOrThrow()
        assertEquals(com.p2chat.wire.PacketType.ROSTER_SYNC, wireFrame.header.packetType)
        assertEquals(0x9999L, wireFrame.header.groupId)
        assertEquals(selfFp, wireFrame.header.senderFp)
    }
}
