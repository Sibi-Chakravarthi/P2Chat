package com.p2chat.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.ble.BleState
import com.p2chat.ble.PeerConnectionState
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.groups.BleStateBanner
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tactical Mesh Radar and Peer Discovery screen (T4.7, NET-2, NET-8, NET-13, UI-2, UI-6, UI-7).
 * Matches docs/design/2._mesh_radar_peer_discovery/.
 */
@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    onEnableBluetoothClick: () -> Unit = {},
    onRequestPermissionsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
            RadarTopBar(
                isMeshActive = uiState.isMeshActive,
                activeNodeCount = uiState.peers.count { it.connectionState == PeerConnectionState.CONNECTED },
                onProfileClick = onProfileClick
            )

            // 2. ID-6 Banner: Alert when BLE hardware/permission state != READY
            if (uiState.bleState != BleState.READY) {
                BleStateBanner(
                    bleState = uiState.bleState,
                    onEnableBluetoothClick = onEnableBluetoothClick,
                    onRequestPermissionsClick = onRequestPermissionsClick
                )
            }

            // 3. Scrollable Radar Dashboard
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top Master Radio & Buffer Card
                item {
                    MasterStatusCard(
                        isMeshActive = uiState.isMeshActive,
                        pid = uiState.pid,
                        powerMw = uiState.estimatedPowerMw,
                        bufferOccupancy = uiState.bufferOccupancy,
                        bufferPercent = uiState.bufferPercent,
                        cachedBytesKb = uiState.cachedBytesKb,
                        onToggleMesh = { viewModel.toggleMesh() }
                    )
                }

                // Radar Scope Visualization Card
                item {
                    RadarScopeCard(
                        peers = uiState.peers,
                        isScanning = uiState.isScanning
                    )
                }

                // Nearby BLE Peers Section Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Nearby BLE Peers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceBright)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${uiState.peers.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = WarningAmber
                                )
                            }
                        }

                        Text(
                            text = if (uiState.isScanning) "Live (1 Hz)" else "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (uiState.isScanning) VerifiedGreen else TextTertiary
                        )
                    }
                }

                // Peer List or Empty State
                if (uiState.peers.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (uiState.isMeshActive) "Scanning for Mesh Nodes..." else "Radar Inactive",
                            message = if (uiState.isMeshActive) {
                                "No BLE peers discovered within radio range yet. Ensure nearby devices have P2Chat running."
                            } else {
                                "Mesh radio is paused. Activate radio to begin scanning for nearby nodes."
                            },
                            actionLabel = "Scan Mesh",
                            onActionClick = {
                                onRequestPermissionsClick()
                                viewModel.toggleMesh()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(uiState.peers, key = { it.address }) { peer ->
                        PeerCard(
                            peer = peer,
                            onClick = { viewModel.connectPeer(peer.address) }
                        )
                    }
                }

                // Bottom Quick Action: Broadcast Presence Beacon
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    PresenceBeaconCard(
                        isBeaconActive = uiState.isBeaconActive,
                        onToggleBeacon = { viewModel.toggleBeacon() }
                    )
                }
            }
        }
    }
}

/**
 * Top app bar with Tactical P2Chat branding and live mesh status indicator.
 */
@Composable
fun RadarTopBar(
    isMeshActive: Boolean,
    activeNodeCount: Int,
    onProfileClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarMeshPing")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RadarPulseAlpha"
    )

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
                    text = "Radar",
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
                        .background(
                            if (isMeshActive) VerifiedGreen.copy(alpha = pulseAlpha) else TextTertiary
                        )
                )
                Text(
                    text = if (isMeshActive) "MESH ONLINE • $activeNodeCount NODES" else "MESH OFFLINE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isMeshActive) VerifiedGreen else TextTertiary,
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
 * Top Status Card with Master Radio switch, PID telemetry chip, and Buffer occupancy meter.
 */
@Composable
fun MasterStatusCard(
    isMeshActive: Boolean,
    pid: Int,
    powerMw: Int,
    bufferOccupancy: Int,
    bufferPercent: Float,
    cachedBytesKb: Long,
    onToggleMesh: () -> Unit
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
        // Master Radio & Switch Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceBright),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = if (isMeshActive) WarningAmber else TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isMeshActive) "Mesh Radio Active" else "Mesh Radio Inactive",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isMeshActive) "BLE 5.2 • PHY Coded S=8" else "Radio paused • Mesh idle",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // Master Radio Switch
            TacticalSwitch(
                checked = isMeshActive,
                onCheckedChange = { onToggleMesh() },
                contentDescription = "Master Mesh Radio Toggle"
            )
        }

        // Active PID Telemetry & Power Estimate
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceBright)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isMeshActive) VerifiedGreen else TextTertiary)
                    )
                    Text(
                        text = "PID: $pid",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = if (isMeshActive) "Foreground Active" else "Foreground Inactive",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMeshActive) VerifiedGreen else TextTertiary,
                        fontSize = 10.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "$powerMw mW",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Store-and-Forward Buffer Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceBright.copy(alpha = 0.5f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Store-and-Forward Buffer",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "${"%.1f".format(bufferPercent)}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
            }

            // Progress Bar Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(CanvasBackground)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (bufferPercent / 100f).coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(WarningAmber)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$bufferOccupancy / 1000 packets",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = "$cachedBytesKb KB cached for relay",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * Radar Scope visualization with concentric tactical rings, crosshairs, and dynamic peer blips.
 */
@Composable
fun RadarScopeCard(
    peers: List<DiscoveredPeerUiModel>,
    isScanning: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweepRotation")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarSweep"
    )

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
            Column {
                Text(
                    text = "Tactical Radar Scope",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = if (isScanning) "Scanning 2.4GHz BLE PHY • Window 480/640ms" else "Scanner Idle",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (isScanning) VerifiedGreen else TextTertiary,
                    fontSize = 10.sp
                )
            }

            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(if (isScanning) sweepAngle else 0f)
            )
        }

        // Mini Scope Screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CanvasBackground)
                .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = (size.height / 2f) - 10f

                // Concentric circles
                drawCircle(
                    color = HairlineBorder,
                    radius = maxRadius * 0.33f,
                    center = center,
                    style = Stroke(width = 1f)
                )
                drawCircle(
                    color = HairlineBorder,
                    radius = maxRadius * 0.66f,
                    center = center,
                    style = Stroke(width = 1f)
                )
                drawCircle(
                    color = HairlineBorder,
                    radius = maxRadius,
                    center = center,
                    style = Stroke(width = 1f)
                )

                // Crosshairs
                drawLine(
                    color = HairlineBorder,
                    start = Offset(center.x, 10f),
                    end = Offset(center.x, size.height - 10f),
                    strokeWidth = 1f
                )
                drawLine(
                    color = HairlineBorder,
                    start = Offset(10f, center.y),
                    end = Offset(size.width - 10f, center.y),
                    strokeWidth = 1f
                )

                // Center Host Node
                drawCircle(
                    color = WarningAmber,
                    radius = 4.dp.toPx(),
                    center = center
                )

                // Map Discovered Peers on Radar Plane
                peers.forEachIndexed { index, peer ->
                    // Distance mapped from RSSI: -50 dBm is near center (0.25), -95 dBm is outer edge (0.9)
                    val normalizedDist = (( -peer.rssi - 50 ).toFloat() / 45f).coerceIn(0.2f, 0.9f)
                    val radius = maxRadius * normalizedDist
                    // Disperse around circle
                    val angleDeg = (index * (360f / peers.size.coerceAtLeast(1)) + 45f) * (Math.PI / 180f)
                    val peerX = center.x + (radius * cos(angleDeg)).toFloat()
                    val peerY = center.y + (radius * sin(angleDeg)).toFloat()

                    val blipColor = when (peer.connectionState) {
                        PeerConnectionState.CONNECTED -> VerifiedGreen
                        PeerConnectionState.CONNECTING -> WarningAmber
                        PeerConnectionState.QUEUED -> TextSecondary
                        PeerConnectionState.DISCONNECTED -> if (peer.isVerified) VerifiedGreen.copy(alpha = 0.5f) else DestructiveRose
                    }

                    drawCircle(
                        color = blipColor,
                        radius = 4.dp.toPx(),
                        center = Offset(peerX, peerY)
                    )
                }
            }
        }
    }
}

/**
 * Peer Card rendering alias, MAC, connection status, RSSI signal bars, MTU, and link role.
 */
@Composable
fun PeerCard(
    peer: DiscoveredPeerUiModel,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .semantics {
                role = androidx.compose.ui.semantics.Role.Button
                contentDescription = "Peer ${peer.alias}, State ${peer.connectionState}, RSSI ${peer.rssi} dBm"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Row: Icon + Alias/MAC + Connection Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Role Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when (peer.connectionState) {
                                PeerConnectionState.CONNECTED -> VerifiedGreen.copy(alpha = 0.15f)
                                PeerConnectionState.CONNECTING -> WarningAmber.copy(alpha = 0.15f)
                                PeerConnectionState.QUEUED -> SurfaceBright
                                PeerConnectionState.DISCONNECTED -> if (peer.isVerified) VerifiedGreen.copy(alpha = 0.15f) else DestructiveRose.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            peer.isVerified -> Icons.Default.VerifiedUser
                            peer.connectionState == PeerConnectionState.CONNECTING -> Icons.Default.Hub
                            peer.connectionState == PeerConnectionState.QUEUED -> Icons.Default.RadioButtonChecked
                            else -> Icons.Default.GppMaybe
                        },
                        contentDescription = null,
                        tint = when (peer.connectionState) {
                            PeerConnectionState.CONNECTED -> VerifiedGreen
                            PeerConnectionState.CONNECTING -> WarningAmber
                            PeerConnectionState.QUEUED -> TextSecondary
                            PeerConnectionState.DISCONNECTED -> if (peer.isVerified) VerifiedGreen else DestructiveRose
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = peer.alias,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceBright)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = peer.shortTag,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (peer.isVerified) VerifiedGreen else WarningAmber
                            )
                        }
                    }

                    Text(
                        text = "MAC: ${peer.address}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Connection Status Pill
            ConnectionStatusPill(connectionState = peer.connectionState, isVerified = peer.isVerified)
        }

        // Bottom Row: RSSI Signal Bars + dBm + MTU Badge + Role Description
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal Strength Bars
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RssiSignalBars(signalBars = peer.signalBars)
                Text(
                    text = "${peer.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = when (peer.signalBars) {
                        4 -> VerifiedGreen
                        3 -> WarningAmber
                        2 -> TextSecondary
                        else -> DestructiveRose
                    },
                    fontSize = 11.sp
                )
            }

            // MTU Badge & Role
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceBright)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "MTU ${peer.mtu}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = peer.roleDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * 4-Bar Tactical Signal Strength Meter.
 */
@Composable
fun RssiSignalBars(signalBars: Int) {
    val barColor = when (signalBars) {
        4 -> VerifiedGreen
        3 -> WarningAmber
        2 -> TextSecondary
        else -> DestructiveRose
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (signalBars >= 1) barColor else SurfaceBright)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (signalBars >= 2) barColor else SurfaceBright)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(9.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (signalBars >= 3) barColor else SurfaceBright)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (signalBars >= 4) barColor else SurfaceBright)
        )
    }
}

/**
 * Tactical Connection Status Pill.
 */
@Composable
fun ConnectionStatusPill(
    connectionState: PeerConnectionState,
    isVerified: Boolean
) {
    val (bgColor, textColor, label) = when (connectionState) {
        PeerConnectionState.CONNECTED -> Triple(VerifiedGreen.copy(alpha = 0.2f), VerifiedGreen, "CONNECTED")
        PeerConnectionState.CONNECTING -> Triple(WarningAmber.copy(alpha = 0.2f), WarningAmber, "CONNECTING")
        PeerConnectionState.QUEUED -> Triple(SurfaceBright, TextSecondary, "QUEUED — MAX 4")
        PeerConnectionState.DISCONNECTED -> if (isVerified) {
            Triple(SurfaceBright, TextTertiary, "DISCONNECTED")
        } else {
            Triple(DestructiveRose.copy(alpha = 0.2f), DestructiveRose, "UNVERIFIED")
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (connectionState == PeerConnectionState.CONNECTED) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
            } else if (connectionState == PeerConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    color = textColor,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(8.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * Bottom Quick Action: Broadcast Presence Beacon.
 */
@Composable
fun PresenceBeaconCard(
    isBeaconActive: Boolean,
    onToggleBeacon: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
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
                    .background(SurfaceBright),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = if (isBeaconActive) WarningAmber else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column {
                Text(
                    text = "Broadcast Presence Beacon",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Interval: 1280ms • Payload: ECDSA ID",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        TacticalSwitch(
            checked = isBeaconActive,
            onCheckedChange = { onToggleBeacon() },
            contentDescription = "Presence Beacon Toggle"
        )
    }
}

/**
 * Tactical Custom Switch for Dark Utilitarian Theme.
 */
@Composable
fun TacticalSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) WarningAmber else SurfaceBright)
            .clickable(onClick = { onCheckedChange(!checked) })
            .padding(2.dp)
            .semantics {
                role = androidx.compose.ui.semantics.Role.Switch
                this.contentDescription = contentDescription
            },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) OnStarkPrimary else TextTertiary),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
