package com.p2chat.data.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var seenDao: SeenMessageDao
    private lateinit var bufferDao: BufferedPacketDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.createInMemory(context)
        seenDao = db.seenMessageDao()
        bufferDao = db.bufferedPacketDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testSeenMessage_insertIfAbsentAndDuplicate() = runBlocking {
        val msgId = 123456789L
        val timestamp = 1000L

        // First insert succeeds
        val insertedFirst = seenDao.insertIfAbsent(msgId, timestamp)
        assertTrue("First insert must return true", insertedFirst)
        assertTrue("Message should exist", seenDao.exists(msgId))
        assertEquals(1, seenDao.count())

        // Duplicate insert returns false
        val insertedSecond = seenDao.insertIfAbsent(msgId, timestamp + 100)
        assertFalse("Duplicate insert must return false", insertedSecond)
        assertEquals(1, seenDao.count())
    }

    @Test
    fun testSeenMessage_purgeExpired() = runBlocking {
        val cutoff = 5000L
        val oldMsg1 = 101L
        val oldMsg2 = 102L
        val freshMsg = 201L

        seenDao.insertIfAbsent(oldMsg1, 1000L)
        seenDao.insertIfAbsent(oldMsg2, 4999L)
        seenDao.insertIfAbsent(freshMsg, 5001L)

        assertEquals(3, seenDao.count())

        val purged = seenDao.purgeExpired(cutoff)
        assertEquals(2, purged)

        assertEquals(1, seenDao.count())
        assertFalse(seenDao.exists(oldMsg1))
        assertFalse(seenDao.exists(oldMsg2))
        assertTrue(seenDao.exists(freshMsg))
    }

    @Test
    fun testSeenMessage_allMsgIdsAndDelete() = runBlocking {
        val ids = listOf(10L, 20L, 30L)
        ids.forEach { seenDao.insertIfAbsent(it, 1000L) }

        val storedIds = seenDao.allMsgIds()
        assertEquals(ids.sorted(), storedIds.sorted())

        val deleted = seenDao.delete(20L)
        assertEquals(1, deleted)
        assertFalse(seenDao.exists(20L))
        assertEquals(2, seenDao.count())
    }

    @Test
    fun testBufferedPacket_insertAndDuplicate() = runBlocking {
        val packet1 = BufferedPacket(
            msgId = 1001L,
            groupId = 55L,
            packetType = 1,
            frame = byteArrayOf(0x01, 0x02, 0x03),
            receivedTtl = 5,
            storedAtMs = 1000L,
            expiresAtMs = 2000L,
            priority = 0
        )

        assertTrue(bufferDao.insertIfAbsent(packet1))
        assertFalse(bufferDao.insertIfAbsent(packet1))
        assertEquals(1, bufferDao.countBuffer())

        val retrieved = bufferDao.getPacket(1001L)
        assertNotNull(retrieved)
        assertEquals(packet1, retrieved)
    }

    @Test
    fun testBufferedPacket_purgeExpired() = runBlocking {
        val nowMs = 5000L
        val expiredPacket = BufferedPacket(
            msgId = 1L,
            groupId = 10L,
            packetType = 1,
            frame = byteArrayOf(0x01),
            receivedTtl = 3,
            storedAtMs = 1000L,
            expiresAtMs = 4999L
        )
        val validPacket = BufferedPacket(
            msgId = 2L,
            groupId = 10L,
            packetType = 1,
            frame = byteArrayOf(0x02),
            receivedTtl = 3,
            storedAtMs = 1000L,
            expiresAtMs = 5001L
        )

        bufferDao.insertIfAbsent(expiredPacket)
        bufferDao.insertIfAbsent(validPacket)
        assertEquals(2, bufferDao.countBuffer())

        val purged = bufferDao.purgeExpired(nowMs)
        assertEquals(1, purged)
        assertEquals(1, bufferDao.countBuffer())
        assertNull(bufferDao.getPacket(1L))
        assertNotNull(bufferDao.getPacket(2L))
    }

    @Test
    fun testBufferedPacket_allMsgIdsAndDelete() = runBlocking {
        val packet1 = BufferedPacket(1L, 10L, 1, byteArrayOf(1), 3, 1000L, 5000L)
        val packet2 = BufferedPacket(2L, 10L, 1, byteArrayOf(2), 3, 1000L, 5000L)

        bufferDao.insertIfAbsent(packet1)
        bufferDao.insertIfAbsent(packet2)

        assertEquals(listOf(1L, 2L), bufferDao.allMsgIds().sorted())

        assertEquals(1, bufferDao.delete(1L))
        assertEquals(1, bufferDao.countBuffer())
        assertNull(bufferDao.getPacket(1L))
    }
}
