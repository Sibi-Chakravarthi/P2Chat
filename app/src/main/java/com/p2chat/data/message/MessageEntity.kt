package com.p2chat.data.message

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Message record stored in Room (PRD §4, SEC-15).
 * Message body is encrypted at rest using LocalAtRestCipher under Android Keystore.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: Long,

    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "sender_fp")
    val senderFp: Long,

    @ColumnInfo(name = "key_epoch")
    val keyEpoch: Int,

    @ColumnInfo(name = "sent_at_ms")
    val sentAtMs: Long,

    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long,

    @ColumnInfo(name = "body")
    val body: EncryptedBody,

    @ColumnInfo(name = "sig_ok")
    val sigOk: Boolean,

    @ColumnInfo(name = "tier")
    val tier: Int = 0,

    @ColumnInfo(name = "score")
    val score: Float = 0.0f,

    @ColumnInfo(name = "revealed")
    val revealed: Boolean = false
)
