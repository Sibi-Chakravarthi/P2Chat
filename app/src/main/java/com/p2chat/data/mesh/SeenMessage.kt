package com.p2chat.data.mesh

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a seen message ID for epidemic routing deduplication (NET-8, D-8).
 * Stored in Room to persist seen messages across process restarts and prevent replay loops.
 */
@Entity(tableName = "seen_messages")
data class SeenMessage(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: Long,

    @ColumnInfo(name = "first_seen_ms")
    val firstSeenMs: Long
)
