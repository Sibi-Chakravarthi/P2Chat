package com.p2chat.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PublishedWithChanges
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.crypto.invite.GroupInviteCodec
import com.p2chat.crypto.invite.QrPayloadCodec
import com.p2chat.data.group.RosterMember
import com.p2chat.ui.common.ErrorState
import com.p2chat.ui.common.LoadingState
import com.p2chat.ui.onboarding.QrCodeGenerator
import com.p2chat.ui.onboarding.QrScannerScreen
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
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Group Administration and Key Rotation screen matching docs/design/4._group_admin_key_rotation/
 * (T4.6, SEC-9, SEC-10, SEC-11, UI-4, UI-6, UI-7).
 */
@Composable
fun GroupAdminScreen(
    viewModel: GroupAdminViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var memberToRemove by remember { mutableStateOf<RosterMember?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showInviteQrDialog by remember { mutableStateOf(false) }
    var inviteQrBase64 by remember { mutableStateOf<String?>(null) }

    val openInviteQrAction: () -> Unit = {
        coroutineScope.launch {
            val inviteData = viewModel.getGroupInviteData()
            if (inviteData != null) {
                inviteQrBase64 = GroupInviteCodec.encode(inviteData)
                showInviteQrDialog = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    BackHandler {
        if (showScanner) {
            showScanner = false
        } else if (showInviteQrDialog) {
            showInviteQrDialog = false
        } else if (memberToRemove != null) {
            memberToRemove = null
        } else {
            onBackClick()
        }
    }

    if (showScanner) {
        QrScannerScreen(
            onQrScanned = { rawQr ->
                val result = QrPayloadCodec.decodeFromBase64(rawQr)
                result.getOrNull()?.let { payload ->
                    viewModel.addMemberFromQr(payload)
                    openInviteQrAction()
                }
                showScanner = false
            },
            onClose = { showScanner = false }
        )
        return
    }

    if (showInviteQrDialog && inviteQrBase64 != null && uiState is GroupAdminUiState.Success) {
        val successState = uiState as GroupAdminUiState.Success
        GroupInviteQrDialog(
            groupName = successState.group.name,
            epoch = successState.group.currentEpoch,
            inviteQrBase64 = inviteQrBase64!!,
            onDismiss = { showInviteQrDialog = false }
        )
    }

    if (memberToRemove != null) {
        val member = memberToRemove!!
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = {
                Text(
                    text = "Remove Member & Rotate Key",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Removing ${member.alias} will immediately increment the group key epoch, generate a fresh AES-256-GCM key, and lock out ${member.alias} from all future group traffic. All remaining members will receive a sealed key rotation packet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fp = member.senderFp
                        memberToRemove = null
                        viewModel.removeMember(fp)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DestructiveRose,
                        contentColor = OnStarkPrimary
                    ),
                    modifier = Modifier.semantics { contentDescription = "Confirm member removal and key rotation" }
                ) {
                    Text("Remove & Rotate Key")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { memberToRemove = null },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.semantics { contentDescription = "Cancel member removal" }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceContainer,
            modifier = Modifier.border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = CanvasBackground,
        topBar = {
            AdminTopBar(
                onBackClick = onBackClick,
                onShowInviteQrClick = openInviteQrAction
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(CanvasBackground)
        ) {
            when (val state = uiState) {
                is GroupAdminUiState.Loading -> {
                    LoadingState(
                        message = "Loading cryptographic roster...",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupAdminUiState.AccessDenied -> {
                    AdminAccessDenied(
                        onBackClick = onBackClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupAdminUiState.Error -> {
                    ErrorState(
                        title = "Admin Error",
                        errorMessage = state.message,
                        onRetry = onBackClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is GroupAdminUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Group info sub-header
                        item {
                            GroupSubHeader(
                                groupName = state.group.name,
                                groupId = state.group.groupId,
                                currentEpoch = state.currentEpoch
                            )
                        }

                        // 2. Active Ratchet Key Epoch Card
                        item {
                            RatchetEpochCard(
                                currentEpoch = state.currentEpoch,
                                epochDigest = state.epochDigest,
                                graceRemainingSeconds = state.graceRemainingSeconds,
                                onRotateClick = { viewModel.rotateKeyNow() },
                                onCopyDigest = {
                                    clipboardManager.setText(AnnotatedString(state.epochDigest))
                                }
                            )
                        }

                        // 3. Signed Roster Section Header + Add Member Button
                        item {
                            RosterHeaderRow(
                                memberCount = state.members.count { !it.revoked },
                                maxMembers = BleConstants.MAX_ROSTER_MEMBERS,
                                canAddMember = state.canAddMember,
                                onAddMemberClick = { showScanner = true },
                                onShowInviteQrClick = openInviteQrAction
                            )
                        }

                        // 4. Roster Member Cards
                        items(state.members, key = { it.senderFp }) { member ->
                            RosterMemberCard(
                                member = member,
                                isAdmin = member.senderFp == state.group.groupId, // or admin identity check
                                onRemoveClick = { memberToRemove = member }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTopBar(
    onBackClick: () -> Unit,
    onShowInviteQrClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.semantics { contentDescription = "Navigate back to chat" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = TextPrimary
                )
            }
            Column {
                Text(
                    text = "Key Epoch Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "AIR-GAPPED CHANNEL",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = WarningAmber
                    )
                }
            }
        }

        IconButton(
            onClick = onShowInviteQrClick,
            modifier = Modifier.semantics { contentDescription = "Show Group Invite QR code" }
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                tint = TextPrimary
            )
        }
    }
}

@Composable
private fun GroupSubHeader(
    groupName: String,
    groupId: Long,
    currentEpoch: Int
) {
    val groupTag = "#${(groupId and 0xFFFF).toString().padStart(4, '0')}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Group Administration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Text(
                text = "$groupName • Channel $groupTag",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .background(SurfaceBright, RoundedCornerShape(20.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "EPOCH ${currentEpoch.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = WarningAmber
            )
        }
    }
}

@Composable
private fun RatchetEpochCard(
    currentEpoch: Int,
    epochDigest: String,
    graceRemainingSeconds: Int,
    onRotateClick: () -> Unit,
    onCopyDigest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PublishedWithChanges,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "ACTIVE RATCHET KEY EPOCH",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Key Epoch $currentEpoch",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .background(SurfaceBright, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "AES-256-GCM",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = VerifiedGreen
                        )
                    }
                }
            }

            // Grace Countdown Pill
            Column(horizontalAlignment = Alignment.End) {
                if (graceRemainingSeconds > 0) {
                    val minutes = graceRemainingSeconds / 60
                    val seconds = graceRemainingSeconds % 60
                    val timeStr = "%02d:%02d".format(minutes, seconds)

                    Box(
                        modifier = Modifier
                            .background(WarningAmber.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmber
                        )
                    }
                    Text(
                        text = "Grace sync window",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = TextTertiary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .background(SurfaceBright, RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Stable",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Monospace Epoch Digest Streamer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceBright, RoundedCornerShape(8.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "SHA256: $epochDigest",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }

            IconButton(
                onClick = onCopyDigest,
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = "Copy epoch SHA256 digest" }
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Rotate Key Now Action
        Button(
            onClick = onRotateClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = StarkPrimary,
                contentColor = OnStarkPrimary
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics { contentDescription = "Rotate Key Now" }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Rotate Key Now",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnStarkPrimary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Triggers multi-hop forward secrecy re-key across mesh",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RosterHeaderRow(
    memberCount: Int,
    maxMembers: Int,
    canAddMember: Boolean,
    onAddMemberClick: () -> Unit,
    onShowInviteQrClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Signed Roster",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "($memberCount / $maxMembers)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (canAddMember) VerifiedGreen else DestructiveRose
                )
            }
            Text(
                text = "Max 16 for MTU slot limits",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontSize = 10.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onShowInviteQrClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StarkPrimary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .semantics { contentDescription = "Show Group Invite QR code" }
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Invite QR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onAddMemberClick,
                enabled = canAddMember,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceBright,
                    contentColor = if (canAddMember) StarkPrimary else TextTertiary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .semantics {
                        contentDescription = if (canAddMember) "Add member via mutual QR scan" else "Add member disabled: roster full"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Add via QR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RosterMemberCard(
    member: RosterMember,
    isAdmin: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fpShort = (member.senderFp and 0xFFFFFFFFFFFFL).toString(16).uppercase().chunked(4).joinToString(":")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceBright, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isAdmin) Icons.Default.Shield else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (member.revoked) TextTertiary else StarkPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = member.alias,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (member.revoked) TextTertiary else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (member.revoked) {
                        Box(
                            modifier = Modifier
                                .background(DestructiveRose.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Revoked",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = DestructiveRose
                            )
                        }
                    }
                }

                Text(
                    text = fpShort,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }
        }

        // Action / Status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!member.revoked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .background(VerifiedGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = VerifiedGreen,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "Verified",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = VerifiedGreen
                    )
                }

                IconButton(
                    onClick = onRemoveClick,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "Remove member ${member.alias}" }
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = null,
                        tint = DestructiveRose,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Access Denied screen shown when a non-admin attempts to view group admin (UI-4, SEC-10).
 */
@Composable
private fun AdminAccessDenied(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = DestructiveRose,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Access Restricted",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Only the designated group administrator can view cryptographic keys and manage the group roster.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = StarkPrimary,
                contentColor = OnStarkPrimary
            ),
            modifier = Modifier.semantics { contentDescription = "Go back to group chat" }
        ) {
            Text("Go Back", color = OnStarkPrimary)
        }
    }
}

/**
 * High-contrast Group Invite QR dialog enabling in-person, 1-step group joining (SEC-9).
 */
@Composable
private fun GroupInviteQrDialog(
    groupName: String,
    epoch: Int,
    inviteQrBase64: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(inviteQrBase64) {
        QrCodeGenerator.generate(inviteQrBase64, sizePx = 400)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = VerifiedGreen
                )
                Text(
                    text = "Group Invite QR",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Have the target phone scan this QR code with their camera using 'Scan Peer / Invite QR' to join $groupName (Epoch $epoch) instantly (SEC-9).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Group Invite QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "Failed to render QR",
                            style = MaterialTheme.typography.bodySmall,
                            color = DestructiveRose
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StarkPrimary,
                    contentColor = OnStarkPrimary
                )
            ) {
                Text("Done")
            }
        },
        containerColor = SurfaceContainer
    )
}
