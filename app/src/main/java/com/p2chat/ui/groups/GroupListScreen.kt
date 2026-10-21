package com.p2chat.ui.groups

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.ble.BleState
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.common.ErrorState
import com.p2chat.ui.common.LoadingState
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
import com.p2chat.ui.theme.VerifiedGreen
import com.p2chat.ui.theme.WarningAmber
import kotlinx.coroutines.launch

/**
 * Group List and creation screen matching docs/design/3._group_list_e2ee_chat/ (T4.4, UI-3, ID-6, UI-6, UI-7).
 */
@Composable
fun GroupListScreen(
    viewModel: GroupListViewModel,
    onGroupClick: (groupId: Long) -> Unit,
    onScanQrClick: () -> Unit = {},
    onEnableBluetoothClick: () -> Unit = {},
    onRequestPermissionsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val bleState by viewModel.bleState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismissRequest = { showCreateDialog = false },
            onConfirmCreate = { name ->
                showCreateDialog = false
                scope.launch {
                    val result = viewModel.createGroup(name)
                    result.getOrNull()?.let { newGroupId ->
                        onGroupClick(newGroupId)
                    }
                }
            },
            onScanInviteQr = onScanQrClick
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        // 1. Top Header Bar
        GroupListTopBar(
            bleState = bleState,
            onScanQrClick = onScanQrClick,
            onCreateGroupClick = { showCreateDialog = true }
        )

        // 2. ID-6 Banner: Shown when BLE state is not READY
        if (bleState != BleState.READY) {
            BleStateBanner(
                bleState = bleState,
                onEnableBluetoothClick = onEnableBluetoothClick,
                onRequestPermissionsClick = onRequestPermissionsClick
            )
        }

        // 3. Main Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (val state = uiState) {
                is GroupListUiState.Loading -> {
                    LoadingState(
                        message = "Loading encrypted groups...",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupListUiState.Error -> {
                    ErrorState(
                        title = "Failed to Load Groups",
                        errorMessage = state.message,
                        onRetry = { viewModel.observeGroups() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupListUiState.Empty -> {
                    EmptyState(
                        title = "No Conversations",
                        message = "Join a mesh group via in-person QR invite to start exchanging end-to-end encrypted messages.",
                        actionLabel = "Create Group",
                        onActionClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupListUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.groups, key = { it.groupId }) { group ->
                            GroupCard(
                                group = group,
                                onClick = { onGroupClick(group.groupId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupListTopBar(
    bleState: BleState,
    onScanQrClick: () -> Unit,
    onCreateGroupClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMeshOnline = bleState == BleState.READY
    val transition = rememberInfiniteTransition(label = "meshPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "P2Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StarkPrimary
                    )
                    Text(
                        text = " / ",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextTertiary
                    )
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(if (isMeshOnline) pulseAlpha else 1f)
                            .background(
                                color = if (isMeshOnline) VerifiedGreen else DestructiveRose,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (isMeshOnline) "MESH ONLINE" else "MESH OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (isMeshOnline) VerifiedGreen else DestructiveRose
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onScanQrClick,
                    modifier = Modifier
                        .size(38.dp)
                        .background(SurfaceBright, CircleShape)
                        .semantics { contentDescription = "Accept Invite QR code scanner" }
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = StarkPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onCreateGroupClick,
                    modifier = Modifier
                        .size(38.dp)
                        .background(StarkPrimary, CircleShape)
                        .semantics { contentDescription = "Create New Group" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = OnStarkPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * ID-6 One-Tap Fix Banner shown when Bluetooth is off or permissions denied.
 */
@Composable
fun BleStateBanner(
    bleState: BleState,
    onEnableBluetoothClick: () -> Unit,
    onRequestPermissionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message: String
    val buttonText: String
    val buttonAction: () -> Unit
    val buttonContentDesc: String

    when (bleState) {
        BleState.BLUETOOTH_OFF -> {
            message = "Bluetooth is disabled. Mesh communications offline."
            buttonText = "Enable"
            buttonAction = onEnableBluetoothClick
            buttonContentDesc = "One-tap fix: Enable Bluetooth"
        }

        BleState.PERMISSIONS_DENIED -> {
            message = "Nearby devices permission required for BLE mesh."
            buttonText = "Grant"
            buttonAction = onRequestPermissionsClick
            buttonContentDesc = "One-tap fix: Grant Bluetooth permissions"
        }

        BleState.UNSUPPORTED -> {
            message = "Bluetooth LE mesh is unsupported on this hardware."
            buttonText = "Dismiss"
            buttonAction = {}
            buttonContentDesc = "Hardware unsupported notification"
        }

        BleState.READY -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DestructiveRose.copy(alpha = 0.15f))
            .border(1.dp, DestructiveRose.copy(alpha = 0.5f), RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "Bluetooth State Alert: $message" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = DestructiveRose,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = buttonAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = DestructiveRose,
                contentColor = OnStarkPrimary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier
                .height(30.dp)
                .semantics { contentDescription = buttonContentDesc }
        ) {
            Text(
                text = buttonText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = OnStarkPrimary
            )
        }
    }
}

/**
 * Group card displaying group name, current epoch, member count, and last message.
 */
@Composable
fun GroupCard(
    group: GroupListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val epochLabel = "EPOCH ${group.currentEpoch}"
    val membersLabel = "${group.memberCount} / ${group.maxMembers} Members"
    val groupTag = "#${(group.groupId and 0xFFFF).toString().padStart(4, '0')}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .semantics {
                contentDescription = "Group ${group.name}, $epochLabel, $membersLabel, ${group.lastMessageText ?: "No messages"}"
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Group Icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(SurfaceBright, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = StarkPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = groupTag,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Epoch badge
                        Box(
                            modifier = Modifier
                                .background(SurfaceBright, RoundedCornerShape(4.dp))
                                .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = epochLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber
                            )
                        }

                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )

                        // Member count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = null,
                                tint = VerifiedGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = membersLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }

        if (group.lastMessageText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = group.lastMessageText,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 48.dp)
            )
        }
    }
}
