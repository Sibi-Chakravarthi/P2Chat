package com.p2chat.ui.bench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.bench.BenchProgress
import com.p2chat.data.bench.BenchRunEntity
import com.p2chat.data.bench.TtlPdrStats
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.DestructiveRose
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.OnStarkPrimary
import com.p2chat.ui.theme.StarkPrimary
import com.p2chat.ui.theme.SurfaceBright
import com.p2chat.ui.theme.SurfaceContainer
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextSecondary
import com.p2chat.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tactical multi-hop benchmark telemetry screen (T4.10, BENCH-1, BENCH-2, UI-6, UI-7).
 */
@Composable
fun BenchScreen(
    viewModel: BenchViewModel,
    onExportCsv: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        BenchScreenContent(
            state = state,
            onSetBurstCount = viewModel::setBurstCount,
            onSetIntervalMs = viewModel::setIntervalMs,
            onSetPayloadBytes = viewModel::setPayloadBytes,
            onSetInitialTtl = viewModel::setInitialTtl,
            onSetTtlSweep = viewModel::setTtlSweep,
            onSetRole = viewModel::setRole,
            onStartBenchmark = viewModel::startBenchmark,
            onSelectRun = viewModel::selectRun,
            onExportCsv = { runId ->
                viewModel.exportCsv(runId)
                onExportCsv(runId)
            },
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun BenchScreenContent(
    state: BenchUiState,
    onSetBurstCount: (Int) -> Unit,
    onSetIntervalMs: (Long) -> Unit,
    onSetPayloadBytes: (Int) -> Unit,
    onSetInitialTtl: (Int) -> Unit,
    onSetTtlSweep: (Boolean) -> Unit,
    onSetRole: (BenchRole) -> Unit,
    onStartBenchmark: () -> Unit,
    onSelectRun: (Long) -> Unit,
    onExportCsv: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Screen Header
        item {
            BenchHeader(isRunning = state.progress.isRunning)
        }

        // 2. Role Selector (Sender vs Echo Node)
        item {
            RoleSelectorCard(
                selectedRole = state.role,
                onRoleSelected = onSetRole,
                enabled = !state.progress.isRunning
            )
        }

        // 3. Sender Burst Configuration Form (when in Sender role)
        if (state.role == BenchRole.SENDER) {
            item {
                BurstConfigCard(
                    burstCount = state.burstCount,
                    intervalMs = state.intervalMs,
                    payloadBytes = state.payloadBytes,
                    initialTtl = state.initialTtl,
                    isTtlSweep = state.isTtlSweep,
                    countError = state.countError,
                    intervalError = state.intervalError,
                    payloadError = state.payloadError,
                    isValid = state.isValid,
                    isRunning = state.progress.isRunning,
                    onSetBurstCount = onSetBurstCount,
                    onSetIntervalMs = onSetIntervalMs,
                    onSetPayloadBytes = onSetPayloadBytes,
                    onSetInitialTtl = onSetInitialTtl,
                    onSetTtlSweep = onSetTtlSweep,
                    onStartBenchmark = onStartBenchmark
                )
            }
        } else {
            // Echo Node Active Card
            item {
                EchoNodeCard(
                    isRunning = state.progress.isRunning,
                    onActivateEcho = onStartBenchmark
                )
            }
        }

        // 4. Live Telemetry & Progress
        item {
            LiveTelemetryCard(progress = state.progress)
        }

        // 5. Past Benchmark Runs Section Header
        item {
            Text(
                text = "BENCHMARK HISTORY (${state.pastRuns.size})",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(top = 8.dp)
            )
        }

        // 6. History List or Empty State
        if (state.pastRuns.isEmpty()) {
            item {
                EmptyState(
                    title = "No Benchmark Runs",
                    message = "Configure parameters above and tap Start Probe Burst to benchmark latency and PDR across the BLE GATT mesh.",
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(state.pastRuns, key = { it.runId }) { run ->
                BenchRunItemCard(
                    run = run,
                    isSelected = state.selectedRunId == run.runId,
                    ttlStats = if (state.selectedRunId == run.runId) state.selectedRunTtlStats else emptyList(),
                    onSelectRun = { onSelectRun(run.runId) },
                    onExportCsv = { onExportCsv(run.runId) }
                )
            }
        }
    }
}

@Composable
private fun BenchHeader(
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "MULTI-HOP BENCHMARK",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "BLE GATT Mesh Latency & PDR Telemetry",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }

        // Live status pill
        val statusBg = if (isRunning) DestructiveRose.copy(alpha = 0.2f) else SurfaceBright
        val statusText = if (isRunning) "PROBING" else "IDLE"
        val statusColor = if (isRunning) DestructiveRose else TextSecondary

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(statusBg)
                .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun RoleSelectorCard(
    selectedRole: BenchRole,
    onRoleSelected: (BenchRole) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoleOptionButton(
                text = "Sender (Prober)",
                icon = Icons.Default.Bolt,
                isSelected = selectedRole == BenchRole.SENDER,
                enabled = enabled,
                onClick = { onRoleSelected(BenchRole.SENDER) },
                modifier = Modifier.weight(1f)
            )
            RoleOptionButton(
                text = "Echo Node (Target)",
                icon = Icons.Default.Radar,
                isSelected = selectedRole == BenchRole.ECHO_NODE,
                enabled = enabled,
                onClick = { onRoleSelected(BenchRole.ECHO_NODE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RoleOptionButton(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) StarkPrimary else SurfaceBright
    val textColor = if (isSelected) OnStarkPrimary else TextSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp)
            .semantics {
                contentDescription = "$text role option${if (isSelected) " (Selected)" else ""}"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BurstConfigCard(
    burstCount: Int,
    intervalMs: Long,
    payloadBytes: Int,
    initialTtl: Int,
    isTtlSweep: Boolean,
    countError: String?,
    intervalError: String?,
    payloadError: String?,
    isValid: Boolean,
    isRunning: Boolean,
    onSetBurstCount: (Int) -> Unit,
    onSetIntervalMs: (Long) -> Unit,
    onSetPayloadBytes: (Int) -> Unit,
    onSetInitialTtl: (Int) -> Unit,
    onSetTtlSweep: (Boolean) -> Unit,
    onStartBenchmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "BURST GENERATOR CONFIG",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // 1. Burst Count (1..500)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Probe Count",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "$burstCount packets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarkPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = burstCount.toFloat(),
                    onValueChange = { onSetBurstCount(it.toInt()) },
                    valueRange = 1f..500f,
                    enabled = !isRunning,
                    colors = SliderDefaults.colors(
                        thumbColor = StarkPrimary,
                        activeTrackColor = StarkPrimary,
                        inactiveTrackColor = SurfaceBright
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Burst probe count slider, currently $burstCount"
                    }
                )
                // Quick presets
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(20, 50, 100, 200, 500).forEach { preset ->
                        PresetChip(
                            text = "$preset",
                            isSelected = burstCount == preset,
                            enabled = !isRunning,
                            onClick = { onSetBurstCount(preset) }
                        )
                    }
                }
                countError?.let {
                    Text(text = it, color = DestructiveRose, style = MaterialTheme.typography.labelSmall)
                }
            }

            // 2. Interval (50..2000 ms)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Burst Interval",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "$intervalMs ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarkPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = intervalMs.toFloat(),
                    onValueChange = { onSetIntervalMs(it.toLong()) },
                    valueRange = 50f..2000f,
                    enabled = !isRunning,
                    colors = SliderDefaults.colors(
                        thumbColor = StarkPrimary,
                        activeTrackColor = StarkPrimary,
                        inactiveTrackColor = SurfaceBright
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Burst interval slider, currently $intervalMs milliseconds"
                    }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50L, 100L, 250L, 500L, 1000L).forEach { preset ->
                        PresetChip(
                            text = "${preset}ms",
                            isSelected = intervalMs == preset,
                            enabled = !isRunning,
                            onClick = { onSetIntervalMs(preset) }
                        )
                    }
                }
                intervalError?.let {
                    Text(text = it, color = DestructiveRose, style = MaterialTheme.typography.labelSmall)
                }
            }

            // 3. Payload Size (32..350 B)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Payload Size",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "$payloadBytes bytes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StarkPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = payloadBytes.toFloat(),
                    onValueChange = { onSetPayloadBytes(it.toInt()) },
                    valueRange = 32f..350f,
                    enabled = !isRunning,
                    colors = SliderDefaults.colors(
                        thumbColor = StarkPrimary,
                        activeTrackColor = StarkPrimary,
                        inactiveTrackColor = SurfaceBright
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Payload size slider, currently $payloadBytes bytes"
                    }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(32, 64, 128, 256, 350).forEach { preset ->
                        PresetChip(
                            text = "${preset}B",
                            isSelected = payloadBytes == preset,
                            enabled = !isRunning,
                            onClick = { onSetPayloadBytes(preset) }
                        )
                    }
                }
                payloadError?.let {
                    Text(text = it, color = DestructiveRose, style = MaterialTheme.typography.labelSmall)
                }
            }

            // 4. TTL Mode: Single TTL vs Full Sweep (1..7)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Full TTL Sweep Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isTtlSweep) "Cycles initial TTL 1..7 across probes" else "Fixed initial TTL for all probes",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
                Switch(
                    checked = isTtlSweep,
                    onCheckedChange = onSetTtlSweep,
                    enabled = !isRunning,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StarkPrimary,
                        checkedTrackColor = SurfaceBright
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "TTL Sweep mode toggle, currently ${if (isTtlSweep) "Enabled" else "Disabled"}"
                    }
                )
            }

            if (!isTtlSweep) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Initial TTL (Hops)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "$initialTtl",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StarkPrimary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = initialTtl.toFloat(),
                        onValueChange = { onSetInitialTtl(it.toInt()) },
                        valueRange = 1f..7f,
                        steps = 5,
                        enabled = !isRunning,
                        colors = SliderDefaults.colors(
                            thumbColor = StarkPrimary,
                            activeTrackColor = StarkPrimary,
                            inactiveTrackColor = SurfaceBright
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Initial TTL slider, currently $initialTtl hops"
                        }
                    )
                }
            }

            // 5. Start Action Button
            Button(
                onClick = onStartBenchmark,
                enabled = isValid && !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StarkPrimary,
                    contentColor = OnStarkPrimary,
                    disabledContainerColor = SurfaceBright,
                    disabledContentColor = TextTertiary
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = if (isRunning) "Benchmark in progress" else "Transmit probe burst"
                    }
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Repeat else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "BURST IN PROGRESS..." else "TRANSMIT PROBE BURST",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EchoNodeCard(
    isRunning: Boolean,
    onActivateEcho: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = StarkPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TARGET ECHO NODE (ACK REFLECTOR)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "When active, this device listens on the BLE GATT mesh for incoming BENCH_DATA packets and immediately reflects a signed BENCH_ACK carrying the received TTL with default TTL = 7.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Text(
                text = "Guarantees zero leakage into chat message storage or Layer 3 content moderation filters (BENCH-5).",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )

            Button(
                onClick = onActivateEcho,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StarkPrimary,
                    contentColor = OnStarkPrimary
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics {
                        contentDescription = "Activate target echo node mode"
                    }
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "ACTIVATE ECHO MODE", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PresetChip(
    text: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = StarkPrimary,
            selectedLabelColor = OnStarkPrimary,
            containerColor = SurfaceBright,
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = HairlineBorder,
            selectedBorderColor = StarkPrimary,
            enabled = enabled,
            selected = isSelected
        ),
        modifier = Modifier.semantics {
            contentDescription = "Select preset $text"
        }
    )
}

@Composable
private fun LiveTelemetryCard(
    progress: BenchProgress,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REAL-TIME TELEMETRY (BENCH-2)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (progress.totalCount > 0) {
                    Text(
                        text = "${progress.sentCount}/${progress.totalCount} sent",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Live progress bar
            if (progress.isRunning) {
                val progressFraction = if (progress.totalCount > 0) {
                    progress.sentCount.toFloat() / progress.totalCount.toFloat()
                } else 0f

                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .semantics {
                            contentDescription = "Benchmark burst transmission progress: ${(progressFraction * 100).toInt()}%"
                        },
                    color = StarkPrimary,
                    trackColor = SurfaceBright
                )
            }

            Text(
                text = progress.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (progress.isRunning) StarkPrimary else TextSecondary,
                fontFamily = FontFamily.Monospace
            )

            // 4 Telemetry Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricTile(
                    title = "DELIVERY (PDR)",
                    value = "${"%.1f".format(progress.pdrPercent)}%",
                    subtitle = "${progress.ackedCount} acked",
                    icon = Icons.Default.Analytics,
                    highlight = progress.pdrPercent >= 95f,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    title = "RTT / 2 (P95)",
                    value = "${"%.1f".format(progress.rttHalfP95Ms)} ms",
                    subtitle = "p50: ${"%.1f".format(progress.rttHalfP50Ms)} ms",
                    icon = Icons.Default.Timer,
                    highlight = progress.rttHalfP95Ms in 0.1f..500f,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricTile(
                    title = "THROUGHPUT",
                    value = "${"%.0f".format(progress.throughputBps)} B/s",
                    subtitle = "GATT payload",
                    icon = Icons.Default.Speed,
                    highlight = progress.throughputBps > 0f,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    title = "ON-DEVICE AI",
                    value = "${"%.1f".format(progress.inferenceP95Ms)} ms",
                    subtitle = "p50: ${"%.1f".format(progress.inferenceP50Ms)} ms",
                    icon = Icons.Default.Memory,
                    highlight = progress.inferenceP95Ms in 0.1f..35f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceBright)
            .border(1.dp, HairlineBorder, RoundedCornerShape(6.dp))
            .padding(10.dp)
            .semantics {
                contentDescription = "$title: $value, $subtitle"
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) StarkPrimary else TextTertiary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlight) StarkPrimary else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun BenchRunItemCard(
    run: BenchRunEntity,
    isSelected: Boolean,
    ttlStats: List<TtlPdrStats>,
    onSelectRun: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val formattedDate = remember(run.startedAtMs) { dateFormat.format(Date(run.startedAtMs)) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (isSelected) StarkPrimary else HairlineBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelectRun)
            .semantics {
                contentDescription = "Benchmark run #${run.runId} from $formattedDate with PDR ${"%.1f".format(run.pdrPercent)} percent"
            },
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Run ID, Date, and Export Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RUN #${run.runId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StarkPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }

                Button(
                    onClick = onExportCsv,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceBright,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .semantics {
                            contentDescription = "Export CSV for run #${run.runId}"
                        }
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "EXPORT CSV", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                }
            }

            // Burst Specs
            val ttlDisplay = if (run.initialTtl == 0) "Sweep (1..7)" else "${run.initialTtl}"
            Text(
                text = "${run.burstCount} probes • ${run.intervalMs}ms interval • ${run.payloadBytes}B • TTL $ttlDisplay",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )

            // Results Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(label = "PDR", value = "${"%.1f".format(run.pdrPercent)}%")
                MetricColumn(label = "RTT/2 P50", value = "${"%.1f".format(run.rttHalfP50Ms)}ms")
                MetricColumn(label = "RTT/2 P95", value = "${"%.1f".format(run.rttHalfP95Ms)}ms")
                MetricColumn(label = "THROUGHPUT", value = "${"%.0f".format(run.throughputBps)}B/s")
                MetricColumn(label = "AI P95", value = "${"%.1f".format(run.inferenceP95Ms)}ms")
            }

            // Device and peer info
            if (run.deviceModels.isNotBlank()) {
                Text(
                    text = "${run.deviceModels} • ${run.connectedPeers} peers",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }

            // Expandable Per-TTL breakdown
            AnimatedVisibility(visible = isSelected, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(SurfaceBright, RoundedCornerShape(4.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "PER-TTL DELIVERY BREAKDOWN",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarkPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    if (ttlStats.isEmpty()) {
                        Text(
                            text = "Loading per-TTL statistics...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    } else {
                        ttlStats.forEach { stat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "TTL ${stat.initialTtl}:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${stat.delivered}/${stat.total} delivered (${"%.1f".format(stat.pdrPercent)}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
