package com.p2chat.bench

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Payload data for a BENCH_DATA packet (T4.9, BENCH-1, BENCH-5).
 */
data class BenchDataPayload(
    val seqNum: Int,
    val marker: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BenchDataPayload) return false
        return seqNum == other.seqNum && marker.contentEquals(other.marker)
    }

    override fun hashCode(): Int {
        var result = seqNum
        result = 31 * result + marker.contentHashCode()
        return result
    }
}

/**
 * Payload data for a compact BENCH_ACK response packet (T4.9, BENCH-1, BENCH-5).
 */
data class BenchAckPayload(
    val origMsgId: Long,
    val receivedTtl: Int,
    val initialTtl: Int
)

/**
 * Binary serializer and deserializer for inner benchmark payloads.
 */
object BenchPayloadCodec {

    fun encodeData(seqNum: Int, totalPayloadBytes: Int): ByteArray {
        require(totalPayloadBytes in 32..350) { "Payload size must be in 32..350 bytes, got $totalPayloadBytes" }
        val baos = ByteArrayOutputStream(totalPayloadBytes)
        val dos = DataOutputStream(baos)
        dos.writeInt(seqNum)
        // Fill remainder with deterministic pattern
        val remaining = totalPayloadBytes - 4
        val marker = ByteArray(remaining) { (it and 0xFF).toByte() }
        dos.write(marker)
        return baos.toByteArray()
    }

    fun decodeData(bytes: ByteArray): BenchDataPayload {
        require(bytes.size >= 4) { "Bench data payload too small: ${bytes.size} bytes" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val seqNum = dis.readInt()
        val marker = ByteArray(bytes.size - 4)
        dis.readFully(marker)
        return BenchDataPayload(seqNum, marker)
    }

    fun encodeAck(origMsgId: Long, receivedTtl: Int, initialTtl: Int): ByteArray {
        val baos = ByteArrayOutputStream(10)
        val dos = DataOutputStream(baos)
        dos.writeLong(origMsgId)
        dos.writeByte(receivedTtl and 0xFF)
        dos.writeByte(initialTtl and 0xFF)
        return baos.toByteArray()
    }

    fun decodeAck(bytes: ByteArray): BenchAckPayload {
        require(bytes.size >= 10) { "Bench ack payload too small: ${bytes.size} bytes" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val origMsgId = dis.readLong()
        val receivedTtl = dis.readByte().toInt() and 0xFF
        val initialTtl = dis.readByte().toInt() and 0xFF
        return BenchAckPayload(origMsgId, receivedTtl, initialTtl)
    }
}
