# P2Chat Android Toolchain & Gradle Commands

| Field | Value |
|---|---|
| Package Name (`<pkg>`) | `com.p2chat` |
| Module Name | `app` |
| Min SDK | `26` (Android 8.0 Oreo) |
| Target / Compile SDK | `35` (Android 15) |
| Java Version | JDK 17 (`17.0.20.1`, LTS) |
| Gradle Version | `8.10.2` |
| Android Gradle Plugin (AGP) | `8.7.1` |
| Kotlin Version | `2.0.21` |
| Kotlin JVM Target | `17` |

---

## 1. Pinned Dependencies (`gradle/libs.versions.toml`)

| Layer / Purpose | Library | Pinned Version | Notes |
|---|---|---|---|
| UI & Theme | Jetpack Compose BOM | `2024.10.00` | Material 3 dark theme (`zero_state_utilitarian`) |
| Activity | `androidx.activity:activity-compose` | `1.9.3` | Compose integration |
| Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` | `2.8.6` | Coroutine flows & viewmodels |
| Cryptography | `org.bouncycastle:bcprov-jdk18on` | `1.78.1` | P-256 ECDH, ECDSA raw (r \|\| s), AES-GCM |
| Storage | `androidx.room:room-runtime`, `room-ktx` | `2.6.1` | Encrypted SQLite storage with KSP compiler |
| On-Device AI | `org.tensorflow:tensorflow-lite` | `2.16.1` | Quantized `all-MiniLM-L6-v2` interpreter |
| QR Code | `com.google.zxing:core` | `3.5.3` | Air-gapped QR invite generation/decode |
| Camera | `androidx.camera:camera-camera2`, `camera-view`| `1.3.4` | Zero Google Play Services dependency |
| Coroutines | `kotlinx-coroutines-test` | `1.8.1` | Deterministic async unit testing |
| Unit Testing | `junit:junit` | `4.13.2` | Pure JVM unit tests |

---

## 2. Pinned Python Toolchain (`pyproject.toml`)

- **Package Manager:** `uv`
- **Python Version:** `3.12.*` (CPython on Windows x64)
- **Key Python Packages:**
  - `pycryptodome >= 3.24.0` (Reference vectors & AAD / signature parity)
  - `bleak >= 3.0.2` (Hardware BLE inspection & testing)
  - `bumble >= 0.0.235` (Virtual BLE emulation over TCP)
  - `tensorflow-cpu` (Offline MiniLM quantization and centroid clustering)

---

## 3. Verified Gradle Commands (Windows PowerShell)

All commands are executed from the repository root `D:\Github\P2Chat`:

| Task | Command | Purpose |
|---|---|---|
| **Pre-push Verify Gate** | `.\scripts\verify.ps1` | Runs wire parity, vectors, unit tests, lint, and asset checks |
| **Check Wire Parity** | `uv run python scripts/check_wire_parity.py` | Validates Kotlin ↔ Python constant equality |
| **Generate Crypto Vectors** | `uv run python scripts/gen_test_vectors.py` | Generates deterministic crypto test vectors |
| **Run JVM Unit Tests** | `.\gradlew.bat testDebugUnitTest` | Runs all pure JVM JUnit tests |
| **Run Android Device Tests**| `.\gradlew.bat connectedDebugAndroidTest` | Runs instrumented tests on physical Device A |
| **Build Debug APK** | `.\gradlew.bat assembleDebug` | Compiles `app-debug.apk` |
| **Lint & Static Analysis** | `.\gradlew.bat lintDebug` | Enforces lint, security rules, and SDK checks |
| **Install on Device A** | `.\gradlew.bat installDebug` | Installs debug build to connected ADB device |
