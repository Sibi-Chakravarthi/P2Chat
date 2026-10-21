# P2Chat

P2Chat is a native Android application (Kotlin, Jetpack Compose) for **serverless, end-to-end encrypted group chat** operating over a decentralized **Bluetooth Low Energy (BLE) GATT mesh** with epidemic store-and-forward routing.

Each receiving device runs an **on-device semantic toxicity filter** (quantized TFLite `all-MiniLM-L6-v2` 384-dimensional sentence embeddings + L2-normalized cosine similarity against Jigsaw centroids and user-blocked vectors) evaluated **strictly post-decryption**. Zero central servers, zero accounts, zero phone numbers, and zero Google Play Services dependencies.

---

## Key Features

- **Decentralized BLE GATT Mesh:** Dual-role GATT server and client (`GattMeshManager`) with automated peripheral advertising (`MeshAdvertiser`) and low-latency central scanning (`AndroidMeshScanner`). Operates autonomously without internet or cellular connectivity.
- **Transport Chunking & Reassembly (`D-17`):** Frames exceeding single-write BLE ATT MTU capacity (e.g. signed rosters and invite credentials up to 1,024 bytes) are framed into sequenced 4-byte transport chunks (`Chunker`, `Reassembler`) and safely reassembled.
- **Zero-Trust Optical Peer Handshake (`ID-4`, `SEC-9`):** High-contrast standalone QR code generator (`QrCodeGenerator`) and CameraX + ZXing optical scanner (`QrDecoder`, `QrScannerScreen`) with row-stride normalization, 90°/180°/270° sensor rotation handling, and dual normal/inverted binarization passes.
- **Instant Group Join & Mutual Verification (`GroupInviteCodec`):** Group admins can generate instant Group Invite QR codes encoding group keys, epoch state, and signed admin keys for 1-step in-person joining, or exchange mutual `PAIRWISE_INVITE` control frames over the BLE mesh.
- **Forward Secrecy & Key Ratchet (`SEC-8`–`SEC-12`):** Symmetric AES-256-GCM message encryption under ephemeral epoch keys. Group admins can rotate keys manually or trigger automatic epoch rotation upon removing members.
- **On-Device AI Content Moderation (`AI-1`–`AI-17`):** Client-side semantic text classification strictly post-decryption on `Dispatchers.Default`. Two-tier safety thresholding:
  - $\tau_{\text{warn}} = 0.68$: Tier 2 warning badge with tap-to-reveal blur.
  - $\tau_{\text{block}} = 0.82$: Tier 1 automatic quarantine into an isolated table.
  - User can customize sensitivity sliders and block specific semantic vectors with "Block Similar".
- **Live Mesh Radar & Benchmark Engine (`BENCH-1`–`BENCH-5`):** Real-time node discovery with live RSSI, MTU, connection state, and buffer occupancy telemetry, plus multi-hop latency and packet delivery ratio (PDR) probe runner with CSV export.

---

## Interactive Screens

The application includes four main tabs and a complete cryptographic flow:

1. **Chats Tab (`GroupListScreen` $\to$ `ChatScreen` $\to$ `GroupAdminScreen`):**
   - Active mesh groups with epoch badges and unread counters.
   - Message composer with live UTF-8 byte counter ($X$ / 350 B) and signature verification badges.
   - Administrative roster management, key rotation controls, and Group Invite QR sharing.
2. **Radar Tab (`RadarScreen`):**
   - Live Store-and-Forward packet buffer occupancy ($X$ / 1,000 packets).
   - Real-time nearby BLE peer list with live RSSI (dBm), negotiated MTU, and connection states (`CONNECTED`, `CONNECTING`, `QUEUED`).
   - One-tap runtime Bluetooth permission and service controls.
3. **AI Filter Tab (`AiFilterScreen`):**
   - Interactive $\tau_{\text{warn}}$ and $\tau_{\text{block}}$ sensitivity sliders persisted in Room.
   - Examiner Test Bench for real-time text normalization, WordPiece tokenization, inference latency (ms), and nearest prototype distance diagnostics.
   - Quarantine and blocked vector pattern manager with restore and delete actions.
4. **Bench Tab (`BenchScreen`):**
   - Automated multi-hop probe runner (`BenchRunner`) supporting Sender vs Ack Node roles.
   - Live 2x2 telemetry grid ($RTT/2$, $PDR\ \% $, Throughput, TFLite inference ms).
   - Sanitized CSV benchmark exporter (`BenchCsvExporter`).
5. **Identity & QR Screen (`OnboardingScreen` in Profile Mode):**
   - Accessible anytime via the top-bar QR icon in Chats, Radar, or Filter tabs.
   - Displays user's 8-byte hex fingerprint (`sender_fp`), editable alias, crisp QR card, and CameraX peer/invite scanner.

---

## Key Documentation

- [Product Requirements Document (PRD)](docs/PRD.md): Functional and non-functional requirements, security boundaries, and performance benchmarks.
- [Implementation Plan](docs/IMPLEMENTATION_PLAN.md): Architecture decisions, Room data models, milestones (`M0`–`M4`), and task breakdown (`T0.1`–`T4.14`).
- [Wire Protocol Specification](docs/WIRE_SPEC.md): Byte-level layouts for headers, payload encapsulation, transport chunks, and control frames.
- [AI Asset Specification & Latency](docs/AI_ASSETS.md): Quantized MiniLM-L6-v2 TFLite model, tensor specifications, and memory footprint.
- [AI Calibration Report](docs/CALIBRATION_REPORT.md): Jigsaw calibration, protest-safety false positive analysis, and threshold settings.
- [Benchmark Results & Physical Verification](docs/BENCHMARK_RESULTS.md): Multi-hop latency, PDR delivery, and physical device verification.
- [Toolchain & Pinned Versions](docs/TOOLCHAIN.md): Pinned versions, Android SDK targets, and verified Gradle commands.
- [Working Rules for Agents](AGENTS.md): Coding guidelines, security invariants, and definition of done.

---

## Quick Start: Connecting Two Phones

### Method 1: Instant 1-Step Group Invite (Fastest)
1. **Device A (Admin):** Create a group in the Chats tab $\to$ open the chat $\to$ tap **Admin** (top right) $\to$ tap **"Invite QR"** (or the QR icon in the top bar).
2. **Device B (Target):** Tap the QR icon in the top right of the Chats tab (or tap **"+"** $\to$ **"Scan Invite QR"**) $\to$ tap **"Scan Peer / Invite QR (CameraX)"**.
3. Point Device B's camera at Device A's screen. Device B immediately decodes the group credentials, pins the admin, and joins the group!

### Method 2: Mutual QR Verification + BLE Mesh Handshake
1. On each phone, tap the QR icon in the Chats top bar to open the Identity Profile.
2. Tap **"Scan Peer / Invite QR"** on each phone to scan the other phone's identity QR code (establishing mutual TOFU verification in `peerDao`).
3. On Device A, open the group $\to$ tap **Admin** $\to$ tap **"Add via QR"** and scan Device B. Device A floods the sealed `PAIRWISE_INVITE` frame over the BLE GATT mesh.

---

## Setup & Environment (Windows PowerShell)

### Prerequisites

1. **OS:** Windows 10/11 (x64)
2. **Shell:** Windows PowerShell (`powershell.exe` or `pwsh`)
3. **JDK:** JDK 17 (LTS) configured on `JAVA_HOME` and `PATH`
4. **Android SDK:** `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`
5. **Python:** Python 3.12 via [`uv`](https://github.com/astral-sh/uv)

### Build & Verification Commands

```powershell
# 1. Clone repository
git clone https://github.com/Sibi-Chakravarthi/P2Chat.git
cd P2Chat

# 2. Sync Python virtual environment
uv sync

# 3. Check wire protocol parity between Kotlin and Python
uv run python scripts/check_wire_parity.py

# 4. Generate deterministic cryptographic test vectors
uv run python scripts/gen_test_vectors.py

# 5. Run JVM unit tests
.\gradlew.bat testDebugUnitTest

# 6. Run Android lint checks
.\gradlew.bat lintDebug

# 7. Assemble debug APK
.\gradlew.bat assembleDebug

# 8. Install debug build onto all connected physical phones
.\gradlew.bat installDebug
```

---

## Pre-Push Verification Gate

Before submitting a pull request or pushing changes, run the automated verification script:

```powershell
.\scripts\verify.ps1
```

The gate enforces five sequential checks:
1. **Wire Parity:** Validates 100% constant and UUID equality between `BleConstants.kt` and `scripts/wire_spec.py`.
2. **Test Vector Invariance:** Regenerates crypto vectors and ensures `crypto_vectors.json` is byte-identical.
3. **JVM Unit Tests:** Executes all pure Kotlin/Java unit tests (`testDebugUnitTest`).
4. **Android Lint:** Runs static analysis and security checks (`lintDebug`).
5. **Asset Budget:** Enforces the `< 30 MB` budget for on-device AI assets (`scripts/check_asset_size.py`).

---

## Key Gradle Commands

| Task | Command (PowerShell) | Purpose |
|---|---|---|
| **Run All Pre-Push Checks** | `.\scripts\verify.ps1` | Full verification gate |
| **Run JVM Unit Tests** | `.\gradlew.bat testDebugUnitTest` | Fast local unit tests |
| **Run Android Device Tests** | `.\gradlew.bat connectedDebugAndroidTest` | Instrumented tests on physical device |
| **Lint & Static Analysis** | `.\gradlew.bat lintDebug` | Android Lint analysis |
| **Assemble Debug APK** | `.\gradlew.bat assembleDebug` | Build debug APK |
| **Install to Connected Devices** | `.\gradlew.bat installDebug` | Install to connected ADB devices |
