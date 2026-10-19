package com.p2chat.ai

import com.p2chat.data.settings.SettingDao

/**
 * Moderation sensitivity thresholds (T3.10, AI-11).
 *
 * Invariants enforced by [validate]:
 * - 0.0 < tauWarn < tauBlock <= 1.0
 * - tauBlock - tauWarn >= 0.01
 */
data class FilterSettings(
    val tauWarn: Float = DEFAULT_TAU_WARN,
    val tauBlock: Float = DEFAULT_TAU_BLOCK
) {
    init {
        validate(tauWarn, tauBlock)
    }

    companion object {
        const val DEFAULT_TAU_WARN = 0.68f
        const val DEFAULT_TAU_BLOCK = 0.82f
        const val MIN_GAP = 0.01f

        const val KEY_TAU_WARN = "ai_tau_warn"
        const val KEY_TAU_BLOCK = "ai_tau_block"

        /**
         * Validates threshold boundaries (AI-11).
         * Throws [IllegalArgumentException] if invalid.
         */
        fun validate(tauWarn: Float, tauBlock: Float) {
            require(tauWarn > 0.0f) {
                "tau_warn must be strictly greater than 0.0, was $tauWarn"
            }
            require(tauBlock <= 1.0f) {
                "tau_block must be at most 1.0, was $tauBlock"
            }
            require(tauBlock > tauWarn) {
                "tau_block ($tauBlock) must be strictly greater than tau_warn ($tauWarn)"
            }
            require(tauBlock - tauWarn >= (MIN_GAP - 1e-6f)) {
                "Gap between tau_block ($tauBlock) and tau_warn ($tauWarn) must be at least $MIN_GAP"
            }
        }
    }
}

/**
 * Repository for persisting and retrieving filter thresholds in the Room settings table.
 */
class FilterSettingsRepository(private val settingDao: SettingDao) {

    suspend fun getSettings(): FilterSettings {
        val warn = settingDao.getFloat(FilterSettings.KEY_TAU_WARN, FilterSettings.DEFAULT_TAU_WARN)
        val block = settingDao.getFloat(FilterSettings.KEY_TAU_BLOCK, FilterSettings.DEFAULT_TAU_BLOCK)
        return try {
            FilterSettings(warn, block)
        } catch (_: Exception) {
            FilterSettings()
        }
    }

    suspend fun updateSettings(warn: Float, block: Float): FilterSettings {
        FilterSettings.validate(warn, block)
        settingDao.putFloat(FilterSettings.KEY_TAU_WARN, warn)
        settingDao.putFloat(FilterSettings.KEY_TAU_BLOCK, block)
        return FilterSettings(warn, block)
    }
}
