package com.p2chat.ble

import com.p2chat.wire.BleConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun chunk_rejectsFrameLargerThanProtocolLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            Chunker.chunk(ByteArray(BleConstants.MAX_FRAME_SIZE_BYTES + 1))
        }
    }

    @Test
    fun reassembler_rejectsOversizedSingleAndLegacyFrames() {
        val reassembler = Reassembler(maxFrameSizeBytes = 32)
        val oversizedSingle = ByteArray(37)
        oversizedSingle[0] = BleConstants.TRANSPORT_KIND_SINGLE
        oversizedSingle[3] = 1

        assertNull(reassembler.processIncomingPacket("peer1", oversizedSingle))
        assertNull(reassembler.processIncomingPacket("peer1", ByteArray(33) { 0x7f }))
    }

    @Test
    fun reassembler_discardsConflictingAssemblyInsteadOfMixingChunks() {
        val reassembler = Reassembler()
        val firstAssembly = byteArrayOf(
            BleConstants.TRANSPORT_KIND_CHUNK, 42, 0, 2, 0x11
        )
        val conflictingAssembly = byteArrayOf(
            BleConstants.TRANSPORT_KIND_CHUNK, 42, 0, 3, 0x22
        )
        val staleSecondChunk = byteArrayOf(
            BleConstants.TRANSPORT_KIND_CHUNK, 42, 1, 2, 0x33
        )

        assertNull(reassembler.processIncomingPacket("peer1", firstAssembly))
        assertNull(reassembler.processIncomingPacket("peer1", conflictingAssembly))
        assertNull(reassembler.processIncomingPacket("peer1", staleSecondChunk))
    }
}
