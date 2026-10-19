package com.p2chat.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for BlockedVectorEntity validation and invariants (AI-9, AI-10).
 */
class BlockedVectorEntityTest {

    @Test
    fun testValidVectorCreation() {
        val validBlob = ByteArray(1536) { (it % 256).toByte() }
        val entity = BlockedVectorEntity(
            id = 1L,
            vector = validBlob,
            forceQuarantine = true,
            sourceMsgId = 1001L,
            createdAtMs = 1700000000000L
        )

        assertEquals(1L, entity.id)
        assertEquals(1536, entity.vector.size)
        assertTrue(entity.forceQuarantine)
        assertEquals(1001L, entity.sourceMsgId)
        assertEquals(1700000000000L, entity.createdAtMs)
    }

    @Test
    fun testRejectVectorInvalidSize_tooShort() {
        val shortBlob = ByteArray(1535)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BlockedVectorEntity(
                id = 1L,
                vector = shortBlob,
                forceQuarantine = true,
                sourceMsgId = 1001L,
                createdAtMs = 1700000000000L
            )
        }
        assertTrue(ex.message!!.contains("Vector blob must be exactly 1536 bytes"))
    }

    @Test
    fun testRejectVectorInvalidSize_tooLong() {
        val longBlob = ByteArray(1537)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BlockedVectorEntity(
                id = 1L,
                vector = longBlob,
                forceQuarantine = true,
                sourceMsgId = 1001L,
                createdAtMs = 1700000000000L
            )
        }
        assertTrue(ex.message!!.contains("Vector blob must be exactly 1536 bytes"))
    }

    @Test
    fun testRejectEmptyVector() {
        val emptyBlob = ByteArray(0)
        assertThrows(IllegalArgumentException::class.java) {
            BlockedVectorEntity(
                id = 1L,
                vector = emptyBlob,
                forceQuarantine = true,
                sourceMsgId = 1001L,
                createdAtMs = 1700000000000L
            )
        }
    }

    @Test
    fun testEqualityAndHashCode() {
        val blobA = ByteArray(1536) { 0x11 }
        val blobB = ByteArray(1536) { 0x11 }
        val blobC = ByteArray(1536) { 0x22 }

        val e1 = BlockedVectorEntity(id = 1L, vector = blobA, forceQuarantine = true, sourceMsgId = 10L, createdAtMs = 100L)
        val e2 = BlockedVectorEntity(id = 1L, vector = blobB, forceQuarantine = true, sourceMsgId = 10L, createdAtMs = 100L)
        val e3 = BlockedVectorEntity(id = 1L, vector = blobC, forceQuarantine = true, sourceMsgId = 10L, createdAtMs = 100L)

        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
        assertNotEquals(e1, e3)
    }
}
