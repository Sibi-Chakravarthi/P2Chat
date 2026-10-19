package com.p2chat.ui.aifilter

import com.p2chat.ai.FilterDiagnosis
import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterSettings
import com.p2chat.ai.FilterSettingsRepository
import com.p2chat.ai.FilterVerdict
import com.p2chat.ai.QuarantineRepository
import com.p2chat.ai.score.CategoryScore
import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.data.settings.Setting
import com.p2chat.data.settings.SettingDao
import com.p2chat.util.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class AiFilterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSettingDao : SettingDao {
        val settings = mutableMapOf<String, String>()

        override suspend fun putSetting(setting: Setting): Long {
            settings[setting.key] = setting.value
            return 1L
        }

        override suspend fun getString(key: String): String? = settings[key]
        override fun observeString(key: String): Flow<String?> = flowOf(settings[key])
        override suspend fun delete(key: String): Int = if (settings.remove(key) != null) 1 else 0
        override suspend fun clearAll(): Int = settings.size.also { settings.clear() }
    }

    private class FakeQuarantineDao : QuarantineDao {
        val flow = MutableStateFlow<Map<Long, QuarantineEntity>>(emptyMap())

        override suspend fun insert(entity: QuarantineEntity): Long {
            val map = flow.value.toMutableMap()
            map[entity.msgId] = entity
            flow.value = map
            return entity.msgId
        }

        override suspend fun getById(msgId: Long): QuarantineEntity? = flow.value[msgId]
        override fun getAllFlow(): Flow<List<QuarantineEntity>> = flowOf(flow.value.values.toList())
        override suspend fun getAll(): List<QuarantineEntity> = flow.value.values.toList()

        override suspend fun deleteById(msgId: Long): Int {
            val map = flow.value.toMutableMap()
            val removed = map.remove(msgId)
            flow.value = map
            return if (removed != null) 1 else 0
        }

        override suspend fun purgeOlderThan(cutoffMs: Long): Int = 0
        override suspend fun count(): Int = flow.value.size
    }

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<Long, MessageEntity>()

        override suspend fun insertMessage(message: MessageEntity): Long {
            messages[message.msgId] = message
            return message.msgId
        }

        override suspend fun getMessage(msgId: Long): MessageEntity? = messages[msgId]
        override fun observeMessagesForGroup(groupId: Long): Flow<List<MessageEntity>> = flowOf(messages.values.toList())
        override suspend fun getMessagesForGroup(groupId: Long): List<MessageEntity> = messages.values.toList()
        override suspend fun setRevealed(msgId: Long, revealed: Boolean): Int {
            val m = messages[msgId] ?: return 0
            messages[msgId] = m.copy(revealed = revealed)
            return 1
        }
        override suspend fun deleteMessage(msgId: Long): Int = if (messages.remove(msgId) != null) 1 else 0
        override suspend fun deleteGroupMessages(groupId: Long): Int = 0
        override suspend fun count(): Int = messages.size
        override suspend fun countForGroup(groupId: Long): Int = messages.values.count { it.groupId == groupId }
    }

    private class FakeBlockedVectorDao : BlockedVectorDao {
        val flow = MutableStateFlow<Map<Long, BlockedVectorEntity>>(emptyMap())

        override suspend fun count(): Int = flow.value.size
        override suspend fun insert(entity: BlockedVectorEntity): Long {
            val map = flow.value.toMutableMap()
            map[entity.id] = entity
            flow.value = map
            return entity.id
        }

        override suspend fun getAll(): List<BlockedVectorEntity> = flow.value.values.toList()
        override fun getAllFlow(): Flow<List<BlockedVectorEntity>> = flowOf(flow.value.values.toList())
        override suspend fun getById(id: Long): BlockedVectorEntity? = flow.value[id]
        override suspend fun getBySourceMsgId(msgId: Long): BlockedVectorEntity? = flow.value.values.firstOrNull { it.sourceMsgId == msgId }

        override suspend fun deleteById(id: Long): Int {
            val map = flow.value.toMutableMap()
            val removed = map.remove(id)
            flow.value = map
            return if (removed != null) 1 else 0
        }

        override suspend fun updateForceQuarantine(id: Long, forceQuarantine: Boolean): Int {
            val existing = flow.value[id] ?: return 0
            val map = flow.value.toMutableMap()
            map[id] = existing.copy(forceQuarantine = forceQuarantine)
            flow.value = map
            return 1
        }

        override suspend fun deleteAll(): Int = 0
    }

    private lateinit var settingDao: FakeSettingDao
    private lateinit var quarantineDao: FakeQuarantineDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var blockedVectorDao: FakeBlockedVectorDao
    private lateinit var settingsRepository: FilterSettingsRepository
    private lateinit var quarantineRepository: QuarantineRepository
    private lateinit var filterService: FilterService
    private lateinit var clock: FakeClock
    private lateinit var viewModel: AiFilterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingDao = FakeSettingDao()
        quarantineDao = FakeQuarantineDao()
        messageDao = FakeMessageDao()
        blockedVectorDao = FakeBlockedVectorDao()
        clock = FakeClock(1700000000000L)

        settingsRepository = FilterSettingsRepository(settingDao)
        quarantineRepository = QuarantineRepository(quarantineDao, messageDao, clock)

        // FilterService with null assets falls back gracefully to NotScanned or diagnostic stub
        filterService = FilterService(
            embedder = null,
            scorer = null,
            tokenizer = null,
            settingDao = settingDao
        )

        viewModel = AiFilterViewModel(
            filterService = filterService,
            settingsRepository = settingsRepository,
            quarantineRepository = quarantineRepository,
            blockedVectorDao = blockedVectorDao,
            clock = clock
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState_defaultsToStandardThresholds() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertEquals(0.68f, state.tauWarn, 0.001f)
        assertEquals(0.82f, state.tauBlock, 0.001f)
        assertEquals(0, state.quarantinedMessages.size)
        assertEquals(0, state.blockedVectors.size)
        assertEquals(AiFilterTab.QUARANTINE, state.selectedTab)
    }

    @Test
    fun testUpdateTauWarn_clampedBelowTauBlock() = runTest(testDispatcher) {
        advanceUntilIdle()
        // Try setting tau_warn = 0.90f when tau_block = 0.82f
        val job = viewModel.updateTauWarn(0.90f)
        job.join()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Must be clamped to tauBlock - 0.01 = 0.81f
        assertTrue(state.tauWarn <= 0.81f)
        assertTrue(state.tauBlock > state.tauWarn)
    }

    @Test
    fun testUpdateTauBlock_clampedAboveTauWarn() = runTest(testDispatcher) {
        advanceUntilIdle()
        // Try setting tau_block = 0.50f when tau_warn = 0.68f
        val job = viewModel.updateTauBlock(0.50f)
        job.join()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Must be clamped to tauWarn + 0.01 = 0.69f
        assertTrue(state.tauBlock >= 0.69f)
        assertTrue(state.tauBlock > state.tauWarn)
    }

    @Test
    fun testResetToDefaults_restoresStandardThresholds() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.updateTauWarn(0.60f).join()
        viewModel.updateTauBlock(0.90f).join()
        advanceUntilIdle()

        assertEquals(0.60f, viewModel.uiState.value.tauWarn, 0.001f)
        assertEquals(0.90f, viewModel.uiState.value.tauBlock, 0.001f)

        viewModel.resetToDefaults().join()
        advanceUntilIdle()

        assertEquals(0.68f, viewModel.uiState.value.tauWarn, 0.001f)
        assertEquals(0.82f, viewModel.uiState.value.tauBlock, 0.001f)
    }

    @Test
    fun testDiagnose_populatesDiagnosticResults() = runTest(testDispatcher) {
        advanceUntilIdle()
        val job = viewModel.diagnose("You are an idiot and your mesh will fail")
        job.join()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.diagnosis)
        assertEquals("You are an idiot and your mesh will fail", state.diagnosis?.rawText)
    }

    @Test
    fun testRestoreQuarantine_movesRecordToChat() = runTest(testDispatcher) {
        val nowMs = clock.currentTimeMillis()
        val quarantineEntity = QuarantineEntity(
            msgId = 12345L,
            groupId = 42L,
            senderFp = 0x8F4CL,
            body = EncryptedBody.of("Threat message to quarantine"),
            score = 0.88f,
            nearestCategory = "threat",
            createdAtMs = nowMs,
            expiresAtMs = nowMs + 7 * 24 * 3600 * 1000L
        )
        quarantineDao.insert(quarantineEntity)
        advanceUntilIdle()

        // Restore to chat
        val job = viewModel.restoreQuarantine(12345L)
        job.join()
        advanceUntilIdle()

        // Assert removed from quarantine and added to messages with revealed = true
        assertEquals(null, quarantineDao.getById(12345L))
        val restoredMessage = messageDao.getMessage(12345L)
        assertNotNull(restoredMessage)
        assertEquals("Threat message to quarantine", restoredMessage?.body?.text)
        assertTrue(restoredMessage?.revealed == true)
    }

    @Test
    fun testDeleteQuarantine_removesMessage() = runTest(testDispatcher) {
        val nowMs = clock.currentTimeMillis()
        val entity = QuarantineEntity(
            msgId = 999L,
            groupId = 42L,
            senderFp = 0x8F4CL,
            body = EncryptedBody.of("Spam message"),
            score = 0.84f,
            nearestCategory = "harassment",
            createdAtMs = nowMs,
            expiresAtMs = nowMs + 7 * 24 * 3600 * 1000L
        )
        quarantineDao.insert(entity)
        advanceUntilIdle()

        viewModel.deleteQuarantine(999L).join()
        advanceUntilIdle()

        assertEquals(null, quarantineDao.getById(999L))
    }

    @Test
    fun testToggleForceQuarantine_updatesEntity() = runTest(testDispatcher) {
        val rawVec = ByteArray(1536)
        val entity = BlockedVectorEntity(
            id = 1L,
            vector = rawVec,
            forceQuarantine = true,
            sourceMsgId = 100L,
            createdAtMs = clock.currentTimeMillis()
        )
        blockedVectorDao.insert(entity)
        advanceUntilIdle()

        viewModel.toggleForceQuarantine(1L, true).join()
        advanceUntilIdle()

        val updated = blockedVectorDao.getById(1L)
        assertNotNull(updated)
        assertFalse(updated?.forceQuarantine ?: true)
    }

    @Test
    fun testDeleteBlockedVector_removesVector() = runTest(testDispatcher) {
        val rawVec = ByteArray(1536)
        val entity = BlockedVectorEntity(
            id = 5L,
            vector = rawVec,
            forceQuarantine = true,
            sourceMsgId = 100L,
            createdAtMs = clock.currentTimeMillis()
        )
        blockedVectorDao.insert(entity)
        advanceUntilIdle()

        viewModel.deleteBlockedVector(5L).join()
        advanceUntilIdle()

        assertEquals(null, blockedVectorDao.getById(5L))
    }
}
