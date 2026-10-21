package com.p2chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.SecurityUpdateWarning
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MessageBubble rendering cryptographic and semantic tier details (T4.5, UI-3, AI-7, AI-12, AI-13).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessageUiModel,
    onRevealClick: (msgId: Long) -> Unit,
    onBlockSimilarClick: (msgId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }

    val isBlurred = !message.revealed && (message.tier == 2 || message.tier == 0)
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(message.sentAtMs) { timeFormatter.format(Date(message.sentAtMs)) }

    val alignment = if (message.isSelf) Alignment.End else Alignment.Start
    val bubbleShape = if (message.isSelf) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        // 1. Sender Header (Alias, TOFU badge, Epoch, Time)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            if (!message.isSelf) {
                Text(
                    text = message.senderAlias,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // TOFU Badge (ID-5)
                if (message.tofuVerified) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .background(VerifiedGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .semantics { contentDescription = "TOFU Key Pinning Verified" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = VerifiedGreen,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "Verified",
                            color = VerifiedGreen,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .background(WarningAmber.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .semantics { contentDescription = "TOFU Key Pinning Unverified" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.GppMaybe,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "Unverified",
                            color = WarningAmber,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Text(
                    text = "Self (Operator)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = WarningAmber
                )
            }

            // Epoch Tag
            Text(
                text = "e${message.keyEpoch}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextTertiary
            )

            // Timestamp
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = TextTertiary
            )
        }

        // 2. Message Bubble Surface
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 320.dp)
                .clip(bubbleShape)
                .background(if (message.isSelf) SurfaceBright else SurfaceContainer)
                .border(1.dp, HairlineBorder, bubbleShape)
                .combinedClickable(
                    onClick = {
                        if (isBlurred) {
                            onRevealClick(message.msgId)
                        }
                    },
                    onLongClick = {
                        if (!isBlurred) {
                            showContextMenu = true
                        }
                    }
                )
                .padding(12.dp)
                .semantics {
                    contentDescription = if (isBlurred) {
                        "Blurred message: tap to reveal"
                    } else {
                        "Message from ${message.senderAlias}: ${message.text}"
                    }
                }
        ) {
            if (isBlurred) {
                // Tier 2 or NOT_SCANNED Blurred Presentation
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Blurred underlying text preview
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier
                            .blur(8.dp)
                            .padding(bottom = 8.dp)
                    )

                    // Scrim overlay with tap-to-reveal button
                    val overlayText = if (message.tier == 2) {
                        "Flagged by on-device AI (s = ${"%.2f".format(message.score)}) — Tap to reveal"
                    } else {
                        "Not scanned (AI filter unavailable) — Tap to reveal"
                    }

                    Button(
                        onClick = { onRevealClick(message.msgId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (message.tier == 2) WarningAmber else SurfaceBright,
                            contentColor = if (message.tier == 2) OnStarkPrimary else TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = overlayText }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (message.tier == 2) Icons.Default.Warning else Icons.Default.LockClock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = overlayText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = if (message.tier == 2) OnStarkPrimary else TextPrimary
                            )
                        }
                    }
                }
            } else {
                // Clearly rendered plaintext (Tier 3 or Revealed)
                Column {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Footer with signature verification indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (message.sigOk) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Signature verified",
                                    tint = VerifiedGreen,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "ECDSA ok",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = VerifiedGreen
                                )
                            }
                        }

                        if (message.isSelf) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Delivered to local buffer",
                                    tint = VerifiedGreen,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Delivered",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }
            }

            // Long-press Context Menu for moderation (AI-9, AI-10)
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                modifier = Modifier
                    .background(SurfaceContainer)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SecurityUpdateWarning,
                                contentDescription = null,
                                tint = DestructiveRose,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Block Similar Messages",
                                color = DestructiveRose,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    onClick = {
                        showContextMenu = false
                        onBlockSimilarClick(message.msgId)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Block Similar Messages"
                    }
                )
            }
        }
    }
}
