# P2Chat

P2Chat is a native Android application (Kotlin, Jetpack Compose) for **serverless, end-to-end encrypted group chat** operating over a **Bluetooth Low Energy (BLE) GATT mesh** with epidemic store-and-forward routing.

Each receiving device runs an **on-device semantic toxicity filter** (quantized TFLite `all-MiniLM-L6-v2` 384-dimensional sentence embeddings + L2-normalized cosine similarity against Jigsaw centroids and user-blocked vectors) evaluated **strictly post-decryption**.

---

## Key Documentation

- [Product Requirements Document (PRD)](docs/PRD.md): Functional and non-functional requirements, security boundaries, and performance benchmarks.
- [Implementation Plan](docs/IMPLEMENTATION_PLAN.md): Architecture, data models, milestones (`M0`–`M4`), and task breakdown (`T0.1`–`T4.14`).
- [Wire Protocol Specification](docs/WIRE_SPEC.md): Byte-level layouts for headers, payload encapsulation, transport chunks, and control frames.
- [Toolchain & Pinned Versions](docs/TOOLCHAIN.md): Pinned versions, Android SDK targets, and verified Gradle commands.
- [Working Rules for Agents](AGENTS.md): Coding guidelines, security invariants, and definition of done.

---

## Lane Assignments & Ownership

| Lane | Owner | Focus Area | Key Deliverables |
|---|---|---|---|
| **Lane 1** | Sibi | Cryptography & Security | P-256 ECDH & ECDSA, AES-256-GCM, KeyVault, Epoch rotation, Rejection suite |
| **Lane 2** | Aditya | BLE Mesh & Transport | GATT Server/Client, Chunker, Bloom filter digest, epidemic flood routing, Fuzz testing |
| **Lane 3** | Krishiv | On-Device AI Filter | WordPiece tokenizer, TextNormalizer, TFLite embedder, Jigsaw centroids, Safety calibration |
| **Lane 4** | Siddharth | UI & Room Storage | Encrypted Room database, Compose Material 3 UI, Onboarding vertical slice, Benchmark engine |

---

## Setup & Environment (Windows PowerShell)

### Prerequisites

1. **OS:** Windows 10/11 (x64)
2. **Shell:** Windows PowerShell (`powershell.exe` or `pwsh`)
3. **JDK:** JDK 17 (LTS) configured on `JAVA_HOME` and `PATH`
4. **Android SDK:** `minSdk = 26`, `compileSdk = 35`
5. **Python:** Python 3.12 via [`uv`](https://github.com/astral-sh/uv)

### Setup Commands

```powershell
# 1. Clone repository
git clone https://github.com/username/P2Chat.git
cd P2Chat

# 2. Sync Python virtual environment and dependencies
uv sync

# 3. Verify wire protocol parity between Kotlin and Python
uv run python scripts/check_wire_parity.py

# 4. Generate deterministic cryptographic test vectors
uv run python scripts/gen_test_vectors.py

# 5. Run JVM unit tests
.\gradlew.bat testDebugUnitTest

# 6. Run Android lint checks
.\gradlew.bat lintDebug

# 7. Assemble debug APK
.\gradlew.bat assembleDebug
```

---

## Pre-Push Verification Gate

Before submitting a pull request or pushing changes, run the automated verification script:

```powershell
.\scripts\verify.ps1
```

The gate enforces five sequential checks:
1. **Wire Parity:** Validates 100% constant and UUID equality between `BleConstants.kt` and `wire_spec.py`.
2. **Test Vector Invariance:** Regenerates crypto vectors and ensures the committed `crypto_vectors.json` is byte-identical.
3. **JVM Unit Tests:** Executes all pure Kotlin/Java unit tests (`testDebugUnitTest`).
4. **Android Lint:** Runs static analysis and security checks (`lintDebug`).
5. **Asset Budget:** Enforces the `< 30 MB` budget for on-device AI assets (`check_asset_size.py`).

---

## Key Gradle Commands

| Task | Command (PowerShell) | Purpose |
|---|---|---|
| **Run All Pre-Push Checks** | `.\scripts\verify.ps1` | Full verification gate |
| **Run JVM Unit Tests** | `.\gradlew.bat testDebugUnitTest` | Fast local tests |
| **Run Android Device Tests** | `.\gradlew.bat connectedDebugAndroidTest` | Instrumented tests on physical Device A |
| **Lint & Static Analysis** | `.\gradlew.bat lintDebug` | Android Lint analysis |
| **Assemble Debug APK** | `.\gradlew.bat assembleDebug` | Build debug APK |
| **Install to Device** | `.\gradlew.bat installDebug` | Install to connected ADB device |
