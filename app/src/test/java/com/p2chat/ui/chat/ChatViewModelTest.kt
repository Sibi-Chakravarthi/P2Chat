package com.p2chat.ui.chat

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.KeyVault
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
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.pipeline.SendPipeline
import com.p2chat.util.FakeClock
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
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeGroupDao : GroupDao {
        val groups = mutableMapOf<Long, GroupEntity>()
        override suspend fun insertGroup(group: GroupEntity): Long {
            groups[group.groupId] = group
            return group.groupId
        }
        override suspend fun getGroup(groupId: Long): GroupEntity? = groups[groupId]
        override fun observeGroup(groupId: Long): Flow<GroupEntity?> = flowOf(groups[groupId])
        override fun observeAllGroups(): Flow<List<GroupEntity>> = flowOf(groups.values.toList())
        override suspend fun getAllGroups(): List<GroupEntity> = groups.values.toList()
        override suspend fun updateGroup(group: GroupEntity): Int {
            groups[group.groupId] = group
            return 1
        }
        override suspend fun deleteGroup(groupId: Long): Int = if (groups.remove(groupId) != null) 1 else 0
        override suspend fun count(): Int = groups.size
    }

    private class FakeRosterMemberDao : RosterMemberDao {
        val members = mutableMapOf<Pair<Long, Long>, RosterMember>()
        override suspend fun insertOrUpdate(member: RosterMember): Long {
            members[Pair(member.groupId, member.senderFp)] = member
            return member.senderFp
        }
        override suspend fun insertAll(memberList: List<RosterMember>) {
            memberList.forEach { insertOrUpdate(it) }
        }
        override suspend fun getMembers(groupId: Long): List<RosterMember> =
            members.values.filter { it.groupId == groupId }
        override suspend fun getActiveMembers(groupId: Long): List<RosterMember> =
            members.values.filter { it.groupId == groupId && !it.revoked }
        override fun observeMembers(groupId: Long): Flow<List<RosterMember>> =
            flowOf(members.values.filter { it.groupId == groupId })
        override suspend fun getMember(groupId: Long, senderFp: Long): RosterMember? =
            members[Pair(groupId, senderFp)]
        override suspend fun revokeMember(groupId: Long, senderFp: Long): Int = 0
        override suspend fun deleteMember(groupId: Long, senderFp: Long): Int = 0
        override suspend fun deleteGroupRoster(groupId: Long): Int = 0
        override suspend fun countActiveMembers(groupId: Long): Int = 0
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
        override suspend fun deletePeer(senderFp: Long): Int = if (peers.remove(senderFp) != null) 1 else 0
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
        override suspend fun updateAlias(senderFp: Long, newAlias: String): Int {
            identity = identity?.copy(alias = newAlias)
            return 1
        }
        override suspend fun hasIdentity(): Boolean = identity != null
        override suspend fun clearIdentity(): Int = 0
    }

    private class FakeMessageDao : MessageDao {
        val messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

        override suspend fun insertMessage(message: MessageEntity): Long {
            val list = messagesFlow.value.toMutableList()
            list.removeAll { it.msgId == message.msgId }
            list.add(message)
            messagesFlow.value = list
            return message.msgId
        }

        override suspend fun getMessage(msgId: Long): MessageEntity? =
            messagesFlow.value.firstOrNull { it.msgId == msgId }

        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> =
            messagesFlow.asStateFlow()

        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> =
            messagesFlow.value.filter { it.groupId == groupId }

        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int {
            val list = messagesFlow.value.toMutableList()
            val idx = list.indexOfFirst { it.msgId == msgId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(revealed = revealed)
                messagesFlow.value = list
                return 1
            }
            return 0
        }

        override suspend fun deleteMessage(msgId: Long): Int = 0
        override suspend fun deleteGroupMessages(groupId: Long): Int = 0
        override suspend fun count(): Int = messagesFlow.value.size
        override suspend fun countForGroup(groupId: Long): Int =
            messagesFlow.value.count { it.groupId == groupId }
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

    private val groupId = 42L
    private val selfFp = 0x1122334455667788L
    private val peerFp = 0x2233445566778899L

    private lateinit var groupDao: FakeGroupDao
    private lateinit var rosterDao: FakeRosterMemberDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var localIdentityDao: FakeLocalIdentityDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var seenMessageDao: FakeSeenMessageDao
    private lateinit var bufferedPacketDao: FakeBufferedPacketDao
    private lateinit var keyVault: KeyVault
    private lateinit var sendPipeline: SendPipeline
    private lateinit var viewModel: ChatViewModel

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

        val selfKey = EcProvider.get().generateKeyPair()
        val selfPub = EcProvider.encodeUncompressedPublicKey(selfKey.public)
        val wrappedPriv = keyVault.wrapPrivateKey(selfKey.private)

        localIdentityDao.insertIdentity(
            LocalIdentity(
                senderFp = selfFp,
                alias = "Self_Operator",
                signingPub = selfPub,
                ecdhPub = selfPub,
                wrappedSigningPriv = wrappedPriv,
                wrappedEcdhPriv = wrappedPriv
            )
        )

        groupDao.insertGroup(
            GroupEntity(
                groupId = groupId,
                name = "Tactical Squad",
                myRole = "ADMIN",
                currentEpoch = 1,
                wrappedCurrentKey = keyVault.wrap(ByteArray(32) { 0x42 }),
                rosterVersion = 1
            )
        )

        sendPipeline = SendPipeline(
            groupDao = groupDao,
            localIdentityDao = localIdentityDao,
            messageDao = messageDao,
            seenMessageDao = seenMessageDao,
            bufferedPacketDao = bufferedPacketDao,
            keyVault = keyVault,
            clock = FakeClock(10_000_000L)
        )

        viewModel = ChatViewModel(
            groupId = groupId,
            groupDao = groupDao,
            rosterMemberDao = rosterDao,
            peerDao = peerDao,
            localIdentityDao = localIdentityDao,
            messageDao = messageDao,
            sendPipeline = sendPipeline,
            blockSimilarService = null
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testMessagesRenderCleanAndBlurredTiersCorrectly() = runTest(testDispatcher) {
        advanceUntilIdle()

        // 1. Insert a clean Tier 3 message
        messageDao.insertMessage(
            MessageEntity(
                msgId = 101L,
                groupId = groupId,
                senderFp = peerFp,
                keyEpoch = 1,
                sentAtMs = 1000L,
                receivedAtMs = 1001L,
                body = EncryptedBody.of("Clean telemetry report"),
                sigOk = true,
                tier = 3,
                score = 0.12f,
                revealed = true
            )
        )

        // 2. Insert a warned Tier 2 message (blurred, unrevealed)
        messageDao.insertMessage(
            MessageEntity(
                msgId = 102L,
                groupId = groupId,
                senderFp = peerFp,
                keyEpoch = 1,
                sentAtMs = 2000L,
                receivedAtMs = 2001L,
                body = EncryptedBody.of("Questionable message content"),
                sigOk = true,
                tier = 2,
                score = 0.75f,
                revealed = false
            )
        )

        advanceUntilIdle()

        val uiMessages = viewModel.messages.value
        assertEquals(2, uiMessages.size)

        val cleanMsg = uiMessages[0]
        assertEquals(101L, cleanMsg.msgId)
        assertEquals(3, cleanMsg.tier)
        assertTrue(cleanMsg.revealed)
        assertFalse(cleanMsg.isSelf)

        val blurredMsg = uiMessages[1]
        assertEquals(102L, blurredMsg.msgId)
        assertEquals(2, blurredMsg.tier)
        assertFalse("Tier 2 unrevealed must have revealed = false", blurredMsg.revealed)
        assertEquals(0.75f, blurredMsg.score, 0.001f)
    }

    @Test
    fun testRevealMessageFlipsRevealedDirectlyInRoomWithoutCallingFilterService() = runTest(testDispatcher) {
        advanceUntilIdle()

        messageDao.insertMessage(
            MessageEntity(
                msgId = 200L,
                groupId = groupId,
                senderFp = peerFp,
                keyEpoch = 1,
                sentAtMs = 3000L,
                receivedAtMs = 3001L,
                body = EncryptedBody.of("Blurred payload"),
                sigOk = true,
                tier = 2,
                score = 0.78f,
                revealed = false
            )
        )

        advanceUntilIdle()
        assertFalse(viewModel.messages.value[0].revealed)

        // Unmask / tap to reveal
        viewModel.revealMessage(200L)
        advanceUntilIdle()

        // Message is now revealed
        val updated = viewModel.messages.value[0]
        assertTrue("Revealed message must now be unmasked", updated.revealed)
        assertEquals("Blurred payload", updated.text)
    }

    @Test
    fun testSendMessage_WithinLimitSucceeds_AndOverLimitIgnored() = runTest(testDispatcher) {
        advanceUntilIdle()

        // 1. Valid 20-byte message
        val job = viewModel.sendMessage("Hello Mesh Network!")
        job?.join()
        advanceUntilIdle()

        assertEquals(1, messageDao.count())
        val sent = viewModel.messages.value[0]
        assertTrue(sent.isSelf)
        assertEquals("Hello Mesh Network!", sent.text)
        assertEquals(3, sent.tier)
        assertTrue(sent.revealed)

        // 2. Over limit message (> 350 bytes)
        val overLimit = "X".repeat(351)
        val overLimitJob = viewModel.sendMessage(overLimit)
        overLimitJob?.join()
        advanceUntilIdle()

        // Count remains 1 (rejected by client-side length check)
        assertEquals(1, messageDao.count())
    }
}
