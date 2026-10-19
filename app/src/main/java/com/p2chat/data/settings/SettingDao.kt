package com.p2chat.data.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for settings table (Decision 13).
 */
@Dao
interface SettingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: Setting): Long

    suspend fun putString(key: String, value: String): Long {
        return putSetting(Setting(key, value))
    }

    suspend fun putFloat(key: String, value: Float): Long {
        return putString(key, value.toString())
    }

    suspend fun putBoolean(key: String, value: Boolean): Long {
        return putString(key, value.toString())
    }

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getString(key: String): String?

    suspend fun getFloat(key: String, defaultValue: Float): Float {
        return getString(key)?.toFloatOrNull() ?: defaultValue
    }

    suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getString(key)?.toBooleanStrictOrNull() ?: defaultValue
    }

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    fun observeString(key: String): Flow<String?>

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM settings")
    suspend fun clearAll(): Int
}
