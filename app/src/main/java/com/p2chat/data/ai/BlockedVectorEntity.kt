package com.p2chat.data.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-blocked semantic vector entity (AI-9, AI-10).
 * Stores a 1,536-byte raw little-endian Float32 array (384 dimensions x 4 bytes).
 * When force_quarantine is true (default), matches with score >= 0.80 automatically quarantine.
 */
@Entity(tableName = "user_blocked_vectors")
data class BlockedVectorEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "vector")
    val vector: ByteArray,

    @ColumnInfo(name = "force_quarantine")
    val forceQuarantine: Boolean = true,

    @ColumnInfo(name = "source_msg_id")
    val sourceMsgId: Long,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long
) {
    init {
        require(vector.size == VECTOR_SIZE_BYTES) {
            "Vector blob must be exactly $VECTOR_SIZE_BYTES bytes (384 float32 values), got ${vector.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockedVectorEntity

        if (id != other.id) return false
        if (!vector.contentEquals(other.vector)) return false
        if (forceQuarantine != other.forceQuarantine) return false
        if (sourceMsgId != other.sourceMsgId) return false
        if (createdAtMs != other.createdAtMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + forceQuarantine.hashCode()
        result = 31 * result + sourceMsgId.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        return result
    }

    companion object {
        const val VECTOR_DIMENSIONS = 384
        const val VECTOR_SIZE_BYTES = VECTOR_DIMENSIONS * 4 // 1,536 bytes
    }
}
