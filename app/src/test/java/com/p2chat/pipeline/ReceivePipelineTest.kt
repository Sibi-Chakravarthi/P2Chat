package com.p2chat.pipeline

import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterSettings
import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.ble.PacketRouter
import com.p2chat.crypto.envelope.EnvelopeBuilder
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.FakeClock
import com.p2chat.wire.BleConstants
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

class ReceivePipelineTest {

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

    private class FakeQuarantineDao : QuarantineDao {
        val records = mutableMapOf<Long, QuarantineEntity>()
        override suspend fun insert(entity: QuarantineEntity): Long {
            records[entity.msgId] = entity
            return entity.msgId
        }
        override suspend fun getById(msgId: Long): QuarantineEntity? = records[msgId]
        override suspend fun getAll(): List<QuarantineEntity> = records.values.toList()
        override fun getAllFlow(): Flow<List<QuarantineEntity>> = flowOf(records.values.toList())
        override suspend fun count(): Int = records.size
        override suspend fun deleteById(msgId: Long): Int = if (records.remove(msgId) != null) 1 else 0
        override suspend fun purgeOlderThan(cutoffMs: Long): Int {
            val toRemove = records.values.filter { it.createdAtMs < cutoffMs }.map { it.msgId }
            toRemove.forEach { records.remove(it) }
            return toRemove.size
        }
    }

    private class RecordingPacketRouter : PacketRouter {
        val evictedMsgIds = mutableListOf<Long>()
        override suspend fun evict(msgId: Long) {
            evictedMsgIds.add(msgId)
        }
    }

    private class CountingEmbedder : SentenceEmbedder {
        var callCount = 0
        override val isAvailable: Boolean = true
        override val lastInferenceLatencyMs: Long = 1L
        var vectorToReturn = FloatArray(384) { 0f }.apply { this[1] = 1f }

        override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray {
            callCount++
            return vectorToReturn
        }
        override fun reload() {}
        override fun close() {}
    }

    private lateinit var groupDao: FakeGroupDao
    private lateinit var rosterDao: FakeRosterMemberDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var quarantineDao: FakeQuarantineDao
    private lateinit var packetRouter: RecordingPacketRouter
    private lateinit var countingEmbedder: CountingEmbedder
    private lateinit var filterService: FilterService
    private lateinit var controlHandler: ControlPacketHandler
    private lateinit var keyVault: KeyVault
    private lateinit var clock: FakeClock

    private lateinit var pipeline: ReceivePipeline
    private lateinit var senderKeyPair: KeyPair
    private val senderFp = 0x1122334455667788L
    private val groupId = 42L
    private val groupKey = ByteArray(32) { 0x42 }

    @Before
    fun setup() {
        runBlocking {
            KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
            keyVault = KeyVault()
            clock = FakeClock(10_000_000L)

            groupDao = FakeGroupDao()
            rosterDao = FakeRosterMemberDao()
            messageDao = FakeMessageDao()
            quarantineDao = FakeQuarantineDao()
            packetRouter = RecordingPacketRouter()
            countingEmbedder = CountingEmbedder()

            val store = VectorStore(FloatArray(384).apply { this[0] = 1f }, listOf("toxic"))
            val scorer = Scorer(store)
            val tokenizer = WordPieceTokenizer(mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102))

            filterService = FilterService(
                embedder = countingEmbedder,
                scorer = scorer,
                tokenizer = tokenizer,
                initialSettings = FilterSettings(tauWarn = 0.68f, tauBlock = 0.82f)
            )

            controlHandler = ControlPacketHandler(groupDao, rosterDao)

            pipeline = ReceivePipeline(
                groupDao = groupDao,
                rosterMemberDao = rosterDao,
                messageDao = messageDao,
                quarantineDao = quarantineDao,
                keyVault = keyVault,
                filterService = filterService,
                packetRouter = packetRouter,
                controlPacketHandler = controlHandler,
                clock = clock
            )

            senderKeyPair = EcProvider.get().generateKeyPair()
            val pubBytes = EcProvider.encodeUncompressedPublicKey(senderKeyPair.public)

            groupDao.insertGroup(
                GroupEntity(
                    groupId = groupId,
                    name = "Test Group",
                    myRole = "MEMBER",
                    currentEpoch = 1,
                    wrappedCurrentKey = keyVault.wrap(groupKey),
                    rosterVersion = 1
                )
            )

            rosterDao.insertOrUpdate(
                RosterMember(
                    groupId = groupId,
                    senderFp = senderFp,
                    signingPub = pubBytes,
                    ecdhPub = pubBytes,
                    alias = "Alice",
                    revoked = false
                )
            )
        }
    }

    private fun createFrame(
        msgId: Long,
        plaintext: String,
        packetType: PacketType = PacketType.CHAT,
        keyEpoch: Int = 1,
        key: ByteArray = groupKey
    ): ByteArray {
        val header = PacketHeader(
            packetType = packetType,
            ttl = BleConstants.DEFAULT_TTL,
            msgId = msgId,
            groupId = groupId,
            senderFp = senderFp,
            keyEpoch = keyEpoch
        )
        return EnvelopeBuilder.buildChatPacket(
            header = header,
            sentAtMs = clock.currentTimeMillis(),
            plaintext = plaintext,
            groupKey = key,
            signingPrivateKey = senderKeyPair.private
        )
    }

    @Test
    fun testSignatureFailure_triggersPacketRouterEvict_andSubsequentValidSucceeds() = runBlocking {
        val msgId = 998877L
        val genuineFrame = createFrame(msgId = msgId, plaintext = "Clean message")

        // 1. Tamper signature (last byte of frame)
        val corruptedFrame = genuineFrame.copyOf()
        corruptedFrame[corruptedFrame.size - 1] = (corruptedFrame[corruptedFrame.size - 1] + 1).toByte()

        // 2. Process corrupted frame
        val verdict1 = pipeline.processFrame(corruptedFrame)
        assertTrue("Must drop on bad signature", verdict1 is PacketVerdict.DropBadSignature)
        assertEquals(listOf(msgId), packetRouter.evictedMsgIds)
        assertEquals("FilterService must NOT be called on bad signature", 0, countingEmbedder.callCount)

        // 3. Process genuine frame with the same msgId
        val verdict2 = pipeline.processFrame(genuineFrame)
        assertTrue("Genuine packet must be accepted after eviction", verdict2 is PacketVerdict.Accept)
        assertEquals(1, countingEmbedder.callCount)
    }

    @Test
    fun testFilterServiceNeverCalledBeforeSignatureAndGcmPass() = runBlocking {
        // Build frame with wrong group key (GCM authentication failure)
        val wrongKey = ByteArray(32) { 0x99.toByte() }
        val badGcmFrame = createFrame(msgId = 12345L, plaintext = "Secret message", key = wrongKey)

        val verdict = pipeline.processFrame(badGcmFrame)
        assertTrue(verdict is PacketVerdict.DropAuthFail)
        assertEquals(1, packetRouter.evictedMsgIds.size)
        assertEquals("FilterService must NEVER be called if GCM decryption fails", 0, countingEmbedder.callCount)
    }

    @Test
    fun testTierRouting_Tier1QuarantineOnly_Tier2And3ToMessages() = runBlocking {
        // Case A: Tier 1 (Quarantine) - score = 0.85
        countingEmbedder.vectorToReturn = FloatArray(384) { 0f }.apply {
            this[0] = 0.85f
            this[1] = sqrt(1.0 - 0.85 * 0.85).toFloat()
        }
        val frameT1 = createFrame(msgId = 1L, plaintext = "Toxic text")
        val v1 = pipeline.processFrame(frameT1)
        assertTrue(v1 is PacketVerdict.Accept)
        assertEquals("Tier 1 must write ONLY to quarantine", 1, quarantineDao.count())
        assertEquals("Tier 1 must NOT write to messages", 0, messageDao.count())

        // Case B: Tier 2 (Warning) - score = 0.70
        countingEmbedder.vectorToReturn = FloatArray(384) { 0f }.apply {
            this[0] = 0.70f
            this[1] = sqrt(1.0 - 0.70 * 0.70).toFloat()
        }
        val frameT2 = createFrame(msgId = 2L, plaintext = "Warn text")
        val v2 = pipeline.processFrame(frameT2)
        assertTrue(v2 is PacketVerdict.Accept)
        assertEquals(1, quarantineDao.count()) // unchanged
        assertEquals(1, messageDao.count())
        val msg2 = messageDao.getMessage(2L)
        assertNotNull(msg2)
        assertEquals(2, msg2!!.tier)
        assertFalse("Tier 2 must be blurred (revealed = false)", msg2.revealed)

        // Case C: Tier 3 (Clean) - score = 0.20
        countingEmbedder.vectorToReturn = FloatArray(384) { 0f }.apply {
            this[0] = 0.20f
            this[1] = sqrt(1.0 - 0.20 * 0.20).toFloat()
        }
        val frameT3 = createFrame(msgId = 3L, plaintext = "Clean text")
        val v3 = pipeline.processFrame(frameT3)
        assertTrue(v3 is PacketVerdict.Accept)
        assertEquals(2, messageDao.count())
        val msg3 = messageDao.getMessage(3L)
        assertNotNull(msg3)
        assertEquals(3, msg3!!.tier)
        assertTrue("Tier 3 must be clean (revealed = true)", msg3.revealed)
    }

    @Test
    fun testBenchDataAndAckBypassFilterServiceAndMessages() = runBlocking {
        var benchDataReceived = false
        var benchAckReceived = false

        pipeline.benchListener = object : BenchPacketListener {
            override suspend fun onBenchData(msgId: Long, initialTtl: Int, receivedTtl: Int, payload: ByteArray) {
                benchDataReceived = true
            }
            override suspend fun onBenchAck(msgId: Long, receivedTtl: Int, ackMonoNs: Long) {
                benchAckReceived = true
            }
        }

        val benchDataFrame = createFrame(msgId = 111L, plaintext = "BENCH_PAYLOAD", packetType = PacketType.BENCH_DATA)
        val v1 = pipeline.processFrame(benchDataFrame)
        assertTrue(v1 is PacketVerdict.Accept)
        assertTrue(benchDataReceived)
        assertEquals(0, messageDao.count())
        assertEquals(0, quarantineDao.count())
        assertEquals(0, countingEmbedder.callCount)

        val benchAckFrame = createFrame(msgId = 222L, plaintext = "ACK", packetType = PacketType.BENCH_ACK)
        val v2 = pipeline.processFrame(benchAckFrame)
        assertTrue(v2 is PacketVerdict.Accept)
        assertTrue(benchAckReceived)
        assertEquals(0, messageDao.count())
        assertEquals(0, quarantineDao.count())
        assertEquals(0, countingEmbedder.callCount)
    }

    @Test
    fun testHeldEpochNPlus1PacketsAutomaticallyDecryptAndFilterWhenRotated() = runBlocking {
        val nextEpoch = 2
        val nextGroupKey = ByteArray(32) { 0x55 }

        val futureEpochFrame = createFrame(
            msgId = 777L,
            plaintext = "Message from epoch 2",
            keyEpoch = nextEpoch,
            key = nextGroupKey
        )

        // 1. Process future frame
        val verdict = pipeline.processFrame(futureEpochFrame)
        assertTrue("Must hold future epoch packet", verdict is PacketVerdict.HoldFutureEpoch)
        assertEquals(1, pipeline.getHeldPacketCount(groupId, nextEpoch))
        assertEquals(0, messageDao.count())

        // 2. Install epoch 2 key in group
        val currentGroup = groupDao.getGroup(groupId)!!
        groupDao.updateGroup(
            currentGroup.copy(
                currentEpoch = nextEpoch,
                wrappedCurrentKey = keyVault.wrap(nextGroupKey),
                prevEpoch = 1,
                wrappedPrevKey = currentGroup.wrappedCurrentKey
            )
        )

        // 3. Trigger release of held packets
        pipeline.onNewEpochKeyAvailable(groupId, nextEpoch)

        assertEquals("Held packets must be drained", 0, pipeline.getHeldPacketCount(groupId, nextEpoch))
        assertEquals("Decrypted packet must now be in messageDao", 1, messageDao.count())
        val msg = messageDao.getMessage(777L)
        assertNotNull(msg)
        assertEquals("Message from epoch 2", msg!!.body.text)
    }
}
