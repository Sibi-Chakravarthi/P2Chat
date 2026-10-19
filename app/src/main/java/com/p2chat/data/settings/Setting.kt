package com.p2chat.data.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Key-value settings entity (Decision 13).
 * Used for AI toxicity thresholds, debug toggles, and user preferences without adding DataStore.
 */
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String
)
