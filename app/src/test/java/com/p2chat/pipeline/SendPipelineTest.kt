package com.p2chat.pipeline

import com.p2chat.ble.MeshTransport
import com.p2chat.ble.PeerSink
import com.p2chat.crypto.envelope.EnvelopeVerifier
import com.p2chat.crypto.envelope.MessageLimits
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.mesh.BufferedPacket
import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.data.mesh.SeenMessage
import com.p2chat.data.mesh.SeenMessageDao
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.FakeClock
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

class SendPipelineTest {

    private class FakeGroupDao : GroupDao {
        val groups = mutableMapOf<Long, GroupEntity>()
        var getCount = 0
        override suspend fun insertGroup(group: GroupEntity): Long {
            groups[group.groupId] = group
            return group.groupId
        }
        override suspend fun getGroup(groupId: Long): GroupEntity? {
            getCount++
            return groups[groupId]
        }
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

    private class FakeLocalIdentityDao : LocalIdentityDao {
        var identity: LocalIdentity? = null
        var getCount = 0
        override suspend fun insertIdentity(identity: LocalIdentity): Long {
            this.identity = identity
            return identity.senderFp
        }
        override suspend fun getIdentity(): LocalIdentity? {
            getCount++
            return identity
        }
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

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<Long, MessageEntity>()
        var insertCount = 0
        override suspend fun insertMessage(message: MessageEntity): Long {
            insertCount++
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

    private class FakeSeenMessageDao : SeenMessageDao {
        val seen = mutableSetOf<Long>()
        var insertCount = 0
        override suspend fun insertIgnore(seenMessage: SeenMessage): Long {
            return if (seen.add(seenMessage.msgId)) {
                insertCount++
                seenMessage.msgId
            } else -1L
        }
        override suspend fun delete(msgId: Long): Int = if (seen.remove(msgId)) 1 else 0
        override suspend fun purgeExpired(cutoffMs: Long): Int = 0
        override suspend fun allMsgIds(): List<Long> = seen.toList()
        override suspend fun count(): Int = seen.size
        override suspend fun exists(msgId: Long): Boolean = seen.contains(msgId)
    }

    private class FakeBufferedPacketDao : BufferedPacketDao {
        val buffer = mutableMapOf<Long, BufferedPacket>()
        var insertCount = 0
        override suspend fun insertIgnore(packet: BufferedPacket): Long {
            return if (!buffer.containsKey(packet.msgId)) {
                insertCount++
                buffer[packet.msgId] = packet
                packet.msgId
            } else -1L
        }
        override suspend fun delete(msgId: Long): Int = if (buffer.remove(msgId) != null) 1 else 0
        override suspend fun purgeExpired(nowMs: Long): Int = 0
        override suspend fun countBuffer(): Int = buffer.size
        override fun observeBufferCount(): Flow<Int> = flowOf(buffer.size)
        override suspend fun allMsgIds(): List<Long> = buffer.keys.toList()
        override suspend fun getPacket(msgId: Long): BufferedPacket? = buffer[msgId]
        override suspend fun getPacketsToForward(limit: Int): List<BufferedPacket> =
            buffer.values.sortedByDescending { it.priority }.take(limit)
        override suspend fun clearAll(): Int {
            val size = buffer.size
            buffer.clear()
            return size
        }
    }

    private class FakePeerSink(override val peerId: String) : PeerSink {
        val sentFrames = mutableListOf<ByteArray>()
        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            sentFrames.add(frameBytes)
            return true
        }
    }

    private lateinit var groupDao: FakeGroupDao
    private lateinit var localIdentityDao: FakeLocalIdentityDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var seenMessageDao: FakeSeenMessageDao
    private lateinit var bufferedPacketDao: FakeBufferedPacketDao
    private lateinit var keyVault: KeyVault
    private lateinit var clock: FakeClock
    private lateinit var sinkA: FakePeerSink
    private lateinit var sinkB: FakePeerSink
    private lateinit var pipeline: SendPipeline

    private lateinit var identityKeyPair: KeyPair
    private val senderFp = 0x1122334455667788L
    private val groupId = 9999L
    private val groupKey = ByteArray(32) { 0x33 }

    @Before
    fun setup() {
        runBlocking {
            KeyVault.testKeyOverride = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
            keyVault = KeyVault()
            clock = FakeClock(10_000_000L)

            groupDao = FakeGroupDao()
            localIdentityDao = FakeLocalIdentityDao()
            messageDao = FakeMessageDao()
            seenMessageDao = FakeSeenMessageDao()
            bufferedPacketDao = FakeBufferedPacketDao()

            pipeline = SendPipeline(
                groupDao = groupDao,
                localIdentityDao = localIdentityDao,
                messageDao = messageDao,
                seenMessageDao = seenMessageDao,
                bufferedPacketDao = bufferedPacketDao,
                keyVault = keyVault,
                clock = clock
            )

            sinkA = FakePeerSink("peer_A")
            sinkB = FakePeerSink("peer_B")
            pipeline.meshTransport.registerSink(sinkA)
            pipeline.meshTransport.registerSink(sinkB)

            identityKeyPair = EcProvider.get().generateKeyPair()
            val pubBytes = EcProvider.encodeUncompressedPublicKey(identityKeyPair.public)
            val wrappedPriv = keyVault.wrapPrivateKey(identityKeyPair.private)

            localIdentityDao.insertIdentity(
                LocalIdentity(
                    senderFp = senderFp,
                    alias = "Sender",
                    signingPub = pubBytes,
                    ecdhPub = pubBytes,
                    wrappedSigningPriv = wrappedPriv,
                    wrappedEcdhPriv = wrappedPriv
                )
            )

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
        }
    }

    @Test
    fun test350ByteMessageProduces478ByteFrame_InsertsToDb_AndFloodsToPeerSinks() = runBlocking {
        // Exactly 350 ASCII characters = 350 UTF-8 bytes
        val text350 = "A".repeat(350)
        assertEquals(350, text350.toByteArray(Charsets.UTF_8).size)

        val result = pipeline.sendChatMessage(
            groupId = groupId,
            plaintext = text350,
            ttl = 7,
            explicitMsgId = 123456789L
        )

        // 1. Success check
        assertTrue("Send must succeed for 350-byte plaintext", result is SendResult.Success)
        val success = result as SendResult.Success
        assertEquals(123456789L, success.msgId)

        // 2. Exact 478-byte frame check (MessageLimits.CHAT_FRAME_SIZE_BYTES)
        assertEquals(
            "350-byte payload must produce exactly 478-byte wire frame",
            MessageLimits.CHAT_FRAME_SIZE_BYTES,
            success.frameBytes.size
        )
        assertEquals(478, success.frameBytes.size)

        // 3. Inserted into messageDao as Tier 3 Clean
        assertEquals(1, messageDao.count())
        val storedMsg = messageDao.getMessage(123456789L)
        assertNotNull(storedMsg)
        assertEquals(3, storedMsg!!.tier)
        assertTrue("Sender's own message must be revealed (Clean)", storedMsg.revealed)
        assertTrue("Sender's own message signature must be true", storedMsg.sigOk)
        assertEquals(0.0f, storedMsg.score, 0.001f)
        assertEquals(text350, storedMsg.body.text)

        // 4. Stored in seen_messages
        assertTrue(seenMessageDao.exists(123456789L))

        // 5. Stored in packet_buffer with default priority and 48h TTL
        assertEquals(1, bufferedPacketDao.countBuffer())
        val buffered = bufferedPacketDao.getPacket(123456789L)
        assertNotNull(buffered)
        assertEquals(0, buffered!!.priority)
        assertEquals(7, buffered.receivedTtl)
        assertEquals(clock.currentTimeMillis() + MessageLimits.MAX_MESSAGE_AGE_MS, buffered.expiresAtMs)

        // 6. Flooded to all registered PeerSinks
        assertEquals(1, sinkA.sentFrames.size)
        assertEquals(1, sinkB.sentFrames.size)
        assertArrayEquals(success.frameBytes, sinkA.sentFrames[0])
        assertArrayEquals(success.frameBytes, sinkB.sentFrames[0])

        // 7. Verify cryptographic integrity: recipient can verify signature and decrypt payload
        val openVerdict = EnvelopeVerifier.verifyAndOpen(
            frameBytes = success.frameBytes,
            groupKey = groupKey,
            senderSigningPubkey = EcProvider.encodeUncompressedPublicKey(identityKeyPair.public),
            clock = clock
        )
        assertTrue("Emitted frame must pass verification and decryption", openVerdict is PacketVerdict.Accept)
        val accept = openVerdict as PacketVerdict.Accept
        assertEquals(text350, accept.plaintext)
    }

    @Test
    fun test351ByteMessageReturnsMessageTooLong_WithoutTouchingCryptoOrDb() = runBlocking {
        // Exactly 351 bytes
        val text351 = "B".repeat(351)
        assertEquals(351, text351.toByteArray(Charsets.UTF_8).size)

        val result = pipeline.sendChatMessage(groupId = groupId, plaintext = text351)

        // Must return typed MessageTooLong error
        assertTrue(result is SendResult.MessageTooLong)
        val tooLong = result as SendResult.MessageTooLong
        assertEquals(351, tooLong.actualBytes)
        assertEquals(350, tooLong.maxAllowedBytes)

        // Zero calls to GroupDao, IdentityDao, MessageDao, SeenMessageDao, BufferedPacketDao
        assertEquals("GroupDao must not be touched", 0, groupDao.getCount)
        assertEquals("IdentityDao must not be touched", 0, localIdentityDao.getCount)
        assertEquals("MessageDao must not be touched", 0, messageDao.insertCount)
        assertEquals("SeenMessageDao must not be touched", 0, seenMessageDao.insertCount)
        assertEquals("BufferedPacketDao must not be touched", 0, bufferedPacketDao.insertCount)

        // Zero transmissions to PeerSinks
        assertEquals(0, sinkA.sentFrames.size)
        assertEquals(0, sinkB.sentFrames.size)
    }

    @Test
    fun testInvalidTtlReturnsInvalidTtlError() = runBlocking {
        val resZero = pipeline.sendChatMessage(groupId = groupId, plaintext = "Hi", ttl = 0)
        assertTrue(resZero is SendResult.InvalidTtl)

        val resTooHigh = pipeline.sendChatMessage(groupId = groupId, plaintext = "Hi", ttl = 16)
        assertTrue(resTooHigh is SendResult.InvalidTtl)
    }

    @Test
    fun testSendControlFrames_BuffersWithHighPriorityAndFloods() = runBlocking {
        // Construct a synthetic control frame (KEY_ROTATE)
        val header = PacketHeader(
            packetType = PacketType.KEY_ROTATE,
            ttl = 7,
            msgId = 888999L,
            groupId = groupId,
            senderFp = senderFp,
            keyEpoch = 2
        )
        // Dummy 30-byte frame with header
        val headerBytes = header.encode()
        val dummyControlFrame = ByteArray(60).apply {
            System.arraycopy(headerBytes, 0, this, 0, headerBytes.size)
        }

        val sentCount = pipeline.sendControlFrames(listOf(dummyControlFrame))
        assertEquals(1, sentCount)

        // 1. Buffered in seen_messages
        assertTrue(seenMessageDao.exists(888999L))

        // 2. Buffered in packet_buffer with high priority (PRIORITY_CONTROL = 1)
        val buffered = bufferedPacketDao.getPacket(888999L)
        assertNotNull(buffered)
        assertEquals(SendPipeline.PRIORITY_CONTROL, buffered!!.priority)
        assertEquals(1, buffered.priority)

        // 3. Flooded to all PeerSinks
        assertEquals(1, sinkA.sentFrames.size)
        assertEquals(1, sinkB.sentFrames.size)
        assertArrayEquals(dummyControlFrame, sinkA.sentFrames[0])
    }
}
