package com.p2chat.data.mesh

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing an epidemic store-and-forward buffered packet (NET-9, D-8).
 * Stores complete raw frames to forward across multi-hop encounters.
 */
@Entity(tableName = "packet_buffer")
data class BufferedPacket(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: Long,

    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "packet_type")
    val packetType: Int,

    @ColumnInfo(name = "frame", typeAffinity = ColumnInfo.BLOB)
    val frame: ByteArray,

    @ColumnInfo(name = "received_ttl")
    val receivedTtl: Int,

    @ColumnInfo(name = "stored_at_ms")
    val storedAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    @ColumnInfo(name = "priority")
    val priority: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BufferedPacket

        if (msgId != other.msgId) return false
        if (groupId != other.groupId) return false
        if (packetType != other.packetType) return false
        if (!frame.contentEquals(other.frame)) return false
        if (receivedTtl != other.receivedTtl) return false
        if (storedAtMs != other.storedAtMs) return false
        if (expiresAtMs != other.expiresAtMs) return false
        if (priority != other.priority) return false

        return true
    }

    override fun hashCode(): Int {
        var result = msgId.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + packetType
        result = 31 * result + frame.contentHashCode()
        result = 31 * result + receivedTtl
        result = 31 * result + storedAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        result = 31 * result + priority
        return result
    }
}
