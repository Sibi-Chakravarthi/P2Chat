package com.p2chat.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOnboardingComplete: () -> Unit,
    isProfileMode: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val alias by viewModel.alias.collectAsState()
    val aliasError by viewModel.aliasError.collectAsState()
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerScreen(
            onQrScanned = { rawQr ->
                viewModel.processScannedQr(rawQr)
                showScanner = false
            },
            onClose = { showScanner = false }
        )
        return
    }

    when (val state = uiState) {
        is OnboardingUiState.Loading -> {
            LoadingState(
                message = "Generating secure P-256 identity keypair...",
                modifier = modifier
            )
        }
        is OnboardingUiState.Error -> {
            ErrorState(
                title = "Identity Generation Failed",
                errorMessage = state.message,
                retryLabel = "Retry",
                onRetry = { viewModel.generateIdentity() },
                modifier = modifier
            )
        }
        is OnboardingUiState.Idle -> {
            OnboardingContent(
                alias = alias,
                aliasError = aliasError,
                isCreated = false,
                createdState = null,
                isProfileMode = isProfileMode,
                onBackClick = onBackClick,
                onAliasChange = { viewModel.setAlias(it) },
                onUpdateAlias = { viewModel.updateAlias(it) },
                onRegenerateAlias = { viewModel.generateRandomAlias() },
                onGenerateIdentity = { viewModel.generateIdentity() },
                onScanPeerClick = { showScanner = true },
                onContinueClick = { onBackClick?.invoke() ?: onOnboardingComplete() },
                modifier = modifier
            )
        }
        is OnboardingUiState.Created -> {
            OnboardingContent(
                alias = alias,
                aliasError = aliasError,
                isCreated = true,
                createdState = state,
                isProfileMode = isProfileMode,
                onBackClick = onBackClick,
                onAliasChange = { viewModel.setAlias(it) },
                onUpdateAlias = { viewModel.updateAlias(it) },
                onRegenerateAlias = { viewModel.generateRandomAlias() },
                onGenerateIdentity = { viewModel.generateIdentity() },
                onScanPeerClick = { showScanner = true },
                onContinueClick = { onBackClick?.invoke() ?: onOnboardingComplete() },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun OnboardingContent(
    alias: String,
    aliasError: String?,
    isCreated: Boolean,
    createdState: OnboardingUiState.Created?,
    isProfileMode: Boolean,
    onBackClick: (() -> Unit)?,
    onAliasChange: (String) -> Unit,
    onUpdateAlias: (String) -> Unit,
    onRegenerateAlias: () -> Unit,
    onGenerateIdentity: () -> Unit,
    onScanPeerClick: () -> Unit,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isProfileMode && onBackClick != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.semantics { contentDescription = "Back to chats" }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = StarkPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Identity & QR",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Step & Enclave Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STEP 1 OF 1 • PROVISIONING",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningAmber,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceBright)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(VerifiedGreen)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "OFFLINE SECURE ENCLAVE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = VerifiedGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Title and description
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (isProfileMode) "Tactical Profile" else "Identity Generation",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isProfileMode)
                    "Hardware security keys for decentralized offline mesh discovery and peer verification."
                else
                    "Initialize physical hardware security keys for offline mesh discovery and mutual verification.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Callsign / Alias Input Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceContainer)
                .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Alias / Call sign",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                if (aliasError == null && alias.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = VerifiedGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Valid handle",
                            style = MaterialTheme.typography.labelSmall,
                            color = VerifiedGreen
                        )
                    }
                } else if (aliasError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = DestructiveRose,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = aliasError,
                            style = MaterialTheme.typography.labelSmall,
                            color = DestructiveRose
                        )
                    }
                }
            }

            OutlinedTextField(
                value = alias,
                onValueChange = onAliasChange,
                enabled = !isCreated || isProfileMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Alias call sign input" },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Tag,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (!isCreated) {
                        IconButton(
                            onClick = onRegenerateAlias,
                            modifier = Modifier.semantics { contentDescription = "Regenerate random alias" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cached,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }
                },
                placeholder = { Text("Enter mesh alias...", color = TextTertiary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WarningAmber,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    disabledTextColor = TextSecondary
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Visible to localized BLE discovery",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                Text(
                    text = "${alias.length} / 32 chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alias.length > 32 || alias.isEmpty()) DestructiveRose else WarningAmber,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (!isCreated) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onGenerateIdentity,
                    enabled = aliasError == null && alias.isNotEmpty() && alias.length <= 32,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Generate Identity" },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StarkPrimary,
                        contentColor = OnStarkPrimary
                    )
                ) {
                    Text(
                        text = "Initialize Keypair",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnStarkPrimary
                    )
                }
            } else if (isProfileMode && createdState != null && alias != createdState.identity.alias) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { onUpdateAlias(alias) },
                    enabled = aliasError == null && alias.isNotEmpty() && alias.length <= 32,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .semantics { contentDescription = "Save updated alias" },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StarkPrimary,
                        contentColor = OnStarkPrimary
                    )
                ) {
                    Text(
                        text = "Save Updated Alias",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnStarkPrimary
                    )
                }
            }
        }

        // If identity is created, display Fingerprint card, QR card, and action controls
        if (isCreated && createdState != null) {
            // Cryptographic Fingerprint Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainer)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
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
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "ECDSA & ECDH Identity Key",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceBright)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = VerifiedGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "StrongBox Enclave",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = VerifiedGreen
                        )
                    }
                }

                // Hex Digest with Tactical Copier
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CanvasBackground)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PUBLIC KEY FINGERPRINT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = createdState.formattedFingerprint,
                            style = MaterialTheme.typography.titleMedium,
                            color = VerifiedGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("P2Chat Fingerprint", createdState.formattedFingerprint)
                            clipboard.setPrimaryClip(clip)
                            copied = true
                            coroutineScope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceBright,
                            contentColor = StarkPrimary
                        ),
                        modifier = Modifier.semantics { contentDescription = "Copy key fingerprint" }
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (copied) VerifiedGreen else WarningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = StarkPrimary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Curve secp256r1",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = "Epoch: 0x01",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Peer Pairing Optical Sync (QR Card)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainer)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
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
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Mutual Optical Handshake",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "TOFU READY",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Rendered QR Bitmap Container
                val qrBitmap = remember(createdState.qrBase64) {
                    QrCodeGenerator.generate(createdState.qrBase64, sizePx = 400)
                }

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Cryptographic Peer Identity QR",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Tactical fallback reticle
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, VerifiedGreen, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "QR CODE",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Text(
                    text = "Scan in-person to establish zero-trust TOFU (Trust On First Use) mesh route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // Scanned Peer Feedback Banner (if any)
            if (createdState.keyConflictWarning != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DestructiveRose.copy(alpha = 0.15f))
                        .border(1.dp, DestructiveRose, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = createdState.keyConflictWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = DestructiveRose,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (createdState.scannedPeer != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VerifiedGreen.copy(alpha = 0.15f))
                        .border(1.dp, VerifiedGreen, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = VerifiedGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TOFU Verified: ${createdState.scannedPeer.alias}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VerifiedGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tactical Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onScanPeerClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Scan Peer QR with CameraX" },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StarkPrimary,
                        contentColor = OnStarkPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = OnStarkPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Scan Peer / Invite QR (CameraX)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnStarkPrimary
                    )
                }

                Button(
                    onClick = onContinueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = if (isProfileMode) "Done" else "Continue to Mesh" },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceBright,
                        contentColor = TextPrimary
                    )
                ) {
                    Text(
                        text = if (isProfileMode) "Done" else "Continue to Mesh",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = TextPrimary
                    )
                }
            }

            // Microcopy footer
            Text(
                text = "No servers. No phone numbers. Pure decentralized BLE advertising & scanning.",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
