---
name: Decentralized Tactical Mesh
colors:
  surface: '#0a122a'
  surface-dim: '#0a122a'
  surface-bright: '#313852'
  surface-container-lowest: '#050d25'
  surface-container-low: '#131a33'
  surface-container: '#171e37'
  surface-container-high: '#212942'
  surface-container-highest: '#2c344d'
  on-surface: '#dbe1ff'
  on-surface-variant: '#d8c3ad'
  inverse-surface: '#dbe1ff'
  inverse-on-surface: '#282f49'
  outline: '#a08e7a'
  outline-variant: '#534434'
  surface-tint: '#ffb95f'
  primary: '#ffc174'
  on-primary: '#472a00'
  primary-container: '#f59e0b'
  on-primary-container: '#613b00'
  inverse-primary: '#855300'
  secondary: '#4edea3'
  on-secondary: '#003824'
  secondary-container: '#00a572'
  on-secondary-container: '#00311f'
  tertiary: '#ffbcb7'
  on-tertiary: '#68000a'
  tertiary-container: '#ff938c'
  on-tertiary-container: '#8d0012'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffddb8'
  primary-fixed-dim: '#ffb95f'
  on-primary-fixed: '#2a1700'
  on-primary-fixed-variant: '#653e00'
  secondary-fixed: '#6ffbbe'
  secondary-fixed-dim: '#4edea3'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005236'
  tertiary-fixed: '#ffdad7'
  tertiary-fixed-dim: '#ffb3ad'
  on-tertiary-fixed: '#410004'
  on-tertiary-fixed-variant: '#930013'
  background: '#0a122a'
  on-background: '#dbe1ff'
  surface-variant: '#2c344d'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.04em
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 14px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.75rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
---

## Brand & Style

The design system is engineered for resilient, air-gapped, peer-to-peer mobile communications operating over low-energy physical topologies. Rooted in tactical utility, cryptographic transparency, and operational clarity, the visual style combines Material Design 3 surface models with mission-critical instrumentation aesthetics. 

The aesthetic delivers the calm assurance of high-grade cryptographic software: dense without clutter, dark to conserve OLED power in field operations, and strictly legible across varied ambient lighting conditions. High-contrast state signifiers ensure users can immediately verify cryptographic trust boundaries (TOFU, key epochs, node handshakes) and mesh telemetry without cognitive friction.

Visual principles:
- **Cryptographic Transparency:** Crucial trust markers, verified identity digests, and mesh relays are never decorative; they carry authoritative functional weight.
- **Tactical Realism:** Subdued slate containers organize high-density data, accented by precise amber telemetry glows and emerald validation states.
- **Controlled Glassmorphism:** Constrained frosted-glass surfaces provide non-destructive isolation for quarantined content and flagged message payloads.

## Colors

The palette establishes an authoritative, deep dark-mode baseline optimized for night operations, tactical deployments, and OLED efficiency while avoiding absolute black crushing.

### Core Architecture
- **Canvas Base (`#0B132B`):** Deep tactical navy slate providing the non-emissive backdrop.
- **Surface Level 1 (`#1C2541`):** Base container fill for inactive cards, sheets, and conversation list rows.
- **Surface Level 2 (`#232E4D`):** Container tier for interactive cards, top app bars, and message bubbles (received).
- **Surface Level 3 (`#2A365D`):** Elevated container layer used for modals, active input fields, bottom sheets, and floating dialogs.
- **Structural Stroke (`#334155`):** Clean single-pixel boundary delineating containers and modular panels without visual noise.

### Functional Roles
- **Primary Accent (`#F59E0B`):** Transmit pulses, active BLE relay status, sync queues, routing warnings, and primary navigation states.
- **Cryptographic Verified (`#10B981`):** Cryptographic authenticity, Trust-On-First-Use (TOFU) verification, valid cryptographic signatures, and pristine mesh links.
- **Revocation / Danger (`#EF4444`):** Quarantined frames, key rot/revocation alerts, unverified route drops, and failed integrity checks.

### Typography & Content Tones
- **Primary Text (`#FFFFFF`):** High-priority headings, decrypted message payloads, primary action text.
- **Secondary Text (`#94A3B8`):** Peer labels, timestamps, node identifiers, secondary operational details.
- **Muted Text (`#64748B`):** Disabled UI states, empty track indicators, structural grid guidelines.

## Typography

The type system implements a dual-engine structure: **Inter** handles narrative flow, interpersonal communication, and core UI mechanics, while **JetBrains Mono** governs the telemetry, cryptographic metrics, byte counters, and mesh route data.

- **Inter:** Calibrated with tight tracking across headings for dense tactical readouts. Body scale balances rapid scanning with maximum readability on standard Android hand-held panels.
- **JetBrains Mono:** Enforces absolute tabular consistency across changing hex signatures (`0x8F...4C`), byte counters (`1.4 KB / 2.0 KB`), latency readouts (`42ms`), and epoch sequences. Monospace numbers prevent layout jitter during active BLE peer scanning and packet relay streams.

## Layout & Spacing

Designed strictly around the target 360dp mobile screen standard, layout architecture uses an 8dp (0.5rem) spatial rhythm with 4dp (0.25rem) micro-steps for compact data displays.

### Form-Factor Grid Structure
- **Mobile Handheld (360dp baseline):** 4-column fluid layout with `margin: 1rem` (16dp) on canvas boundaries and `gutter: 0.75rem` (12dp) between internal card columns. Components span 2 or 4 columns.
- **Expanded Tablet Viewport (600dp+):** 8-column layout with split-pane navigation: navigation rail anchored on the left (72dp), conversation feed spanning 3 columns, and active cryptographic session metadata occupying the remaining 5 columns.
- **Compact Handset Vertical Rhythm:** Chat transcript density allows at least 6 standard message bubbles within the primary vertical viewport without scrolling.

### Vertical Stacking Rules
- Compact element groupings (avatar + verification badge) maintain `space-xs` (4dp).
- Input groups, metrics badges, and chip strips maintain `space-sm` (8dp) gaps.
- Discrete modules within an active card structure separate via `space-md` (12dp).
- Major container padding defaults to `space-lg` (16dp).

## Elevation & Depth

Visual hierarchy relies on **Tonal Elevation** complemented by razor-sharp border strokes rather than diffuse, diffuse-drop shadows that wash out dark canvases.

### Surface Elevation Hierarchy
- **Level 0 (Base):** `#0B132B`. The mesh canvas and radar map coordinate plane.
- **Level 1 (Grounded Containers):** `#1C2541` with a `1px` stroke of `#334155`. Used for chat list items, bench metric rows, and static cards.
- **Level 2 (Dynamic Containers & Interactive Items):** `#232E4D` with `1px` stroke of `#334155`. Used for message bubbles, expandable peer sheets, and active filter panels.
- **Level 3 (Overlays, Floating Sheets & Modals):** `#2A365D` with `1px` stroke of `#334155` and an ambient glow: `0px 4px 20px rgba(0, 0, 0, 0.45)`.

### Tactical Accents & Focused Glows
- Focused or active cryptographic components swap their neutral `#334155` border for a hairline `1px` stroke of `#F59E0B` (Primary) or `#10B981` (Verified).
- **Amber Signal Glow:** Active mesh relays and transmitting nodes project a low-radius atmospheric aura: `0px 0px 8px rgba(245, 158, 11, 0.30)`.
- **Verified Integrity Glow:** TOFU verification chips project: `0px 0px 8px rgba(16, 185, 129, 0.35)`.

### Quarantined Payload Diffusion
Messages flagged by the local offline filter apply a hardware-accelerated frosted-glass barrier directly over the text layer: `backdrop-filter: blur(12px); background: rgba(35, 46, 77, 0.75); border: 1px solid rgba(239, 68, 68, 0.4)`. Content is unblurred only upon explicit peer verification tap.

## Shapes

The design system employs a geometric, ergonomic shape paradigm balancing contemporary Material 3 fluid forms with structured technical cards.

- **Primary Cards & Containers:** Defined with `rounded-2xl` (`1rem` / 16dp) curvature to soften dense terminal interfaces and comfortably conform to mobile touch boundaries.
- **Interactive Action Elements:** Inputs, system banners, and segmented panels use standard `rounded-lg` (`0.75rem` / 12dp).
- **Status Pills, Badges & Chips:** Enforce complete `rounded-full` (9999px) capsules. These encapsulate high-density monospace telemetry, ensuring clear distinction from primary message containers.
- **Floating Controls & Indicators:** Tab indicator pills and contextual execution toggles feature fully pill-rounded geometries.

## Components

### Buttons
- **Primary Tactical Action:** Background `#F59E0B`, text `#0B132B` (bold weight), shape `rounded-full`, height 44dp. Pressed state shifts to active opacity `0.9` with an amber signal ring.
- **Secondary Cryptographic Action:** Background `transparent`, border `1px solid #10B981`, text `#10B981`, shape `rounded-full`.
- **Destructive/Quarantine Action:** Background `rgba(239, 68, 68, 0.12)`, border `1px solid #EF4444`, text `#EF4444`, shape `rounded-full`.

### Badges, Status Pills & Chips
- **Mesh Status Pill:** Height 24dp, `rounded-full`, padding 0 8dp. Background `#1C2541`, border `1px solid #334155`. Contains a 6dp active indicator dot (pulsing amber `#F59E0B` or static green `#10B981`) and a JetBrains Mono label (`label-sm`).
- **Cryptographic Trust Badge (TOFU):** Height 20dp, `rounded-full`, background `rgba(16, 185, 129, 0.15)`, border `1px solid #10B981`, text `#10B981`. Displays verified fingerprint slice (e.g., `8F:4C`).

### Cards & Message Surfaces
- **Incoming Message Bubble:** Background `#232E4D`, border `1px solid #334155`, shape `rounded-2xl` with bottom-left corner tightened to `4px`.
- **Outgoing Sent Bubble:** Background `#1C2541`, border `1px solid #F59E0B`, shape `rounded-2xl` with bottom-right corner tightened to `4px`. Monospace sub-label shows delivery route hops (`3 hops • 142ms`).
- **Quarantine Overlay Card:** Background `#232E4D` with `12px` frosted glass blur, warning accent strip in `#EF4444`, centered tap-to-reveal control with reason code (`SUSPICIOUS_PAYLOAD`).

### Input Fields & Controls
- **Tactile Compose Field:** Container background `#2A365D`, border `1px solid #334155`, shape `rounded-2xl`, vertical padding 10dp, horizontal padding 14dp. Placeholder colored in `#64748B`.
- **Monospace Telemetry Counter:** Placed at the right trailing edge of input fields using `JetBrains Mono` at `code-sm` (`11px`), displaying real-time packet capacity (`214B / 512B MTU`) shifting to `#F59E0B` at 85% capacity and `#EF4444` at limit overflow.

### Checkboxes & Toggle Switches
- **Tactical Checkbox:** 18dp × 18dp box with `rounded` (4dp) edges. Border `1px solid #334155`. When checked: fill `#F59E0B`, checkmark `#0B132B`.
- **Radio Selection:** Concentric circles; outer ring `#334155`, active core `#10B981`.

### Bottom Navigation Bar (M3 Standard)
- Container background `#1C2541` pinned to viewport bottom, height 64dp, top border `1px solid #334155`.
- 4 Destinations: **Chats**, **Radar**, **AI Filter**, **Bench**.
- **Active Selection Indicator:** Horizontal pill shape (64dp × 32dp), fill `#F59E0B`, active icon and label tint `#0B132B`.
- **Inactive Destinations:** Icon and label in `#94A3B8`.

### Specialized Domain Components
- **BLE Radar Node Pin:** Circular pulse marker with center core `#F59E0B`, animated stroke ripple `rgba(245, 158, 11, 0.25)`, trailing label in `code-sm` with RSSI strength (`-68 dBm`).
- **Bench Telemetry Streamer:** Dense scrolling console with background `#0B132B`, border `1px solid #334155`, code lines rendered in `JetBrains Mono` (`code-sm`) utilizing `#10B981` for packet acks and `#F59E0B` for route discoveries.