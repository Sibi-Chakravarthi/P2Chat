package com.p2chat.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.message.EncryptedBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Physical device and Room instrumented tests for Quarantine and BlockedVector tables (T3.1, AI-8, AI-9, AI-10).
 */
@RunWith(AndroidJUnit4::class)
class QuarantineAndBlockedVectorDeviceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var quarantineDao: QuarantineDao
    private lateinit var blockedVectorDao: BlockedVectorDao
    private val dbFile: File
        get() = context.getDatabasePath("test_p2chat_ai.db")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_p2chat_ai.db")
        db = androidx.room.Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "test_p2chat_ai.db"
        ).allowMainThreadQueries().build()
        quarantineDao = db.quarantineDao()
        blockedVectorDao = db.blockedVectorDao()
    }

    @After
    fun teardown() {
        db.close()
        context.deleteDatabase("test_p2chat_ai.db")
    }

    @Test
    fun testBlockedVector_refuses501stVector() = runBlocking {
        assertEquals(0, blockedVectorDao.count())

        val dummyVector = ByteArray(1536) { 0x33 }

        // Insert up to 500 vectors
        for (i in 1..500) {
            val entity = BlockedVectorEntity(
                id = 0,
                vector = dummyVector,
                forceQuarantine = true,
                sourceMsgId = i.toLong(),
                createdAtMs = 1700000000000L + i
            )
            val inserted = blockedVectorDao.insertIfBelowCap(entity, cap = 500)
            assertTrue("Expected vector $i to be accepted", inserted)
        }

        assertEquals(500, blockedVectorDao.count())

        // 501st vector insertion must be refused
        val extraEntity = BlockedVectorEntity(
            id = 0,
            vector = dummyVector,
            forceQuarantine = true,
            sourceMsgId = 501L,
            createdAtMs = 1700000001000L
        )
        val inserted501 = blockedVectorDao.insertIfBelowCap(extraEntity, cap = 500)
        assertFalse("501st blocked vector must be refused when cap is 500", inserted501)
        assertEquals(500, blockedVectorDao.count())
    }

    @Test
    fun testBlockedVector_rejectsNon1536ByteBlob() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockedVectorEntity(
                id = 0,
                vector = ByteArray(1535),
                forceQuarantine = true,
                sourceMsgId = 1L,
                createdAtMs = 1000L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            BlockedVectorEntity(
                id = 0,
                vector = ByteArray(1537),
                forceQuarantine = true,
                sourceMsgId = 1L,
                createdAtMs = 1000L
            )
        }
    }

    @Test
    fun testQuarantine_purgeRemovesOnlyRowsOlderThan7Days() = runBlocking {
        val nowMs = 1700000000000L
        val eightDaysOld = nowMs - (8L * 24 * 60 * 60 * 1000L)
        val sixDaysOld = nowMs - (6L * 24 * 60 * 60 * 1000L)
        val today = nowMs

        val qOld = QuarantineEntity(
            msgId = 101L,
            groupId = 1L,
            senderFp = 0xAAAL,
            body = EncryptedBody.of("Old toxic message"),
            score = 0.95f,
            nearestCategory = "threat",
            createdAtMs = eightDaysOld,
            expiresAtMs = eightDaysOld + QuarantineDao.SEVEN_DAYS_MS
        )
        val qMedium = QuarantineEntity(
            msgId = 102L,
            groupId = 1L,
            senderFp = 0xBBBL,
            body = EncryptedBody.of("Medium toxic message"),
            score = 0.88f,
            nearestCategory = "identity_hate",
            createdAtMs = sixDaysOld,
            expiresAtMs = sixDaysOld + QuarantineDao.SEVEN_DAYS_MS
        )
        val qNew = QuarantineEntity(
            msgId = 103L,
            groupId = 1L,
            senderFp = 0xCCCL,
            body = EncryptedBody.of("New toxic message"),
            score = 0.99f,
            nearestCategory = "severe_toxic",
            createdAtMs = today,
            expiresAtMs = today + QuarantineDao.SEVEN_DAYS_MS
        )

        quarantineDao.insert(qOld)
        quarantineDao.insert(qMedium)
        quarantineDao.insert(qNew)
        assertEquals(3, quarantineDao.count())

        val purgedCount = quarantineDao.purgeOlderThan7Days(nowMs)
        assertEquals(1, purgedCount)
        assertEquals(2, quarantineDao.count())

        assertNull(quarantineDao.getById(101L))
        assertNotNull(quarantineDao.getById(102L))
        assertNotNull(quarantineDao.getById(103L))
    }

    @Test
    fun testQuarantine_noPlaintextMarkerInRawDatabaseFile() = runBlocking {
        val secretMarker = "EXTREMELY_TOXIC_MALICIOUS_SECRET_MARKER_998877"
        val quarantine = QuarantineEntity(
            msgId = 99992L,
            groupId = 55L,
            senderFp = 0x1122334455667788L,
            body = EncryptedBody.of(secretMarker),
            score = 0.99f,
            nearestCategory = "threat",
            createdAtMs = 1700000000000L,
            expiresAtMs = 1700000000000L + QuarantineDao.SEVEN_DAYS_MS
        )

        quarantineDao.insert(quarantine)

        // Read back from DAO to verify decryption works
        val loaded = quarantineDao.getById(99992L)
        assertNotNull(loaded)
        assertEquals(secretMarker, loaded!!.body.text)
        assertFalse(loaded.body.isError)

        // Close db to flush WAL
        db.close()

        // Read raw bytes of the SQLite database file
        assertTrue(dbFile.exists())
        val dbBytes = dbFile.readBytes()
        val markerBytes = secretMarker.toByteArray(Charsets.UTF_8)

        // Invariant: marker string must NOT appear anywhere in the database file
        val found = (0..dbBytes.size - markerBytes.size).any { i ->
            markerBytes.indices.all { j -> dbBytes[i + j] == markerBytes[j] }
        }
        assertFalse("Quarantine plaintext leaked to SQLite file! Must be AES-256-GCM encrypted.", found)
    }
}
