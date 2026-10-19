package com.p2chat.ui.aifilter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.ai.FilterDiagnosis
import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterSettings
import com.p2chat.ai.FilterSettingsRepository
import com.p2chat.ai.QuarantineRepository
import com.p2chat.data.ai.BlockedVectorDao
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab selection for the bottom view on the AI Filter screen.
 */
enum class AiFilterTab {
    QUARANTINE,
    BLOCKLIST
}

/**
 * UI presentation model for a quarantined message record (AI-8, UI-5).
 */
data class QuarantineUiModel(
    val msgId: Long,
    val groupId: Long,
    val senderFp: Long,
    val senderTag: String,
    val text: String,
    val score: Float,
    val nearestCategory: String,
    val timeAgo: String,
    val expiresCountdown: String
)

/**
 * Unified UI state for the AI Filter Calibration and Quarantine screen (T4.8, AI-8, AI-10, AI-11, AI-17, UI-5).
 */
data class AiFilterUiState(
    val tauWarn: Float = FilterSettings.DEFAULT_TAU_WARN,
    val tauBlock: Float = FilterSettings.DEFAULT_TAU_BLOCK,
    val diagnosis: FilterDiagnosis? = null,
    val isDiagnosing: Boolean = false,
    val quarantinedMessages: List<QuarantineUiModel> = emptyList(),
    val blockedVectors: List<BlockedVectorEntity> = emptyList(),
    val selectedTab: AiFilterTab = AiFilterTab.QUARANTINE,
    val npuStatus: String = "AIR-GAPPED HARDWARE NPU • 4.2 TOPS",
    val blockedVectorSlotsUsed: Int = 0,
    val blockedVectorMaxSlots: Int = 500
)

/**
 * ViewModel driving threshold calibration sliders, live examiner test bench,
 * on-device quarantine audit log, and custom vector blocklist (T4.8).
 */
class AiFilterViewModel(
    private val filterService: FilterService,
    private val settingsRepository: FilterSettingsRepository,
    private val quarantineRepository: QuarantineRepository,
    private val blockedVectorDao: BlockedVectorDao,
    private val clock: Clock = Clock.System
) : ViewModel() {

    private val _settings = MutableStateFlow(FilterSettings())
    private val _diagnosis = MutableStateFlow<FilterDiagnosis?>(null)
    private val _isDiagnosing = MutableStateFlow(false)
    private val _selectedTab = MutableStateFlow(AiFilterTab.QUARANTINE)

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            val persisted = settingsRepository.getSettings()
            _settings.value = persisted
        }
    }

    private data class DiagnosticState(
        val diagnosis: FilterDiagnosis?,
        val isDiagnosing: Boolean
    )

    private val diagnosticStateFlow = combine(_diagnosis, _isDiagnosing) { d, id ->
        DiagnosticState(d, id)
    }

    val uiState: StateFlow<AiFilterUiState> = combine(
        _settings,
        diagnosticStateFlow,
        _selectedTab,
        quarantineRepository.observeAll(),
        blockedVectorDao.getAllFlow()
    ) { settings, diagState, tab, quarantined, blocked ->
        val nowMs = clock.currentTimeMillis()
        val quarantineModels = quarantined.map { entity ->
            val senderHex = "%04X".format(entity.senderFp and 0xFFFF)
            val elapsedMinutes = ((nowMs - entity.createdAtMs) / 60000L).coerceAtLeast(0)
            val timeAgo = when {
                elapsedMinutes < 1 -> "just now"
                elapsedMinutes < 60 -> "${elapsedMinutes}m ago"
                else -> "${elapsedMinutes / 60}h ago"
            }
            val remainingHours = ((entity.expiresAtMs - nowMs) / 3600000L).coerceAtLeast(0)
            val expiresCountdown = "${remainingHours / 24}d ${remainingHours % 24}h"

            QuarantineUiModel(
                msgId = entity.msgId,
                groupId = entity.groupId,
                senderFp = entity.senderFp,
                senderTag = "NODE_$senderHex",
                text = entity.body.text,
                score = entity.score,
                nearestCategory = entity.nearestCategory,
                timeAgo = timeAgo,
                expiresCountdown = expiresCountdown
            )
        }

        AiFilterUiState(
            tauWarn = settings.tauWarn,
            tauBlock = settings.tauBlock,
            diagnosis = diagState.diagnosis,
            isDiagnosing = diagState.isDiagnosing,
            quarantinedMessages = quarantineModels,
            blockedVectors = blocked,
            selectedTab = tab,
            blockedVectorSlotsUsed = blocked.size,
            blockedVectorMaxSlots = 500
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AiFilterUiState()
    )

    fun selectTab(tab: AiFilterTab) {
        _selectedTab.value = tab
    }

    /**
     * Updates tau_warn with clamping (AI-11):
     * 0.0 < tau_warn < tau_block - 0.01.
     */
    fun updateTauWarn(warn: Float): Job = viewModelScope.launch(Dispatchers.Default) {
        val currentBlock = _settings.value.tauBlock
        val maxAllowed = (currentBlock - FilterSettings.MIN_GAP).coerceAtLeast(0.01f)
        val clampedWarn = warn.coerceIn(0.01f, maxAllowed)

        try {
            val updated = settingsRepository.updateSettings(clampedWarn, currentBlock)
            _settings.value = updated
        } catch (_: Exception) {
            // Revert on invariant check failure
        }
    }

    /**
     * Updates tau_block with clamping (AI-11):
     * tau_warn + 0.01 <= tau_block <= 1.0.
     */
    fun updateTauBlock(block: Float): Job = viewModelScope.launch(Dispatchers.Default) {
        val currentWarn = _settings.value.tauWarn
        val minAllowed = (currentWarn + FilterSettings.MIN_GAP).coerceAtMost(1.0f)
        val clampedBlock = block.coerceIn(minAllowed, 1.0f)

        try {
            val updated = settingsRepository.updateSettings(currentWarn, clampedBlock)
            _settings.value = updated
        } catch (_: Exception) {
            // Revert on invariant check failure
        }
    }

    fun resetToDefaults(): Job = viewModelScope.launch(Dispatchers.Default) {
        val reset = settingsRepository.resetToDefaults()
        _settings.value = reset
        _snackbarEvent.emit("Reset filter thresholds to defaults (τ_warn = 0.68, τ_block = 0.82).")
    }

    /**
     * Runs live examiner diagnostic inference (AI-17).
     */
    fun diagnose(text: String): Job = viewModelScope.launch(Dispatchers.Default) {
        if (text.isBlank()) return@launch
        _isDiagnosing.value = true
        try {
            val result = filterService.diagnose(text)
            _diagnosis.value = result
        } catch (e: Exception) {
            _snackbarEvent.emit("Diagnostic failed: ${e.message}")
        } finally {
            _isDiagnosing.value = false
        }
    }

    /**
     * Restores a quarantined message into active chat (AI-8).
     */
    fun restoreQuarantine(msgId: Long): Job = viewModelScope.launch(Dispatchers.Default) {
        val success = quarantineRepository.restore(msgId)
        if (success) {
            _snackbarEvent.emit("Restored message to chat feed.")
        } else {
            _snackbarEvent.emit("Failed to restore message.")
        }
    }

    /**
     * Permanently deletes a message from quarantine (AI-8).
     */
    fun deleteQuarantine(msgId: Long): Job = viewModelScope.launch(Dispatchers.Default) {
        quarantineRepository.delete(msgId)
        _snackbarEvent.emit("Message deleted from quarantine.")
    }

    /**
     * Clears all quarantined messages.
     */
    fun clearAllQuarantine(): Job = viewModelScope.launch(Dispatchers.Default) {
        val all = quarantineRepository.getAll()
        all.forEach { quarantineRepository.delete(it.msgId) }
        _snackbarEvent.emit("Quarantine log cleared.")
    }

    /**
     * Toggles force_quarantine for a user-blocked vector (AI-10).
     */
    fun toggleForceQuarantine(id: Long, currentValue: Boolean): Job = viewModelScope.launch(Dispatchers.Default) {
        blockedVectorDao.updateForceQuarantine(id, !currentValue)
        val stateStr = if (!currentValue) "Force quarantine enabled" else "Blur warning enabled"
        _snackbarEvent.emit("$stateStr for vector #$id.")
    }

    /**
     * Removes a blocked vector from the local blocklist (AI-10).
     */
    fun deleteBlockedVector(id: Long): Job = viewModelScope.launch(Dispatchers.Default) {
        blockedVectorDao.deleteById(id)
        _snackbarEvent.emit("Removed vector #$id from custom blocklist.")
    }
}
