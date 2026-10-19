package com.p2chat.ui.aifilter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.ai.FilterDiagnosis
import com.p2chat.ai.FilterVerdict
import com.p2chat.data.ai.BlockedVectorEntity
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.radar.TacticalSwitch
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.DestructiveRose
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.OnStarkPrimary
import com.p2chat.ui.theme.SurfaceBright
import com.p2chat.ui.theme.SurfaceContainer
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextSecondary
import com.p2chat.ui.theme.TextTertiary
import com.p2chat.ui.theme.VerifiedGreen
import com.p2chat.ui.theme.WarningAmber
import kotlinx.coroutines.flow.collectLatest

/**
 * AI Filter Calibration, Test Bench, Quarantine Log and Blocklist screen (T4.8, AI-8, AI-10, AI-11, AI-17, UI-5..7).
 * Matches docs/design/5._ai_filter_calibration_quarantine/.
 */
@Composable
fun AiFilterScreen(
    viewModel: AiFilterViewModel,
    onProfileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var diagnosticText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Top Header Bar
            AiFilterTopBar(onProfileClick = onProfileClick)

            // 2. Scrollable Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // NPU Acceleration State Banner
                item {
                    NpuStatusBanner(npuStatus = uiState.npuStatus)
                }

                // Card 1: Threshold Calibration Sliders
                item {
                    ThresholdCalibrationCard(
                        tauWarn = uiState.tauWarn,
                        tauBlock = uiState.tauBlock,
                        onWarnChange = { viewModel.updateTauWarn(it) },
                        onBlockChange = { viewModel.updateTauBlock(it) },
                        onResetDefaults = { viewModel.resetToDefaults() }
                    )
                }

                // Card 2: Live Examiner Test Bench
                item {
                    TestBenchCard(
                        text = diagnosticText,
                        onTextChange = { diagnosticText = it },
                        onDiagnose = { viewModel.diagnose(diagnosticText) },
                        diagnosis = uiState.diagnosis,
                        isDiagnosing = uiState.isDiagnosing
                    )
                }

                // Segmented Tabs: Quarantine Log vs Custom Blocklist
                item {
                    SegmentedControlTabs(
                        selectedTab = uiState.selectedTab,
                        quarantineCount = uiState.quarantinedMessages.size,
                        blocklistCount = uiState.blockedVectorSlotsUsed,
                        maxBlocklist = uiState.blockedVectorMaxSlots,
                        onTabSelected = { viewModel.selectTab(it) }
                    )
                }

                // Content for Selected Tab
                when (uiState.selectedTab) {
                    AiFilterTab.QUARANTINE -> {
                        item {
                            QuarantineHeaderRow(
                                onClearAll = { viewModel.clearAllQuarantine() }
                            )
                        }

                        if (uiState.quarantinedMessages.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "No Quarantined Messages",
                                    message = "Messages exceeding τ_block (0.82) are isolated here strictly post-decryption.",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp)
                                )
                            }
                        } else {
                            items(uiState.quarantinedMessages, key = { it.msgId }) { item ->
                                QuarantineMessageCard(
                                    item = item,
                                    onRestore = { viewModel.restoreQuarantine(item.msgId) },
                                    onDelete = { viewModel.deleteQuarantine(item.msgId) }
                                )
                            }
                        }
                    }

                    AiFilterTab.BLOCKLIST -> {
                        item {
                            BlocklistVaultHeader(
                                slotsUsed = uiState.blockedVectorSlotsUsed,
                                maxSlots = uiState.blockedVectorMaxSlots
                            )
                        }

                        if (uiState.blockedVectors.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "No Custom Blocked Vectors",
                                    message = "Long-press any revealed message in chat and select 'Block Similar' to block future variations.",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp)
                                )
                            }
                        } else {
                            items(uiState.blockedVectors, key = { it.id }) { vector ->
                                BlockedVectorCard(
                                    vector = vector,
                                    onToggleForceQuarantine = {
                                        viewModel.toggleForceQuarantine(vector.id, vector.forceQuarantine)
                                    },
                                    onDelete = { viewModel.deleteBlockedVector(vector.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top App Bar for AI Filter Screen.
 */
@Composable
fun AiFilterTopBar(onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceContainer)
            .border(width = 1.dp, color = HairlineBorder)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "P2Chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
                Text(
                    text = "/",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextTertiary
                )
                Text(
                    text = "Ai Filter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(VerifiedGreen)
                )
                Text(
                    text = "LOCAL FILTER ACTIVE • POST-DECRYPT ONLY",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = VerifiedGreen,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(SurfaceBright)
                .clickable(onClick = onProfileClick)
                .semantics {
                    role = androidx.compose.ui.semantics.Role.Button
                    contentDescription = "User Profile"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * NPU Acceleration State Banner.
 */
@Composable
fun NpuStatusBanner(npuStatus: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(VerifiedGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = VerifiedGreen,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column {
                Text(
                    text = "Neural Processing Engine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = npuStatus,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = VerifiedGreen,
                    fontSize = 10.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceBright)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(VerifiedGreen)
                )
                Text(
                    text = "INT8 ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = VerifiedGreen,
                    fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * Threshold Calibration Sliders Card (AI-11).
 */
@Composable
fun ThresholdCalibrationCard(
    tauWarn: Float,
    tauBlock: Float,
    onWarnChange: (Float) -> Unit,
    onBlockChange: (Float) -> Unit,
    onResetDefaults: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "On-Device TFLite Toxic Classifier",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = WarningAmber
                )
                Text(
                    text = "Quantized MiniLM-L6-v2 (int8) running locally",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = onResetDefaults,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = "Reset Thresholds to Defaults" }
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Slider 1: Warn / Blur Threshold
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Warn / Blur Threshold",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceBright)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "τ_warn = ${"%.2f".format(tauWarn)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = WarningAmber,
                        fontSize = 11.sp
                    )
                }
            }

            Slider(
                value = tauWarn,
                onValueChange = onWarnChange,
                valueRange = 0.05f..0.95f,
                colors = SliderDefaults.colors(
                    thumbColor = WarningAmber,
                    activeTrackColor = WarningAmber,
                    inactiveTrackColor = SurfaceBright
                ),
                modifier = Modifier.semantics { contentDescription = "Warn Blur Threshold Slider: ${"%.2f".format(tauWarn)}" }
            )
        }

        // Slider 2: Auto-Quarantine Threshold
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = DestructiveRose,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Auto-Quarantine Threshold",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceBright)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "τ_block = ${"%.2f".format(tauBlock)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DestructiveRose,
                        fontSize = 11.sp
                    )
                }
            }

            Slider(
                value = tauBlock,
                onValueChange = onBlockChange,
                valueRange = 0.10f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = DestructiveRose,
                    activeTrackColor = DestructiveRose,
                    inactiveTrackColor = SurfaceBright
                ),
                modifier = Modifier.semantics { contentDescription = "Auto Quarantine Threshold Slider: ${"%.2f".format(tauBlock)}" }
            )
        }

        // Explanation text box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceBright.copy(alpha = 0.4f))
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Messages with cosine score between τ_warn and τ_block are blurred. Scores ≥ τ_block are quarantined before rendering.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Live Examiner Test Bench Card (AI-17).
 */
@Composable
fun TestBenchCard(
    text: String,
    onTextChange: (String) -> Unit,
    onDiagnose: () -> Unit,
    diagnosis: FilterDiagnosis?,
    isDiagnosing: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Troubleshoot,
                    contentDescription = null,
                    tint = VerifiedGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Live Inference Diagnostic",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }

            Text(
                text = "EXAMINER TEST BENCH",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TextTertiary,
                fontSize = 9.sp
            )
        }

        // Text input field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        text = "Type tactical message probe...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDiagnose() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WarningAmber,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = WarningAmber
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Test bench message probe input" }
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(WarningAmber)
                    .clickable(enabled = !isDiagnosing && text.isNotBlank(), onClick = onDiagnose)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Run Inference Diagnostic"
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isDiagnosing) {
                    CircularProgressIndicator(
                        color = OnStarkPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = OnStarkPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Diagnose",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OnStarkPrimary
                        )
                    }
                }
            }
        }

        // Result Diagnostic Chips
        if (diagnosis != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Normalized Text
                DiagnosticChip(
                    label = "Normalized Text",
                    value = "\"${diagnosis.normalizedText}\""
                )

                // WordPiece Tokens
                DiagnosticChip(
                    label = "WordPiece Tokens (${diagnosis.tokens.size})",
                    value = diagnosis.tokens.joinToString(", ")
                )

                // Latency & Cosine Score Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Latency
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceBright.copy(alpha = 0.5f))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "LATENCY",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextTertiary,
                                fontSize = 9.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = VerifiedGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${diagnosis.latencyMs} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = VerifiedGreen
                                )
                            }
                        }
                    }

                    // Score & Tier
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceBright.copy(alpha = 0.5f))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "COSINE SCORE",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextTertiary,
                                fontSize = 9.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "s = ${"%.2f".format(diagnosis.score)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (diagnosis.verdict) {
                                        is FilterVerdict.Tier1Quarantine -> DestructiveRose
                                        is FilterVerdict.Tier2Warning -> WarningAmber
                                        else -> VerifiedGreen
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            when (diagnosis.verdict) {
                                                is FilterVerdict.Tier1Quarantine -> DestructiveRose.copy(alpha = 0.2f)
                                                is FilterVerdict.Tier2Warning -> WarningAmber.copy(alpha = 0.2f)
                                                else -> VerifiedGreen.copy(alpha = 0.2f)
                                            }
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = when (diagnosis.verdict) {
                                            is FilterVerdict.Tier1Quarantine -> "BLOCK"
                                            is FilterVerdict.Tier2Warning -> "WARN"
                                            is FilterVerdict.Tier3Clean -> "CLEAN"
                                            is FilterVerdict.NotScanned -> "NOT SCANNED"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = when (diagnosis.verdict) {
                                            is FilterVerdict.Tier1Quarantine -> DestructiveRose
                                            is FilterVerdict.Tier2Warning -> WarningAmber
                                            else -> VerifiedGreen
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Top-3 Jigsaw Categories
                if (diagnosis.topCategories.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceBright.copy(alpha = 0.5f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "TOP-3 JIGSAW CENTROID CATEGORIES",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary,
                            fontSize = 9.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            diagnosis.topCategories.take(3).forEach { cat ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SurfaceContainer)
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${cat.category} (${"%.2f".format(cat.score)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (cat.score >= 0.82f) DestructiveRose else WarningAmber,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBright.copy(alpha = 0.5f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = TextTertiary,
            fontSize = 9.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Segmented Control for Quarantine Log vs Custom Blocklist.
 */
@Composable
fun SegmentedControlTabs(
    selectedTab: AiFilterTab,
    quarantineCount: Int,
    blocklistCount: Int,
    maxBlocklist: Int,
    onTabSelected: (AiFilterTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceBright)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Tab 1: Quarantine Log
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selectedTab == AiFilterTab.QUARANTINE) WarningAmber else SurfaceBright)
                .clickable { onTabSelected(AiFilterTab.QUARANTINE) }
                .padding(vertical = 8.dp)
                .semantics {
                    role = androidx.compose.ui.semantics.Role.Tab
                    contentDescription = "Quarantine Log Tab ($quarantineCount items)"
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (selectedTab == AiFilterTab.QUARANTINE) OnStarkPrimary else TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Quarantine ($quarantineCount)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedTab == AiFilterTab.QUARANTINE) OnStarkPrimary else TextSecondary
                )
            }
        }

        // Tab 2: Custom Blocklist
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selectedTab == AiFilterTab.BLOCKLIST) WarningAmber else SurfaceBright)
                .clickable { onTabSelected(AiFilterTab.BLOCKLIST) }
                .padding(vertical = 8.dp)
                .semantics {
                    role = androidx.compose.ui.semantics.Role.Tab
                    contentDescription = "Custom Blocklist Tab ($blocklistCount/$maxBlocklist vectors)"
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = null,
                    tint = if (selectedTab == AiFilterTab.BLOCKLIST) OnStarkPrimary else TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Blocklist ($blocklistCount/$maxBlocklist)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedTab == AiFilterTab.BLOCKLIST) OnStarkPrimary else TextSecondary
                )
            }
        }
    }
}

/**
 * Quarantine Section Header.
 */
@Composable
fun QuarantineHeaderRow(onClearAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoDelete,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Local auto-purge: 7-day retention",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TextTertiary,
                fontSize = 10.sp
            )
        }

        Text(
            text = "Clear All",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = DestructiveRose,
            modifier = Modifier
                .clickable(onClick = onClearAll)
                .semantics { contentDescription = "Clear All Quarantined Messages" }
        )
    }
}

/**
 * Quarantined Message Card.
 */
@Composable
fun QuarantineMessageCard(
    item: QuarantineUiModel,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(DestructiveRose.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "s = ${"%.2f".format(item.score)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DestructiveRose,
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )

                Text(
                    text = item.nearestCategory.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = DestructiveRose,
                    fontSize = 10.sp
                )
            }

            Text(
                text = "${item.senderTag} • ${item.timeAgo}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TextTertiary,
                fontSize = 10.sp
            )
        }

        // Quarantined text bubble
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceBright.copy(alpha = 0.4f))
                .padding(10.dp)
        ) {
            Text(
                text = "\"${item.text}\"",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        // Actions: Restore to Chat and Delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarningAmber)
                    .clickable(onClick = onRestore)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Restore message to chat"
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Unarchive,
                        contentDescription = null,
                        tint = OnStarkPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Restore to Chat",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OnStarkPrimary,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SurfaceBright)
                    .clickable(onClick = onDelete)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Delete quarantined message"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = DestructiveRose,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Custom Blocklist Header Row.
 */
@Composable
fun BlocklistVaultHeader(slotsUsed: Int, maxSlots: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "User-Blocked Embeddings Vault",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            fontSize = 10.sp
        )
        Text(
            text = "$slotsUsed / $maxSlots SLOTS USED",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = WarningAmber,
            fontSize = 10.sp
        )
    }
}

/**
 * Blocked Vector Card with force_quarantine toggle and delete action.
 */
@Composable
fun BlockedVectorCard(
    vector: BlockedVectorEntity,
    onToggleForceQuarantine: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "#vector_${vector.id}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceBright)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (vector.forceQuarantine) "FORCE QUARANTINE" else "BLUR WARN",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = if (vector.forceQuarantine) DestructiveRose else WarningAmber
                    )
                }
            }

            Text(
                text = "Source msg #${vector.sourceMsgId} • 384-d normalized float32",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = TextTertiary,
                fontSize = 10.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TacticalSwitch(
                checked = vector.forceQuarantine,
                onCheckedChange = { onToggleForceQuarantine() },
                contentDescription = "Force Quarantine Toggle for Vector #${vector.id}"
            )

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SurfaceBright)
                    .clickable(onClick = onDelete)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Delete blocked vector #${vector.id}"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
