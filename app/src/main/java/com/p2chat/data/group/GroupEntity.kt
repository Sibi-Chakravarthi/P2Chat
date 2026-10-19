package com.p2chat.data.group

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Group entity tracking key epochs, admin roles, and wrapped group keys (SEC-8, SEC-10, SEC-12).
 * Group keys are stored strictly wrapped under Keystore AES-256-GCM master key.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "group_id")
    val groupId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "my_role")
    val myRole: String,

    @ColumnInfo(name = "current_epoch")
    val currentEpoch: Int,

    @ColumnInfo(name = "wrapped_current_key", typeAffinity = ColumnInfo.BLOB)
    val wrappedCurrentKey: ByteArray,

    @ColumnInfo(name = "prev_epoch")
    val prevEpoch: Int? = null,

    @ColumnInfo(name = "wrapped_prev_key", typeAffinity = ColumnInfo.BLOB)
    val wrappedPrevKey: ByteArray? = null,

    @ColumnInfo(name = "grace_expires_ms")
    val graceExpiresMs: Long? = null,

    @ColumnInfo(name = "roster_version")
    val rosterVersion: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GroupEntity

        if (groupId != other.groupId) return false
        if (name != other.name) return false
        if (myRole != other.myRole) return false
        if (currentEpoch != other.currentEpoch) return false
        if (!wrappedCurrentKey.contentEquals(other.wrappedCurrentKey)) return false
        if (prevEpoch != other.prevEpoch) return false
        if (wrappedPrevKey != null) {
            if (other.wrappedPrevKey == null) return false
            if (!wrappedPrevKey.contentEquals(other.wrappedPrevKey)) return false
        } else if (other.wrappedPrevKey != null) return false
        if (graceExpiresMs != other.graceExpiresMs) return false
        if (rosterVersion != other.rosterVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + myRole.hashCode()
        result = 31 * result + currentEpoch
        result = 31 * result + wrappedCurrentKey.contentHashCode()
        result = 31 * result + (prevEpoch ?: 0)
        result = 31 * result + (wrappedPrevKey?.contentHashCode() ?: 0)
        result = 31 * result + (graceExpiresMs?.hashCode() ?: 0)
        result = 31 * result + rosterVersion
        return result
    }
}
