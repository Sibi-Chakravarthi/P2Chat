package com.p2chat.data.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageAtRestCipherTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var messageDao: MessageDao
    private val dbFile: File
        get() = context.getDatabasePath("test_p2chat_at_rest.db")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_p2chat_at_rest.db")
        db = androidx.room.Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "test_p2chat_at_rest.db"
        ).allowMainThreadQueries().build()
        messageDao = db.messageDao()
    }

    @After
    fun teardown() {
        db.close()
        context.deleteDatabase("test_p2chat_at_rest.db")
    }

    @Test
    fun testAtRestCipher_noPlaintextMarkerInRawDatabaseFile() = runBlocking {
        val secretMarker = "TOP_SECRET_PROTEST_COORDINATION_MARKER_98765"
        val message = MessageEntity(
            msgId = 99991L,
            groupId = 42L,
            senderFp = 0xAABBCCDDEEFF0011L,
            keyEpoch = 1,
            sentAtMs = 1700000000000L,
            receivedAtMs = 1700000001000L,
            body = EncryptedBody.of(secretMarker),
            sigOk = true,
            tier = 0,
            score = 0.05f,
            revealed = false
        )

        messageDao.insertMessage(message)

        // Force WAL checkpoint so data is committed to main DB file
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL);")

        // Read all raw bytes of the database file and wal file
        val dbBytes = if (dbFile.exists()) dbFile.readBytes() else ByteArray(0)
        val walFile = File(dbFile.path + "-wal")
        val walBytes = if (walFile.exists()) walFile.readBytes() else ByteArray(0)

        val markerBytes = secretMarker.toByteArray(Charsets.UTF_8)

        fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty() || haystack.size < needle.size) return false
            for (i in 0..(haystack.size - needle.size)) {
                var found = true
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        found = false
                        break
                    }
                }
                if (found) return true
            }
            return false
        }

        assertFalse("Raw SQLite DB file must NOT contain plaintext marker!", containsSubarray(dbBytes, markerBytes))
        assertFalse("Raw SQLite WAL file must NOT contain plaintext marker!", containsSubarray(walBytes, markerBytes))

        // Read-back equals original
        val retrieved = messageDao.getMessage(message.msgId)
        assertNotNull(retrieved)
        assertFalse(retrieved!!.body.isError)
        assertEquals(secretMarker, retrieved.body.text)
    }

    @Test
    fun testAtRestCipher_twoIdenticalBodiesProduceDifferentCiphertexts() {
        val cipher = LocalAtRestCipher()
        val body = EncryptedBody.of("Identical message payload")

        val blob1 = cipher.toBlob(body)
        val blob2 = cipher.toBlob(body)

        assertNotNull(blob1)
        assertNotNull(blob2)
        assertFalse(
            "Two identical bodies must produce different ciphertexts due to fresh random IV",
            blob1.contentEquals(blob2)
        )

        // Both decrypt back to the identical plaintext
        val decrypted1 = cipher.fromBlob(blob1)
        val decrypted2 = cipher.fromBlob(blob2)
        assertEquals("Identical message payload", decrypted1?.text)
        assertEquals("Identical message payload", decrypted2?.text)
    }

    @Test
    fun testAtRestCipher_corruptedBlobYieldsTypedErrorNeverCrashes() {
        val cipher = LocalAtRestCipher()
        val validBlob = cipher.toBlob(EncryptedBody.of("Integrity verified text"))
        assertNotNull(validBlob)

        // Corrupt one byte of ciphertext/tag
        val corruptedBlob = validBlob!!.copyOf()
        corruptedBlob[corruptedBlob.size - 1] = (corruptedBlob[corruptedBlob.size - 1] + 1).toByte()

        val result = cipher.fromBlob(corruptedBlob)
        assertNotNull(result)
        assertTrue("Corrupted blob must return isError = true", result!!.isError)
        assertNotNull("Error description must be present", result.error)
        assertEquals("", result.text)

        // Truncated blob
        val truncatedBlob = ByteArray(10) { 0x01 }
        val truncatedResult = cipher.fromBlob(truncatedBlob)
        assertNotNull(truncatedResult)
        assertTrue("Truncated blob must return error", truncatedResult!!.isError)
    }
}
