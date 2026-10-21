package com.p2chat.ui.theme

import androidx.compose.ui.graphics.Color

// Zero-State Utilitarian Dark Palette (docs/design/zero_state_utilitarian/DESIGN.md)
val CanvasBackground = Color(0xFF09090B)       // Deep matte black canvas
val SurfaceContainer = Color(0xFF141417)       // Elevated dark zinc container
val HairlineBorder   = Color(0xFF222226)       // 1px structural hairline border
val SurfaceBright    = Color(0xFF27272A)

// Typography & Content Tones
val TextPrimary      = Color(0xFFF4F4F5)       // Crisp off-white
val TextSecondary    = Color(0xFF71717A)       // Muted neutral grey
val TextTertiary     = Color(0xFF52525B)       // Low-contrast disabled zinc
val NavUnselected    = Color(0xFFA1A1AA)       // Increased contrast for unselected tabs

// Accents & Semantic States
val StarkPrimary     = Color(0xFFFAFAFA)       // High-contrast primary action fill
val OnStarkPrimary   = Color(0xFF09090B)       // Text on primary button
val VerifiedGreen    = Color(0xFF34D399)       // Cryptographic TOFU verified / active node
val WarningAmber     = Color(0xFFFBBF24)       // Key rotation / unverified signature / lag
val DestructiveRose  = Color(0xFFF87171)       // Revocation / drop / quarantine error
