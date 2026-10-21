package com.p2chat.ui.chat

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2chat.crypto.envelope.MessageLimits
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
import kotlinx.coroutines.flow.collectLatest

/**
 * End-to-end encrypted ChatScreen matching docs/design/3._chat_screen/ (T4.5, SEC-19, UI-3, AI-7, AI-9).
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onAdminClick: (groupId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val group by viewModel.group.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    val inputBytes = remember(inputText) { inputText.toByteArray(Charsets.UTF_8).size }
    val isOverLimit = inputBytes > MessageLimits.MAX_PLAINTEXT_BYTES
    val canSend = inputText.trim().isNotEmpty() && !isOverLimit

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = CanvasBackground,
        topBar = {
            ChatTopBar(
                groupName = group?.name ?: "Group Chat",
                groupId = viewModel.groupId,
                epoch = group?.currentEpoch ?: 1,
                isAdmin = group?.myRole == "ADMIN",
                onBackClick = onBackClick,
                onAdminClick = { onAdminClick(viewModel.groupId) }
            )
        },
        bottomBar = {
            ChatComposer(
                text = inputText,
                onTextChanged = { inputText = it },
                byteCount = inputBytes,
                maxBytes = MessageLimits.MAX_PLAINTEXT_BYTES,
                canSend = canSend,
                isOverLimit = isOverLimit,
                onSendClick = {
                    if (canSend) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(CanvasBackground)
        ) {
            // Live E2EE Ratchet telemetry bar
            ChatTelemetryBar(epoch = group?.currentEpoch ?: 1)

            // Message Stream
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet.\nAll messages sent in this group are end-to-end encrypted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(messages, key = { it.msgId }) { message ->
                        MessageBubble(
                            message = message,
                            onRevealClick = { msgId -> viewModel.revealMessage(msgId) },
                            onBlockSimilarClick = { msgId -> viewModel.blockSimilar(msgId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    groupName: String,
    groupId: Long,
    epoch: Int,
    isAdmin: Boolean,
    onBackClick: () -> Unit,
    onAdminClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupTag = "#${(groupId and 0xFFFF).toString().padStart(4, '0')}"

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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.semantics { contentDescription = "Navigate back to group list" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = TextPrimary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.titleMedium,
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "EPOCH $epoch",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = WarningAmber
                    )
                    Text(text = "•", color = TextTertiary, fontSize = 10.sp)
                    Text(
                        text = "E2EE AES-256-GCM",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Admin button shown ONLY when myRole == ADMIN (T4.5 Do #6)
        if (isAdmin) {
            Button(
                onClick = onAdminClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceBright,
                    contentColor = WarningAmber
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .semantics { contentDescription = "Open Group Cryptographic Admin" }
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Admin",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
            }
        }
    }
}

@Composable
private fun ChatTelemetryBar(epoch: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceBright.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(VerifiedGreen, CircleShape)
            )
            Text(
                text = "E2EE Ratchet Active (Epoch $epoch)",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = VerifiedGreen
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Mesh Flood Ready",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = TextTertiary
            )
        }
    }
}

/**
 * Composer with live UTF-8 byte counter and send validation (SEC-19).
 */
@Composable
private fun ChatComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    byteCount: Int,
    maxBytes: Int,
    canSend: Boolean,
    isOverLimit: Boolean,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = (byteCount.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)
    val counterColor = if (isOverLimit) DestructiveRose else TextSecondary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceContainer)
            .border(1.dp, HairlineBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // UTF-8 Capacity Meter (SEC-19)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BLE Packet Payload Capacity",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontSize = 10.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$byteCount / $maxBytes B",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isOverLimit) FontWeight.Bold else FontWeight.Normal,
                    color = counterColor,
                    fontSize = 11.sp,
                    modifier = Modifier.semantics {
                        contentDescription = "Payload capacity: $byteCount of $maxBytes bytes used"
                    }
                )

                LinearProgressIndicator(
                    progress = { progress },
                    color = if (isOverLimit) DestructiveRose else StarkPrimary,
                    trackColor = SurfaceBright,
                    modifier = Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = {
                    Text(
                        text = "Type encrypted mesh packet...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                },
                maxLines = 4,
                isError = isOverLimit,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isOverLimit) DestructiveRose else StarkPrimary,
                    unfocusedBorderColor = if (isOverLimit) DestructiveRose else HairlineBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceBright,
                    unfocusedContainerColor = SurfaceBright
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Encrypted message input field" }
            )

            IconButton(
                onClick = onSendClick,
                enabled = canSend,
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (canSend) StarkPrimary else SurfaceBright,
                        shape = CircleShape
                    )
                    .semantics { contentDescription = "Transmit packet over mesh" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (canSend) OnStarkPrimary else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
