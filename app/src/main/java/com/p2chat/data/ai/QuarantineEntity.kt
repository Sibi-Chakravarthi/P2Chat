package com.p2chat.data.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.p2chat.data.message.EncryptedBody

/**
 * Quarantined message entity (AI-7, AI-8).
 * Stored when toxic score meets or exceeds tau_block (default 0.82) or matches a blocked vector.
 * The body is encrypted at rest using LocalAtRestCipher under Android Keystore.
 */
@Entity(tableName = "quarantine")
data class QuarantineEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: Long,

    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "sender_fp")
    val senderFp: Long,

    @ColumnInfo(name = "body")
    val body: EncryptedBody,

    @ColumnInfo(name = "score")
    val score: Float,

    @ColumnInfo(name = "nearest_category")
    val nearestCategory: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long
)
