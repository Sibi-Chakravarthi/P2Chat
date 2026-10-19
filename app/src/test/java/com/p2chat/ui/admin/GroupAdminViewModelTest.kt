package com.p2chat.ui.admin

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
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
import com.p2chat.data.mesh.SeenMessage
import com.p2chat.data.mesh.SeenMessageDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.pipeline.SendPipeline
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class GroupAdminViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeGroupDao : GroupDao {
        val groupsFlow = MutableStateFlow<Map<Long, GroupEntity>>(emptyMap())

        override suspend fun insertGroup(group: GroupEntity): Long {
            val map = groupsFlow.value.toMutableMap()
            map[group.groupId] = group
            groupsFlow.value = map
            return group.groupId
        }

        override suspend fun getGroup(groupId: Long): GroupEntity? = groupsFlow.value[groupId]

        override fun observeGroup(groupId: Long): Flow<GroupEntity?> =
            flowOf(groupsFlow.value[groupId])

        override fun observeAllGroups(): Flow<List<GroupEntity>> =
            flowOf(groupsFlow.value.values.toList())

        override suspend fun getAllGroups(): List<GroupEntity> = groupsFlow.value.values.toList()

        override suspend fun updateGroup(group: GroupEntity): Int {
            val map = groupsFlow.value.toMutableMap()
            map[group.groupId] = group
            groupsFlow.value = map
            return 1
        }

        override suspend fun deleteGroup(groupId: Long): Int = 0
        override suspend fun count(): Int = groupsFlow.value.size
    }

    private class FakeRosterMemberDao : RosterMemberDao {
        val membersFlow = MutableStateFlow<Map<Pair<Long, Long>, RosterMember>>(emptyMap())

        override suspend fun insertOrUpdate(member: RosterMember): Long {
            val map = membersFlow.value.toMutableMap()
            map[Pair(member.groupId, member.senderFp)] = member
            membersFlow.value = map
            return member.senderFp
        }

        override suspend fun insertAll(memberList: List<RosterMember>) {
            val map = membersFlow.value.toMutableMap()
            memberList.forEach { map[Pair(it.groupId, it.senderFp)] = it }
            membersFlow.value = map
        }

        override suspend fun getMembers(groupId: Long): List<RosterMember> =
            membersFlow.value.values.filter { it.groupId == groupId }

        override suspend fun getActiveMembers(groupId: Long): List<RosterMember> =
            membersFlow.value.values.filter { it.groupId == groupId && !it.revoked }

        override fun observeMembers(groupId: Long): Flow<List<RosterMember>> =
            flowOf(membersFlow.value.values.filter { it.groupId == groupId })

        override suspend fun getMember(groupId: Long, senderFp: Long): RosterMember? =
            membersFlow.value[Pair(groupId, senderFp)]

        override suspend fun revokeMember(groupId: Long, senderFp: Long): Int {
            val map = membersFlow.value.toMutableMap()
            val m = map[Pair(groupId, senderFp)] ?: return 0
            map[Pair(groupId, senderFp)] = m.copy(revoked = true)
            membersFlow.value = map
            return 1
        }

        override suspend fun deleteMember(groupId: Long, senderFp: Long): Int = 0
        override suspend fun deleteGroupRoster(groupId: Long): Int = 0
        override suspend fun countActiveMembers(groupId: Long): Int =
            membersFlow.value.values.count { it.groupId == groupId && !it.revoked }
    }

    private class FakePeerDao : PeerDao {
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
        override suspend fun deletePeer(senderFp: Long): Int = 0
        override suspend fun count(): Int = peers.size
    }

    private class FakeLocalIdentityDao : LocalIdentityDao {
        var identity: LocalIdentity? = null
        override suspend fun insertIdentity(identity: LocalIdentity): Long {
            this.identity = identity
            return identity.senderFp
        }
        override suspend fun getIdentity(): LocalIdentity? = identity
        override fun observeIdentity(): Flow<LocalIdentity?> = flowOf(identity)
        override suspend fun updateAlias(senderFp: Long, newAlias: String): Int = 1
        override suspend fun hasIdentity(): Boolean = identity != null
        override suspend fun clearIdentity(): Int = 0
    }

    private class FakeMessageDao : MessageDao {
        override suspend fun insertMessage(message: MessageEntity): Long = message.msgId
        override suspend fun getMessage(msgId: Long): MessageEntity? = null
        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> = emptyList()
        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int = 1
        override suspend fun deleteMessage(msgId: Long): Int = 0
        override suspend fun deleteGroupMessages(groupId: Long): Int = 0
        override suspend fun count(): Int = 0
        override suspend fun countForGroup(groupId: Long): Int = 0
    }

    private class FakeSeenMessageDao : SeenMessageDao {
        val seen = mutableSetOf<Long>()
        override suspend fun insertIgnore(seenMessage: SeenMessage): Long =
            if (seen.add(seenMessage.msgId)) seenMessage.msgId else -1L
        override suspend fun delete(msgId: Long): Int = 0
        override suspend fun purgeExpired(cutoffMs: Long): Int = 0
        override suspend fun allMsgIds(): List<Long> = seen.toList()
        override suspend fun count(): Int = seen.size
        override suspend fun exists(msgId: Long): Boolean = seen.contains(msgId)
    }

    private class FakeBufferedPacketDao : BufferedPacketDao {
        val buffer = mutableMapOf<Long, BufferedPacket>()
        override suspend fun insertIgnore(packet: BufferedPacket): Long {
            buffer[packet.msgId] = packet
            return packet.msgId
        }
        override suspend fun delete(msgId: Long): Int = 0
        override suspend fun purgeExpired(nowMs: Long): Int = 0
        override suspend fun countBuffer(): Int = buffer.size
        override fun observeBufferCount(): Flow<Int> = flowOf(buffer.size)
        override suspend fun allMsgIds(): List<Long> = buffer.keys.toList()
        override suspend fun getPacket(msgId: Long): BufferedPacket? = buffer[msgId]
        override suspend fun getPacketsToForward(limit: Int): List<BufferedPacket> = emptyList()
        override suspend fun clearAll(): Int = 0
    }

    private val groupId = 999L
    private val adminFp = 0x1122334455667788L
    private val member1Fp = 0x2233445566778899L
    private val member2Fp = 0x33445566778899AAL

    private lateinit var groupDao: FakeGroupDao
    private lateinit var rosterDao: FakeRosterMemberDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var localIdentityDao: FakeLocalIdentityDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var seenMessageDao: FakeSeenMessageDao
    private lateinit var bufferedPacketDao: FakeBufferedPacketDao
    private lateinit var keyVault: KeyVault
    private lateinit var identityManager: IdentityManager
    private lateinit var rotationService: RotationService
    private lateinit var sendPipeline: SendPipeline

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        keyVault = KeyVault()

        groupDao = FakeGroupDao()
        rosterDao = FakeRosterMemberDao()
        peerDao = FakePeerDao()
        localIdentityDao = FakeLocalIdentityDao()
        messageDao = FakeMessageDao()
        seenMessageDao = FakeSeenMessageDao()
        bufferedPacketDao = FakeBufferedPacketDao()

        identityManager = IdentityManager(localIdentityDao, keyVault)
        rotationService = RotationService(groupDao, rosterDao, keyVault)

        sendPipeline = SendPipeline(
            groupDao = groupDao,
            localIdentityDao = localIdentityDao,
            messageDao = messageDao,
            seenMessageDao = seenMessageDao,
            bufferedPacketDao = bufferedPacketDao,
            keyVault = keyVault,
            clock = FakeClock(10_000_000L)
        )

        val adminKey = EcProvider.get().generateKeyPair()
        val adminPub = EcProvider.encodeUncompressedPublicKey(adminKey.public)
        val wrappedPriv = keyVault.wrapPrivateKey(adminKey.private)

        localIdentityDao.insertIdentity(
            LocalIdentity(
                senderFp = adminFp,
                alias = "Ghost_Admin",
                signingPub = adminPub,
                ecdhPub = adminPub,
                wrappedSigningPriv = wrappedPriv,
                wrappedEcdhPriv = wrappedPriv
            )
        )

        groupDao.insertGroup(
            GroupEntity(
                groupId = groupId,
                name = "Alpha Force",
                myRole = "ADMIN",
                currentEpoch = 1,
                wrappedCurrentKey = keyVault.wrap(ByteArray(32) { 0x11 }),
                rosterVersion = 1
            )
        )

        // Admin member
        rosterDao.insertOrUpdate(
            RosterMember(
                groupId = groupId,
                senderFp = adminFp,
                signingPub = adminPub,
                ecdhPub = adminPub,
                alias = "Ghost_Admin",
                revoked = false
            )
        )

        // Member 1
        val m1Key = EcProvider.get().generateKeyPair()
        val m1Pub = EcProvider.encodeUncompressedPublicKey(m1Key.public)
        rosterDao.insertOrUpdate(
            RosterMember(
                groupId = groupId,
                senderFp = member1Fp,
                signingPub = m1Pub,
                ecdhPub = m1Pub,
                alias = "Valkyrie",
                revoked = false
            )
        )

        // Member 2
        val m2Key = EcProvider.get().generateKeyPair()
        val m2Pub = EcProvider.encodeUncompressedPublicKey(m2Key.public)
        rosterDao.insertOrUpdate(
            RosterMember(
                groupId = groupId,
                senderFp = member2Fp,
                signingPub = m2Pub,
                ecdhPub = m2Pub,
                alias = "Kestrel",
                revoked = false
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testNonAdminCannotAccessScreen_ReturnsAccessDenied() = runTest(testDispatcher) {
        // Change role to MEMBER
        val current = groupDao.getGroup(groupId)!!
        groupDao.updateGroup(current.copy(myRole = "MEMBER"))

        val viewModel = GroupAdminViewModel(
            groupId = groupId,
            groupDao = groupDao,
            rosterMemberDao = rosterDao,
            localIdentityDao = localIdentityDao,
            peerDao = peerDao,
            identityManager = identityManager,
            rotationService = rotationService,
            sendPipeline = sendPipeline,
            keyVault = keyVault
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Non-admin role must result in AccessDenied", state is GroupAdminUiState.AccessDenied)
    }

    @Test
    fun testAdminRemovesMember_IncrementsEpoch_AndQueuesNMinusOneKeyRotateFrames() = runTest(testDispatcher) {
        val viewModel = GroupAdminViewModel(
            groupId = groupId,
            groupDao = groupDao,
            rosterMemberDao = rosterDao,
            localIdentityDao = localIdentityDao,
            peerDao = peerDao,
            identityManager = identityManager,
            rotationService = rotationService,
            sendPipeline = sendPipeline,
            keyVault = keyVault
        )

        advanceUntilIdle()

        val initial = viewModel.uiState.value
        assertTrue(initial is GroupAdminUiState.Success)
        assertEquals(1, (initial as GroupAdminUiState.Success).currentEpoch)

        // Remove Member 2 (Kestrel)
        // Group has 3 members (Admin + Valkyrie + Kestrel).
        // Removing Kestrel leaves 1 other active member (Valkyrie).
        // So N - 1 = 1 KEY_ROTATE frame + 1 ROSTER_SYNC frame = 2 control frames.
        val job = viewModel.removeMember(member2Fp)
        job.join()
        advanceUntilIdle()

        // 1. Verify group epoch incremented to 2
        val updatedGroup = groupDao.getGroup(groupId)!!
        assertEquals(2, updatedGroup.currentEpoch)

        // 2. Member 2 is revoked
        val m2 = rosterDao.getMember(groupId, member2Fp)
        assertNotNull(m2)
        assertTrue("Removed member must be revoked", m2!!.revoked)

        // 3. Control packets queued in packet_buffer (1 KEY_ROTATE + 1 ROSTER_SYNC)
        assertEquals(2, bufferedPacketDao.countBuffer())
    }

    @Test
    fun testManualRotateKeyNow_IncrementsEpoch_AndQueuesKeyRotateFrames() = runTest(testDispatcher) {
        val viewModel = GroupAdminViewModel(
            groupId = groupId,
            groupDao = groupDao,
            rosterMemberDao = rosterDao,
            localIdentityDao = localIdentityDao,
            peerDao = peerDao,
            identityManager = identityManager,
            rotationService = rotationService,
            sendPipeline = sendPipeline,
            keyVault = keyVault
        )

        advanceUntilIdle()

        // Trigger manual key rotation
        val job = viewModel.rotateKeyNow()
        job.join()
        advanceUntilIdle()

        // Epoch incremented to 2
        val updatedGroup = groupDao.getGroup(groupId)!!
        assertEquals(2, updatedGroup.currentEpoch)

        // 2 active non-admin members -> 2 KEY_ROTATE packets + 1 ROSTER_SYNC = 3 frames
        assertEquals(3, bufferedPacketDao.countBuffer())
    }

    @Test
    fun testRosterFull16Members_DisablesCanAddMember() = runTest(testDispatcher) {
        // Add 13 more members to reach exactly 16 (3 already present)
        for (i in 4..16) {
            val key = EcProvider.get().generateKeyPair()
            val pub = EcProvider.encodeUncompressedPublicKey(key.public)
            rosterDao.insertOrUpdate(
                RosterMember(
                    groupId = groupId,
                    senderFp = 0x1000L + i,
                    signingPub = pub,
                    ecdhPub = pub,
                    alias = "Member_$i",
                    revoked = false
                )
            )
        }

        assertEquals(16, rosterDao.getActiveMembers(groupId).size)

        val viewModel = GroupAdminViewModel(
            groupId = groupId,
            groupDao = groupDao,
            rosterMemberDao = rosterDao,
            localIdentityDao = localIdentityDao,
            peerDao = peerDao,
            identityManager = identityManager,
            rotationService = rotationService,
            sendPipeline = sendPipeline,
            keyVault = keyVault
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is GroupAdminUiState.Success)
        val success = state as GroupAdminUiState.Success
        assertFalse("At 16 members, canAddMember must be false", success.canAddMember)
    }
}
