package com.p2chat.ble

import com.p2chat.wire.BleConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport framing, chunker, and reassembler (T2.2, NET-4, NET-12, D-17).
 * Encapsulates frames larger than BLE MTU into sequenced 4-byte transport chunks.
 *
 * Header Layout (4 bytes):
 * - Byte 0: kind (0x00 = SINGLE, 0x01 = CHUNK)
 * - Byte 1: frame_id (0-255 rolling sequence ID)
 * - Byte 2: chunk_index (0-based)
 * - Byte 3: chunk_count (total chunks in frame)
 */
object Chunker {

    private val rollingFrameId = AtomicInteger(0)

    fun nextFrameId(): Int {
        return rollingFrameId.getAndUpdate { (it + 1) and 0xFF }
    }

    /**
     * Splits a frame into transport chunk packets.
     * @param frameBytes The complete wire frame to transmit.
     * @param maxChunkSize The maximum allowed byte size for each chunk (typically MTU - 3).
     */
    fun chunk(frameBytes: ByteArray, maxChunkSize: Int = 500): List<ByteArray> {
        require(frameBytes.size <= BleConstants.MAX_FRAME_SIZE_BYTES) {
            "Frame size ${frameBytes.size} exceeds maximum ${BleConstants.MAX_FRAME_SIZE_BYTES} bytes"
        }
        val maxPayloadPerChunk = maxChunkSize - BleConstants.TRANSPORT_HEADER_SIZE_BYTES
        require(maxPayloadPerChunk > 0) { "maxChunkSize ($maxChunkSize) must be > 4" }

        val frameId = nextFrameId().toByte()

        if (frameBytes.size <= maxPayloadPerChunk) {
            val single = ByteArray(BleConstants.TRANSPORT_HEADER_SIZE_BYTES + frameBytes.size)
            single[0] = BleConstants.TRANSPORT_KIND_SINGLE
            single[1] = frameId
            single[2] = 0.toByte()
            single[3] = 1.toByte()
            System.arraycopy(frameBytes, 0, single, BleConstants.TRANSPORT_HEADER_SIZE_BYTES, frameBytes.size)
            return listOf(single)
        }

        val totalChunks = (frameBytes.size + maxPayloadPerChunk - 1) / maxPayloadPerChunk
        require(totalChunks <= 255) { "Frame size ${frameBytes.size} exceeds maximum chunk capacity" }

        val chunks = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        for (i in 0 until totalChunks) {
            val length = minOf(maxPayloadPerChunk, frameBytes.size - offset)
            val chunkPacket = ByteArray(BleConstants.TRANSPORT_HEADER_SIZE_BYTES + length)
            chunkPacket[0] = BleConstants.TRANSPORT_KIND_CHUNK
            chunkPacket[1] = frameId
            chunkPacket[2] = i.toByte()
            chunkPacket[3] = totalChunks.toByte()
            System.arraycopy(frameBytes, offset, chunkPacket, BleConstants.TRANSPORT_HEADER_SIZE_BYTES, length)
            chunks.add(chunkPacket)
            offset += length
        }
        return chunks
    }
}

/**
 * Thread-safe reassembler for incoming BLE transport frames.
 */
class Reassembler(
    private val maxFrameSizeBytes: Int = BleConstants.MAX_FRAME_SIZE_BYTES,
    private val expiryTimeoutMs: Long = 5000L
) {
    private data class PartialFrame(
        val frameId: Int,
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int = 0,
        var receivedBytes: Int = 0,
        var lastUpdatedAtMs: Long = System.currentTimeMillis()
    )

    private val sessionMap = ConcurrentHashMap<String, ConcurrentHashMap<Int, PartialFrame>>()

    /**
     * Processes an incoming raw transport packet from a peer.
     * Returns the fully reassembled wire frame if complete, or null if more chunks are needed.
     */
    fun processIncomingPacket(peerAddress: String, packet: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (packet.isEmpty()) return null

        // 1. Check if packet has the 4-byte transport header
        if (packet.size >= BleConstants.TRANSPORT_HEADER_SIZE_BYTES) {
            val kind = packet[0]
            val frameId = packet[1].toInt() and 0xFF
            val chunkIndex = packet[2].toInt() and 0xFF
            val chunkCount = packet[3].toInt() and 0xFF

            if (kind == BleConstants.TRANSPORT_KIND_SINGLE && chunkCount == 1 && chunkIndex == 0) {
                val payloadSize = packet.size - BleConstants.TRANSPORT_HEADER_SIZE_BYTES
                if (payloadSize > maxFrameSizeBytes) return null
                val frame = ByteArray(payloadSize)
                System.arraycopy(packet, BleConstants.TRANSPORT_HEADER_SIZE_BYTES, frame, 0, payloadSize)
                return frame
            }

            if (kind == BleConstants.TRANSPORT_KIND_CHUNK && chunkCount > 0 && chunkIndex < chunkCount) {
                val peerSessions = sessionMap.computeIfAbsent(peerAddress) { ConcurrentHashMap() }
                cleanExpired(peerSessions, nowMs)

                val partial = peerSessions.computeIfAbsent(frameId) {
                    PartialFrame(
                        frameId = frameId,
                        totalChunks = chunkCount,
                        chunks = arrayOfNulls(chunkCount)
                    )
                }

                // A reused frame ID must describe the same assembly.  Mixing two
                // different chunk counts could otherwise construct a frame from
                // unrelated packets.
                return synchronized(partial) {
                    if (partial.totalChunks != chunkCount) {
                        peerSessions.remove(frameId, partial)
                        return null
                    }

                    if (chunkIndex < partial.chunks.size && partial.chunks[chunkIndex] == null) {
                        val payloadSize = packet.size - BleConstants.TRANSPORT_HEADER_SIZE_BYTES
                        // Reject before retaining attacker-controlled data.  Waiting
                        // until all chunks arrive permits an oversized partial frame to
                        // consume unbounded memory.
                        if (payloadSize > maxFrameSizeBytes ||
                            partial.receivedBytes > maxFrameSizeBytes - payloadSize
                        ) {
                            peerSessions.remove(frameId, partial)
                            return null
                        }
                        val chunkData = ByteArray(payloadSize)
                        System.arraycopy(packet, BleConstants.TRANSPORT_HEADER_SIZE_BYTES, chunkData, 0, payloadSize)
                        partial.chunks[chunkIndex] = chunkData
                        partial.receivedCount++
                        partial.receivedBytes += payloadSize
                        partial.lastUpdatedAtMs = nowMs

                        if (partial.receivedCount == partial.totalChunks) {
                            peerSessions.remove(frameId, partial)
                            val totalBytes = partial.receivedBytes
                            val completeFrame = ByteArray(totalBytes)
                            var destOffset = 0
                            for (c in partial.chunks) {
                                if (c != null) {
                                    System.arraycopy(c, 0, completeFrame, destOffset, c.size)
                                    destOffset += c.size
                                }
                            }
                            return completeFrame
                        }
                    }
                    null
                }
            }
        }

        // 2. Legacy / unchunked raw fallback (packet without 4-byte transport framing).
        // It is still subject to the same frame cap as transport-framed packets.
        return packet.takeIf { it.size <= maxFrameSizeBytes }
    }

    private fun cleanExpired(sessions: ConcurrentHashMap<Int, PartialFrame>, nowMs: Long) {
        sessions.entries.removeIf { nowMs - it.value.lastUpdatedAtMs > expiryTimeoutMs }
    }
}
