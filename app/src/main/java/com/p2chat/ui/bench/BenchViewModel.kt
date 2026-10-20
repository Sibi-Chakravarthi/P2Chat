package com.p2chat.ui.bench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.bench.BenchConfig
import com.p2chat.bench.BenchProgress
import com.p2chat.bench.BenchRunner
import com.p2chat.data.bench.BenchDao
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.TtlPdrStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Operating role of this device during benchmark telemetry runs (BENCH-1).
 */
enum class BenchRole {
    SENDER,
    ECHO_NODE
}

/**
 * Unified UI state for the Multi-hop Benchmark screen (T4.10, BENCH-1, BENCH-2, UI-6, UI-7).
 */
data class BenchUiState(
    val burstCount: Int = 100,
    val intervalMs: Long = 100L,
    val payloadBytes: Int = 350,
    val initialTtl: Int = 7,
    val isTtlSweep: Boolean = false,
    val role: BenchRole = BenchRole.SENDER,
    val countError: String? = null,
    val intervalError: String? = null,
    val payloadError: String? = null,
    val progress: BenchProgress = BenchProgress(),
    val pastRuns: List<BenchRunEntity> = emptyList(),
    val selectedRunId: Long? = null,
    val selectedRunTtlStats: List<TtlPdrStats> = emptyList(),
    val isStarting: Boolean = false
) {
    val isValid: Boolean
        get() = countError == null && intervalError == null && payloadError == null &&
                burstCount in 1..500 && intervalMs in 50..2000 && payloadBytes in 32..350 && initialTtl in 1..7
}

/**
 * ViewModel driving burst parameter validation, live telemetry aggregation,
 * and historical run inspection (T4.10, BENCH-1, BENCH-2).
 */
class BenchViewModel(
    private val benchRunner: BenchRunner,
    private val benchDao: BenchDao
) : ViewModel() {

    private data class FormParams(
        val burstCount: Int = 100,
        val intervalMs: Long = 100L,
        val payloadBytes: Int = 350,
        val initialTtl: Int = 7,
        val isTtlSweep: Boolean = false,
        val role: BenchRole = BenchRole.SENDER,
        val countError: String? = null,
        val intervalError: String? = null,
        val payloadError: String? = null,
        val selectedRunId: Long? = null,
        val selectedRunTtlStats: List<TtlPdrStats> = emptyList(),
        val isStarting: Boolean = false
    )

    private val _formParams = MutableStateFlow(FormParams())

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val uiState: StateFlow<BenchUiState> = combine(
        _formParams,
        benchRunner.progress,
        benchDao.getAllRunsFlow()
    ) { params, progress, pastRuns ->
        BenchUiState(
            burstCount = params.burstCount,
            intervalMs = params.intervalMs,
            payloadBytes = params.payloadBytes,
            initialTtl = params.initialTtl,
            isTtlSweep = params.isTtlSweep,
            role = params.role,
            countError = params.countError,
            intervalError = params.intervalError,
            payloadError = params.payloadError,
            progress = progress,
            pastRuns = pastRuns,
            selectedRunId = params.selectedRunId,
            selectedRunTtlStats = params.selectedRunTtlStats,
            isStarting = params.isStarting
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = BenchUiState()
    )

    fun setBurstCount(count: Int) {
        val error = if (count in 1..500) null else "Count must be between 1 and 500"
        _formParams.value = _formParams.value.copy(
            burstCount = count,
            countError = error
        )
    }

    fun setIntervalMs(interval: Long) {
        val error = if (interval in 50..2000) null else "Interval must be between 50 and 2000 ms"
        _formParams.value = _formParams.value.copy(
            intervalMs = interval,
            intervalError = error
        )
    }

    fun setPayloadBytes(bytes: Int) {
        val error = if (bytes in 32..350) null else "Payload must be between 32 and 350 bytes"
        _formParams.value = _formParams.value.copy(
            payloadBytes = bytes,
            payloadError = error
        )
    }

    fun setInitialTtl(ttl: Int) {
        _formParams.value = _formParams.value.copy(
            initialTtl = ttl.coerceIn(1, 7)
        )
    }

    fun setTtlSweep(isSweep: Boolean) {
        _formParams.value = _formParams.value.copy(
            isTtlSweep = isSweep
        )
    }

    fun setRole(role: BenchRole) {
        _formParams.value = _formParams.value.copy(
            role = role
        )
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch {
            if (_formParams.value.selectedRunId == runId) {
                _formParams.value = _formParams.value.copy(
                    selectedRunId = null,
                    selectedRunTtlStats = emptyList()
                )
            } else {
                val stats = benchDao.getTtlPdrBreakdown(runId)
                _formParams.value = _formParams.value.copy(
                    selectedRunId = runId,
                    selectedRunTtlStats = stats
                )
            }
        }
    }

    fun startBenchmark() {
        val current = _formParams.value
        val isValid = current.countError == null && current.intervalError == null && current.payloadError == null &&
                current.burstCount in 1..500 && current.intervalMs in 50..2000 &&
                current.payloadBytes in 32..350 && current.initialTtl in 1..7

        if (!isValid) {
            viewModelScope.launch {
                _snackbarEvent.emit("Please resolve configuration errors before starting.")
            }
            return
        }

        if (benchRunner.progress.value.isRunning) {
            viewModelScope.launch {
                _snackbarEvent.emit("A benchmark run is already in progress.")
            }
            return
        }

        val config = BenchConfig(
            count = current.burstCount,
            intervalMs = current.intervalMs,
            payloadBytes = current.payloadBytes,
            initialTtl = current.initialTtl,
            isTtlSweep = current.isTtlSweep,
            isEchoTargetRole = (current.role == BenchRole.ECHO_NODE)
        )

        benchRunner.startBurst(config)
    }

    fun exportCsv(runId: Long) {
        viewModelScope.launch {
            _snackbarEvent.emit("CSV export triggered for run #$runId")
        }
    }
}
