package com.p2chat.ui.groups

import com.p2chat.ble.BleState
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class GroupListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeGroupDao : GroupDao {
        val groupsFlow = MutableStateFlow<List<GroupEntity>>(emptyList())

        override suspend fun insertGroup(group: GroupEntity): Long {
            val current = groupsFlow.value.toMutableList()
            current.removeAll { it.groupId == group.groupId }
            current.add(group)
            groupsFlow.value = current
            return group.groupId
        }

        override suspend fun getGroup(groupId: Long): GroupEntity? =
            groupsFlow.value.firstOrNull { it.groupId == groupId }

        override fun observeGroup(groupId: Long): Flow<GroupEntity?> =
            flowOf(groupsFlow.value.firstOrNull { it.groupId == groupId })

        override fun observeAllGroups(): Flow<List<GroupEntity>> = groupsFlow.asStateFlow()

        override suspend fun getAllGroups(): List<GroupEntity> = groupsFlow.value

        override suspend fun updateGroup(group: GroupEntity): Int {
            insertGroup(group)
            return 1
        }

        override suspend fun deleteGroup(groupId: Long): Int {
            val current = groupsFlow.value.toMutableList()
            val removed = current.removeAll { it.groupId == groupId }
            groupsFlow.value = current
            return if (removed) 1 else 0
        }

        override suspend fun count(): Int = groupsFlow.value.size
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
            members.values.count { it.groupId == groupId && !it.revoked }
    }

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<Long, MessageEntity>()

        override suspend fun insertMessage(message: MessageEntity): Long {
            messages[message.msgId] = message
            return message.msgId
        }

        override suspend fun getMessage(msgId: Long): MessageEntity? = messages[msgId]

        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.groupId == groupId })

        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> =
            messages.values.filter { it.groupId == groupId }

        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int {
            val m = messages[msgId] ?: return 0
            messages[msgId] = m.copy(revealed = revealed)
            return 1
        }

        override suspend fun deleteMessage(msgId: Long): Int = if (messages.remove(msgId) != null) 1 else 0

        override suspend fun deleteGroupMessages(groupId: Long): Int {
            val toRemove = messages.values.filter { it.groupId == groupId }.map { it.msgId }
            toRemove.forEach { messages.remove(it) }
            return toRemove.size
        }

        override suspend fun count(): Int = messages.size

        override suspend fun countForGroup(groupId: Long): Int =
            messages.values.count { it.groupId == groupId }
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
        override suspend fun clearIdentity(): Int {
            val had = identity != null
            identity = null
            return if (had) 1 else 0
        }
    }

    private lateinit var groupDao: FakeGroupDao
    private lateinit var rosterMemberDao: FakeRosterMemberDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var localIdentityDao: FakeLocalIdentityDao
    private lateinit var keyVault: KeyVault
    private lateinit var identityManager: IdentityManager
    private lateinit var groupManager: GroupManager
    private lateinit var viewModel: GroupListViewModel

    @Before
    fun setup() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        keyVault = KeyVault()

        groupDao = FakeGroupDao()
        rosterMemberDao = FakeRosterMemberDao()
        messageDao = FakeMessageDao()
        localIdentityDao = FakeLocalIdentityDao()

        identityManager = IdentityManager(localIdentityDao, keyVault)
        groupManager = GroupManager(groupDao, rosterMemberDao, keyVault)

        // Seed a local identity for group creation
        val keyPair = EcProvider.get().generateKeyPair()
        val pubBytes = EcProvider.encodeUncompressedPublicKey(keyPair.public)
        val wrappedPriv = keyVault.wrapPrivateKey(keyPair.private)
        localIdentityDao.insertIdentity(
            LocalIdentity(
                senderFp = 0x1122334455667788L,
                alias = "Alice_Operator",
                signingPub = pubBytes,
                ecdhPub = pubBytes,
                wrappedSigningPriv = wrappedPriv,
                wrappedEcdhPriv = wrappedPriv
            )
        )

        viewModel = GroupListViewModel(
            groupDao = groupDao,
            rosterMemberDao = rosterMemberDao,
            messageDao = messageDao,
            groupManager = groupManager,
            identityManager = identityManager,
            initialBleState = BleState.READY
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testEmptyStateOnFirstEntry() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue("Initial state with no groups must be Empty", state is GroupListUiState.Empty)
    }

    @Test
    fun testCreateGroupDisplaysEpoch1AndAdminRole() = runTest(testDispatcher) {
        advanceUntilIdle()

        val result = viewModel.createGroup("Ops Squad Alpha")
        assertTrue("Group creation must succeed", result.isSuccess)
        val groupId = result.getOrThrow()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State must become Success after group creation", state is GroupListUiState.Success)
        val success = state as GroupListUiState.Success
        assertEquals(1, success.groups.size)

        val groupItem = success.groups[0]
        assertEquals(groupId, groupItem.groupId)
        assertEquals("Ops Squad Alpha", groupItem.name)
        assertEquals("ADMIN", groupItem.myRole)
        assertEquals("Initial group must display Epoch 1", BleConstants.INITIAL_KEY_EPOCH, groupItem.currentEpoch)
        assertEquals(1, groupItem.currentEpoch)
        assertEquals("Single admin must count 1 member", 1, groupItem.memberCount)
        assertEquals(BleConstants.MAX_ROSTER_MEMBERS, groupItem.maxMembers)
    }

    @Test
    fun testCreateGroupValidation() = runTest(testDispatcher) {
        val emptyResult = viewModel.createGroup("   ")
        assertTrue(emptyResult.isFailure)
        assertTrue(emptyResult.exceptionOrNull() is IllegalArgumentException)

        val longResult = viewModel.createGroup("A".repeat(33))
        assertTrue(longResult.isFailure)
        assertTrue(longResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testBleStateTransitions() = runTest(testDispatcher) {
        assertEquals(BleState.READY, viewModel.bleState.value)

        viewModel.setBleState(BleState.BLUETOOTH_OFF)
        assertEquals(BleState.BLUETOOTH_OFF, viewModel.bleState.value)

        viewModel.setBleState(BleState.PERMISSIONS_DENIED)
        assertEquals(BleState.PERMISSIONS_DENIED, viewModel.bleState.value)

        viewModel.setBleState(BleState.READY)
        assertEquals(BleState.READY, viewModel.bleState.value)
    }
}
