package com.p2chat.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class ChunkerTest {

    @Test
    fun chunk_and_reassemble_singlePacket_success() {
        val data = ByteArray(100) { it.toByte() }
        val chunks = Chunker.chunk(data, maxChunkSize = 200)

        assertEquals(1, chunks.size)
        val reassembler = Reassembler()
        val reassembled = reassembler.processIncomingPacket("peer1", chunks[0])

        assertNotNull(reassembled)
        assertArrayEquals(data, reassembled)
    }

    @Test
    fun chunk_and_reassemble_multiChunk_success() {
        val random = Random(42)
        val data = ByteArray(600)
        random.nextBytes(data)

        // Split into chunks of max size 150 bytes (payload = 146 bytes)
        val chunks = Chunker.chunk(data, maxChunkSize = 150)
        assertEquals(5, chunks.size)

        val reassembler = Reassembler()
        var result: ByteArray? = null
        for (chunk in chunks) {
            result = reassembler.processIncomingPacket("peer1", chunk)
        }

        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun reassembler_handlesRawUnchunkedLegacyPacket() {
        val data = ByteArray(50) { 0x42.toByte() }
        val reassembler = Reassembler()
        // If kind byte is not 0x00 or 0x01
        data[0] = 0x05 // PACKET_TYPE_BENCH_DATA
        val result = reassembler.processIncomingPacket("peer1", data)

        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun reassembler_dropsExpiredPartialFrame() {
        val data = ByteArray(400) { it.toByte() }
        val chunks = Chunker.chunk(data, maxChunkSize = 150)

        val reassembler = Reassembler(expiryTimeoutMs = 1000)
        // Feed only first chunk at t=0
        reassembler.processIncomingPacket("peer1", chunks[0], nowMs = 0)

        // Feed second chunk at t=2000 (expired)
        val result = reassembler.processIncomingPacket("peer1", chunks[1], nowMs = 2000)
        assertNull(result)
    }
}
