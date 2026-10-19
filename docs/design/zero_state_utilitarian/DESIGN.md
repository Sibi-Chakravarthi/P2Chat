---
name: Zero-State Utilitarian
colors:
  surface: '#131315'
  surface-dim: '#131315'
  surface-bright: '#39393b'
  surface-container-lowest: '#0e0e10'
  surface-container-low: '#1c1b1d'
  surface-container: '#201f22'
  surface-container-high: '#2a2a2c'
  surface-container-highest: '#353437'
  on-surface: '#e5e1e4'
  on-surface-variant: '#c4c7c8'
  inverse-surface: '#e5e1e4'
  inverse-on-surface: '#313032'
  outline: '#8e9192'
  outline-variant: '#444748'
  surface-tint: '#c6c6c7'
  primary: '#ffffff'
  on-primary: '#2f3131'
  primary-container: '#e2e2e2'
  on-primary-container: '#636565'
  inverse-primary: '#5d5f5f'
  secondary: '#45dfa4'
  on-secondary: '#003825'
  secondary-container: '#00bd85'
  on-secondary-container: '#00452e'
  tertiary: '#ffffff'
  on-tertiary: '#402d00'
  tertiary-container: '#ffdf9f'
  on-tertiary-container: '#815f00'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e2e2e2'
  primary-fixed-dim: '#c6c6c7'
  on-primary-fixed: '#1a1c1c'
  on-primary-fixed-variant: '#454747'
  secondary-fixed: '#68fcbf'
  secondary-fixed-dim: '#45dfa4'
  on-secondary-fixed: '#002114'
  on-secondary-fixed-variant: '#005137'
  tertiary-fixed: '#ffdf9f'
  tertiary-fixed-dim: '#f9bd22'
  on-tertiary-fixed: '#261a00'
  on-tertiary-fixed-variant: '#5c4300'
  background: '#131315'
  on-background: '#e5e1e4'
  surface-variant: '#353437'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '500'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 30px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '500'
    lineHeight: 26px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 22px
    letterSpacing: -0.005em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.005em
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.01em
  label-mono-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: -0.01em
  label-mono-md:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-mono-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '400'
    lineHeight: 14px
    letterSpacing: 0.04em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-sm: 0.75rem
  gutter-lg: 1.5rem
  margin: 1rem
  margin-sm: 0.75rem
  margin-lg: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
  space-2xl: 2.5rem
---

## Brand & Style

This design system expresses a stripped, deliberate utility built for peer-to-peer trust, cryptographic verification, and calm communication. Drawing architectural restraint from Signal, the raw information density of Bitchat, and the stark physical-digital reduction of Nothing OS, the visual language avoids decorative noise in favor of absolute signal integrity.

The personality is unembellished, confident, and tactfully human. It rejects sci-fi clichés, simulated phosphor glows, and gamified telemetrics. Instead, it elevates metadata, latency stats, and cryptographic fingerprints as honest, beautiful utilities. The interface establishes calm authority through matte surfaces, hairline boundaries, razor-sharp typographic pacing, and purposeful functional color.

## Colors

The palette operates under absolute discipline: deep matte black forms the base canvas, structural zinc defines interactive planes, and white is reserved for focal points.

### Canvas & Surface
- **Canvas (`#09090B`)**: Deep matte black. Serves as the bedrock for the app canvas and persistent toolbars.
- **Surface / Container (`#141417`)**: Subtle dark zinc container layer. Used for grouped listings, input wells, and flat panels. Never stacked beyond one tier.
- **Hairline Border (`#222226`)**: 1px structural boundary. Used across lists, dividers, headers, and explicit state frames.

### Typographic Hierarchy
- **Text Primary (`#F4F4F5`)**: Crisp off-white. Applied to primary headings, conversational text, and core metrics.
- **Text Secondary (`#71717A`)**: Muted neutral grey. Applied to timestamps, routing addresses, subheadings, and secondary labels.
- **Text Tertiary / Disabled (`#52525B`)**: Low-contrast zinc. Applied to placeholder states, inactive icons, and de-emphasized metadata.

### Accents & Semantic States
- **Stark Primary (`#FAFAFA`)**: Reserved for solid primary actions, active segment states, and inverted text on dark fills.
- **Cryptographic / Connected (`#34D399`)**: Muted sage green. Strictly indicates cryptographic identity verification, verified peer handshakes, active mesh nodes, and healthy operational states.
- **Warning (`#FBBF24`)**: Muted amber. Limited to key rotations, unverified signatures, high latency alerts, or degraded relays.
- **Destructive (`#F87171`)**: Muted rose. Limited to disconnect actions, purge commands, block lists, and session termination.

## Typography

The typography couples the human clarity of **Inter** with the structural precision of **JetBrains Mono**.

- **Inter** handles narrative communication, headings, conversation streams, settings menus, and functional navigation. Headings use medium weights (`500`) with modest negative tracking, deliberately rejecting hyper-stylized ultra-bold treatments.
- **JetBrains Mono** is applied strictly to data-dense machine metadata: public key fingerprints, hex IDs, signal strength (`-64 dBm`), packet counts, network epochs, byte streams, and inline status badges. This separation clearly divides human dialogue from system diagnostics.

## Layout & Spacing

Layouts follow an edge-to-edge structure rooted in hairline grid boundaries. Rather than floating insulated islands of content, views rely on flat, planar segments divided by clean 1px borders.

### Rhythm & Density
- Base increments use a strict 4px grid. Standard interactive rows conform to a 48px or 56px vertical rhythm.
- Spacing inside data cells is compact, prioritizing vertical scanability, while outer layout margins remain calm and unhurried.
- Content aligns flush along column guidelines. Avoid asymmetrical negative spaces or scattered decorative offsets.

### Breakpoints & Adaptive Behavior
- **Mobile (< 640px)**: Single-column full-width viewports. Screen canvas sits at `#09090B`. Primary navigation is fixed to the bottom edge. Outer padding rests at `margin-sm` (`12px`) or `margin` (`16px`).
- **Tablet & Desktop (≥ 640px)**: Two-column master-detail layout where master listings (peers, conversations) occupy a fixed 360px panel on the left, separated by a vertical 1px border (`#222226`) from the main communication or bench workspace.

## Elevation & Depth

This system avoids ambient drop shadows, blurred light glows, skeuomorphic bevels, and nested stacked cards. Spatial ordering is expressed purely through **tonal planes and hairline boundaries**.

1. **Canvas (`#09090B`)**: Base canvas level. Carries structural view headers, backdrop regions, and view transitions.
2. **Surface Layer (`#141417`)**: The sole elevated container layer. Used for message bubbles, code blocks, input wells, and flat list backgrounds. Never place a container surface on top of another container surface.
3. **Hairline Outlines (`1px solid #222226`)**: Demarcates the boundary of every functional zone, bottom bar, modal, and row item. It replaces the need for dropped shadows or ambient occlusion.
4. **Modals & Overlays**: Positioned over canvas using an overlay sheet fill of `#09090B` with an enclosing `1px solid #222226` hairline perimeter border. Dim backdrop surfaces use a pure `#000000` alpha fill at 60% opacity without blur filters.

## Shapes

The design uses tight, disciplined geometry with a roundedness level of `1` (Soft):
- Standard interactive elements, inputs, and list containers use `4px` (`0.25rem`) corner rounding.
- Modals and sheet containers use `8px` (`0.5rem`) corner rounding.
- Full-pill treatments (`9999px`) are restricted solely to primary text action buttons, cryptographic state chips, and unread count badges.
- Metric cells, fingerprint data blocks, and terminal streams use square corners (`0px`) or subtle `4px` rounding to maintain their structural character.

## Components

### Buttons
- **Primary Action**: Solid `#FAFAFA` background, `#09090B` text, `4px` or pill shape, `Inter` Medium (`13px`), no border, no shadow. High contrast and immediate.
- **Secondary Action**: `#141417` background, `1px solid #222226` border, `#F4F4F5` text. On hover/press: background transitions to `#1E1E22`.
- **Destructive Action**: `#141417` background, `1px solid #F87171` at 20% opacity, `#F87171` text.
- **Ghost/Tertiary**: Transparent background, `#71717A` text, shifts to `#F4F4F5` on interaction.

### Chips & Badges
- **Cryptographic State Chip**: `#141417` fill with a `1px solid #222226` border. Features an inline `6px` solid `#34D399` circular indicator dot followed by a `11px` `JetBrains Mono` label (e.g., `ED25519 VERIFIED`).
- **Metric Pill**: Monospace label in `#71717A`, metric value in `#F4F4F5`, bounded within a `4px` rounded container with hairline framing.

### Lists & Row Items
- Full-width rows separated by `1px solid #222226` bottom borders.
- Items feature primary title (`#F4F4F5`, Inter 14px), secondary preview (`#71717A`, Inter 13px), and right-aligned monospace meta info (timestamp, bytes, RSSI) in `#52525B` (`11px`, `JetBrains Mono`).
- Strictly flat: no card nesting inside list items.

### Input Fields
- Single-line inputs and terminal prompts use `#141417` background with a crisp `1px solid #222226` border.
- Active/Focus state: border turns into `#71717A` or `#FAFAFA` (hairline width remains strictly 1px, with zero outer glow).
- Inner text uses `#F4F4F5`; placeholder text uses `#52525B`.

### Checkboxes & Radio Controls
- Square (`4px` corner) and circular selectors built with `1px solid #52525B` stroke on `#09090B`.
- Checked state: `#FAFAFA` fill with `#09090B` solid check indicator. No decorative check animations.

### Bottom Navigation Bar (Mobile)
- Fixed at the bottom edge: `#09090B` solid surface with a `1px solid #222226` top border.
- Four flat navigation tabs: **Chats**, **Peers**, **Filter**, and **Bench**.
- Iconography: Minimal, 1.25px thin-stroke monochrome outlines.
- States: Active tab uses `#FAFAFA` icon and text; inactive tabs use `#52525B`. No background indicator pills, no underline animations, and no drop shadows.

### Terminal / Peer Diagnostic Blocks
- Used in the Bench and Peer inspection views.
- Surface `#141417`, hairline `#222226`, text styled entirely in `JetBrains Mono` at `11px` and `12px`.
- Hash signatures, signal bars (rendered as simple text units like `[-68 dBm]`), and transfer speeds displayed cleanly without skeuomorphic gauges.