# AGENTS.md

Working rules for **P2Chat**, for every coding agent (Antigravity, Claude Code, Codex, Cursor, Copilot) and every human contributor.

## What we're building
P2Chat is a native Android application (Kotlin, Jetpack Compose) for serverless, end-to-end encrypted group chat over a Bluetooth Low Energy (BLE) GATT mesh with epidemic store-and-forward routing[cite: 21]. Every receiving device runs an on-device semantic toxicity filter (quantized TFLite `all-MiniLM-L6-v2` sentence embeddings + L2-normalized cosine similarity against Jigsaw centroids and user-blocked vectors) strictly post-decryption[cite: 21].

Sources of truth, in this order:
1. The user's instructions in the current conversation.
2. `docs/PRD.md`: requirements (`ID-*`, `SEC-*`, `NET-*`, `AI-*`, `UI-*`, `BENCH-*`), roles, permissions, and quality bars.
3. `docs/IMPLEMENTATION_PLAN.md`: architecture, decisions, Room data model, milestones (`M0`–`M4`), and tasks (`T0.1`–`T4.14`)[cite: 15, 20].
4. `docs/WIRE_SPEC.md`, `docs/TOOLCHAIN.md`, and `docs/AI_ASSETS.md` (created in `T0.1`, `T0.2`, `T3.2`)[cite: 15, 20].
5. This file.

Before starting a task, read its entry in `docs/IMPLEMENTATION_PLAN.md` and the `docs/PRD.md` sections it references[cite: 15]. Reference requirement IDs (`SEC-6`) and task IDs (`T1.6`) in every commit and PR[cite: 15].

## Objectives, in priority order
1. **Cryptographic & Protocol Integrity.** Never weaken signatures, AES-256-GCM AAD, key wrapping, or the receiver-only filtering boundary (`SEC-1..19`, `AI-1`)[cite: 15, 21].
2. **Crash & Frame Resilience.** Never let a malformed BLE frame, truncated chunk, or failed decryption crash the app (`NET-12`, `SEC-6`)[cite: 21].
3. **Correctness.** Meet every requirement in the task, including empty, loading, error, and permission-denied states (`ID-6`, `UI-6`, `AI-13`)[cite: 15, 21].
4. **Speed.** TFLite + scoring `< 35 ms` p95; 1,000-vector dot-product scan `< 2 ms`; single-hop BLE `< 500 ms` p95 connected; 3-device 2-hop PDR `> 95%` within 10 s (`§8` Quality Bar)[cite: 15, 21].
5. **Simplicity.** Make the smallest change that satisfies the task's `Do` and `Done when` checks without touching files outside `Touches`[cite: 15, 20].

## Environment
- **OS:** Windows (x64)[cite: 15, 20].
- **Default shell:** PowerShell (`pwsh` / `powershell.exe`)[cite: 15, 20].
- **Rule:** Always use Windows PowerShell syntax and `.\gradlew.bat`. Never run bash-only commands (`rm -rf`, `export VAR=...`, `/dev/null`, heredocs `<<'EOF'`)[cite: 20]. Prefer `.\scripts\verify.ps1` over ad-hoc command chains[cite: 15, 20].

## Stack
| Layer | Choice |
|---|---|
| Android / UI | Kotlin, `minSdk = 26`, `targetSdk` (`TODO: confirm in T0.2`), Jetpack Compose, Material 3 (dark theme only)[cite: 20, 21] |
| BLE | Android `BluetoothGattServer`, `BluetoothLeAdvertiser`, `BluetoothLeScanner`, `BluetoothGatt` client[cite: 21] |
| Cryptography | JCA / BouncyCastle (`secp256r1` P-256 ECDH & ECDSA, AES-256-GCM, HKDF-SHA256) + Android Keystore (`p2chat_identity_wrap_key`, `p2chat_room_master_key`)[cite: 20, 21] |
| Local Storage | Room (SQLite) with `LocalAtRestCipher` `TypeConverter`[cite: 20, 21] |
| On-Device AI | TFLite Interpreter (`all-MiniLM-L6-v2` quantized 384-d), Kotlin `TextNormalizer` & `WordPieceTokenizer`[cite: 20, 21] |
| QR & Camera | `com.google.zxing:core` + AndroidX CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) — zero Google Play Services[cite: 20, 21] |
| Offline Tooling | Python 3.11–3.12 via `uv`, `pycryptodome`, `tensorflow-cpu` (`tf.lite.Interpreter`), `scikit-learn`, Google Bumble[cite: 11, 20, 21] |

Use only these libraries[cite: 15]. Ask before swapping any of them or adding any new dependency, state library, DI framework, or UI kit[cite: 15, 20].

## Repo layout
(`<pkg>` and exact existing paths are `TODO: confirm in T0.1 / T0.2`; folders marked `(new in M*)` do not exist yet)[cite: 15, 20]:

```text
.
├── AGENTS.md                              # Working rules for Antigravity and all agents
├── CLAUDE.md                              # Entry pointer for Claude Code
├── README.md                              # Setup and workflow guide (updated in T0.7)
├── docs/
│   ├── PRD.md                             # Product requirements (source of truth)
│   ├── IMPLEMENTATION_PLAN.md             # Decisions, data model, M0–M4 tasks
│   ├── WIRE_SPEC.md                       # Byte-level frame tables (new in T0.1)
│   ├── TOOLCHAIN.md                       # Pinned versions and Gradle tasks (new in T0.2)
│   ├── AI_ASSETS.md                       # TFLite tensor spec & latency report (new in T3.2)
│   ├── CALIBRATION_REPORT.md              # Jigsaw & protest-safety metrics (new in T3.5)
│   ├── MESH_TEST_NOTES.md                 # 2- and 3-device BLE logs (new in T2.12)
│   └── BENCHMARK_RESULTS.md               # Course evaluation results (new in T4.12)
├── scripts/
│   ├── verify.ps1                         # Pre-push verification gate (new in T0.6)
│   ├── wire_spec.py                       # Python wire constants (synced in T0.1)
│   ├── check_wire_parity.py               # Kotlin <-> Python constant check (new in T0.1)
│   ├── check_asset_size.py                # Enforces < 30 MB AI asset budget (new in T0.6)
│   ├── gen_test_vectors.py                # Deterministic crypto vectors (new in T0.3)
│   ├── verify_kotlin_vectors.py           # Verifies Kotlin crypto outputs (new in T1.7)
│   ├── prepare_model.py                   # Quantizes MiniLM-L6-v2 to .tflite (new in T3.2)
│   ├── ai_reference.py                    # Reference normalizer & WordPiece (new in T3.3)
│   ├── gen_tokenizer_fixtures.py          # 100+ tokenizer parity fixtures (new in T3.3)
│   ├── build_toxic_vectors.py             # Builds toxic_vectors.bin + sidecar (new in T3.4)
│   └── calibrate_filter.py                # Precision/Recall/F1 + safety alerts (new in T3.5)
└── app/
    └── src/
        ├── main/
        │   ├── assets/                    # .tflite, vocab.txt, toxic_vectors.bin, meta JSON (M3)
        │   └── java/<pkg>/
        │       ├── wire/                  # BleConstants.kt, WireCodec, PacketHeader (M0)
        │       ├── data/                  # AppDatabase, DAOs, Entities, LocalAtRestCipher (M1/M3/M4)
        │       ├── crypto/                # KeyVault, primitives, EnvelopeVerifier, rotation (M1)
        │       ├── ble/                   # Chunker, BloomDigest, PacketRouter, GATT, Service (M2)
        │       ├── ai/                    # TextNormalizer, WordPieceTokenizer, TfliteEmbedder (M3)
        │       ├── pipeline/              # ReceivePipeline, SendPipeline (new in M4)
        │       ├── bench/                 # BenchRunner, BenchCsvExporter (new in M4)
        │       ├── ui/                    # Theme, nav, Onboarding, Radar, Chat, Admin, AI, Bench
        │       └── util/                  # SecureLog, Clock (M1)
        ├── debug/java/<pkg>/ble/          # MeshDebugScreen, DebugPeerAllowlist (new in M2)
        ├── test/java/<pkg>/               # Pure JVM JUnit tests + JSON fixtures
        └── androidTest/java/<pkg>/        # Physical-device tests (Keystore, Room, BLE, TFLite)
```

### Module pattern
| Part | Where |
|---|---|
| Room Entity & DAO | `app/src/main/java/<pkg>/data/<feature>/`[cite: 20] |
| Domain / Service logic | `app/src/main/java/<pkg>/<layer>/` (`crypto`, `ble`, `ai`, `pipeline`, `bench`)[cite: 20] |
| ViewModel & Compose UI | `app/src/main/java/<pkg>/ui/<screen>/`[cite: 20] |
| JVM Unit Tests | `app/src/test/java/<pkg>/<layer>/`[cite: 20] |
| Device Instrumentation Tests | `app/src/androidTest/java/<pkg>/<layer>/`[cite: 20] |

Task `T1.14` (`OnboardingScreen` + `OnboardingViewModel` + `IdentityManager` + `LocalIdentityDao`) is the reference vertical slice: copy its pattern for every later screen[cite: 15, 20].

## Commands
Don't guess commands that aren't listed here[cite: 15]. Exact Gradle task names are confirmed in `T0.2` and recorded in `docs/TOOLCHAIN.md`[cite: 20].

| Task | Command (Windows PowerShell) |
|---|---|
| Sync Python env | `uv sync` (`TODO: confirm after T0.3`)[cite: 15, 20] |
| Check wire parity | `uv run python scripts/check_wire_parity.py` (after `T0.1`)[cite: 20] |
| Generate crypto vectors | `uv run python scripts/gen_test_vectors.py` (after `T0.3`)[cite: 20] |
| Build debug APK | `.\gradlew.bat assembleDebug` (`TODO: confirm in T0.2`)[cite: 20] |
| Install on Device A | `.\gradlew.bat installDebug` (`TODO: confirm in T0.2`)[cite: 20] |
| Run JVM unit tests | `.\gradlew.bat testDebugUnitTest` (`TODO: confirm in T0.2`)[cite: 20] |
| Run device tests (Device A connected) | `.\gradlew.bat connectedDebugAndroidTest` (`TODO: confirm in T0.2`)[cite: 20] |
| Lint and typecheck | `.\gradlew.bat lintDebug` (`TODO: confirm in T0.2`)[cite: 15, 20] |
| **All checks (pre-push verify gate)** | `.\scripts\verify.ps1` (after `T0.6`)[cite: 15, 20] |

## Task workflow
1. Read the task in `docs/IMPLEMENTATION_PLAN.md` and the `docs/PRD.md` requirements it covers[cite: 15].
2. Write a short plan listing the exact files to touch and tests to add[cite: 15]. Wait for user approval before editing files if the task is non-trivial[cite: 15].
3. Build data/schema first, then pure logic and tests, then UI[cite: 15, 20].
4. Stay strictly inside the task's `Touches` list and respect `Not in this task`[cite: 15, 20]. Any file outside `Touches` requires an explicit reason[cite: 15, 20].
5. Run `.\scripts\verify.ps1` (or `.\gradlew.bat testDebugUnitTest` before `T0.6`)[cite: 15, 20]. Report each `Done when` item as **PASS** or **FAIL**[cite: 15].
6. If a requirement or constant is ambiguous or missing, stop and ask — never invent wire constants or UUIDs[cite: 15, 20].

## Do
- **Scope and process:**
  - Keep `BleConstants.kt` and `scripts/wire_spec.py` 100% synchronized via `scripts/check_wire_parity.py`[cite: 20].
  - Inject the `Clock` interface wherever timestamps, TTL expiry, or the 10-minute epoch grace window are evaluated[cite: 20].
- **Security and data:**
  - Verify the 64-byte P-256 ECDSA signature **before** attempting AES-256-GCM decryption (`SEC-6`)[cite: 21].
  - Keep `ttl (1B)` outside both the AES-256-GCM AAD and the ECDSA signed bytes so relays can decrement it (`SEC-5`)[cite: 21].
  - Evict `msg_id` from `seen_messages` and `packet_buffer` when a group member detects a signature or GCM authentication failure (`D-6`)[cite: 20, 21].
  - Wrap all stored private keys and group keys under `p2chat_identity_wrap_key` and encrypt stored message/quarantine bodies via `LocalAtRestCipher` (`p2chat_room_master_key`)[cite: 20, 21].
  - Route all logging through `SecureLog` (`SEC-16`)[cite: 20, 21].
- **AI filter & BLE performance:**
  - Run TFLite inference and dot-product scoring strictly on `Dispatchers.Default` after decryption (`AI-1`), guarding the single `Interpreter` instance with a mutex/lock (`T3.8`)[cite: 20, 21].
  - Serialize all BLE GATT reads/writes per connection through `WriteQueue` (`T2.8`) and hand callbacks off the Android Binder thread immediately[cite: 20].
- **Frontend (Jetpack Compose):**
  - Provide `EmptyState`, `LoadingState`, and `ErrorState` composables on every screen, support 360 dp width in portrait and landscape, and attach `contentDescription` to all primary controls (`UI-6`, `UI-7`)[cite: 20, 21].

## Don't
- **Never log sensitive data:** Never pass plaintext messages, ciphertexts, nonces, shared secrets, or key byte arrays to `android.util.Log`, `println`, crash reports, or benchmark CSV exports (`SEC-16`)[cite: 20, 21].
- **Never invent wire constants:** Never hardcode guessed UUIDs, HKDF info strings, or header offsets outside `BleConstants.kt` and `scripts/wire_spec.py` (`D-1`)[cite: 20, 21].
- **Never filter before decryption or block relaying:** Never run Layer 3 AI filtering on the sender side or allow a local filter verdict to stop Layer 2 from relaying a packet (`NET-10`, `AI-1`)[cite: 21].
- **Never silently fail open on AI asset errors:** If `.tflite`, `vocab.txt`, or `toxic_vectors.bin` fails to load, never mark messages Clean — always return `NOT_SCANNED` with the blurred warning overlay (`AI-13`)[cite: 20, 21].
- **Never overwrite a pinned peer key:** If a known `sender_fp` presents a changed signing or ECDH public key, reject it and surface a warning (`ID-5`, `T1.2`)[cite: 20, 21].
- **Never commit large datasets or use Robolectric:** Never commit the raw Jigsaw CSV to git (`Decision 23`), and never add Robolectric for Keystore/Room/BLE tests — run `androidTest` on physical Device A (`Decision 14`)[cite: 20].
- **Never use bash-only syntax or destructive git commands:** Do not run Linux-only shell commands on Windows PowerShell, and never run `git reset --hard` or `git push --force`[cite: 15, 20].

## Git and pull requests
- **Branch naming:** `lane<1..4>/<task-id>-<short-slug>` (e.g., `lane1/T1.6-envelope-seal-open`)[cite: 15, 20].
- **Commit format:** `<TASK-ID> (<REQ-IDs>): <concise summary>` (e.g., `T1.6 (SEC-3, SEC-5, SEC-6): implement CHAT envelope seal and open`)[cite: 15].
- **PR granularity:** One task = one PR[cite: 15, 20]. Update `docs/IMPLEMENTATION_PLAN.md` and `docs/PRD.md` in the same PR if any decision changes[cite: 15, 20].
- **Commit policy:** Only commit or push when explicitly asked by the user[cite: 15].

## Testing bar
| Layer | Required |
|---|---|
| Wire codec, crypto primitives, epoch state machine, Bloom digest, packet router, normalizer, WordPiece tokenizer | Pure JVM JUnit tests in `app/src/test/` + cross-language parity against Python JSON fixtures (`SEC-17`, `AI-3`, `T2.3`)[cite: 20, 21] |
| Malformed BLE input & rate limiting | 10,000-frame deterministic fuzz test with 0 uncaught exceptions (`NET-12`, `T2.11`)[cite: 20, 21] |
| Security invariants | 40+ case parameterized rejection suite at 100% pass rate (`SEC-18`, `T1.12`)[cite: 20, 21] |
| Room DAOs, `LocalAtRestCipher`, Android Keystore, TFLite interpreter, Compose screens | Instrumented `androidTest` on physical **Device A** (Nothing Phone (3a) Pro)[cite: 20, 21] |
| Multi-hop BLE delivery & latency | Physical 2-device (`T4.12`) and 3-device A–B–C (`T4.13`) benchmark runs with exported CSV[cite: 20, 21] |

## Definition of done
- [ ] Meets all `Done when` checks in the task and satisfies the cited `docs/PRD.md` requirement IDs[cite: 15, 20].
- [ ] Respects `Touches` and `Not in this task` boundaries[cite: 15, 20].
- [ ] Tests cover positive paths, negative/tampered inputs, permissions, and state transitions[cite: 15, 20].
- [ ] Empty, loading, and error states are handled; UI works at 360 dp width and with TalkBack/keyboard focus (`UI-6`, `UI-7`)[cite: 15, 21].
- [ ] `.\scripts\verify.ps1` passes in Windows PowerShell with zero errors[cite: 15, 20].

## Known gotchas
- **Android Keystore ECDH on `minSdk = 26`:** Native Keystore `PURPOSE_AGREE_KEY` requires API 31+. Always route ECDH through `EcProvider` (`T1.4`) so software P-256 (`secp256r1`) with Keystore AES-256-GCM wrapped private keys runs on API 26–30 and matches Python `pycryptodome` test vectors[cite: 20, 21].
- **ECDSA signature format:** Java/BouncyCastle `SHA256withECDSA` defaults to ASN.1 DER encoding (70–72 bytes), whereas the P2Chat wire format requires raw 64-byte `(r || s)` (`SEC-6`). Always convert DER $\leftrightarrow$ raw 64-byte `(r, s)` in `Ecdsa.kt` (`T1.5`)[cite: 20, 21].
- **BLE GATT concurrency:** Android's `BluetoothGatt` silently drops reads/writes if a previous callback hasn't completed. Always enqueue operations through `WriteQueue` (`T2.8`)[cite: 20].
- **TFLite thread safety:** `org.tensorflow.lite.Interpreter` is not thread-safe. Always acquire the mutex inside `TfliteEmbedder` (`T3.8`) before calling `run()`[cite: 20].
- **Normalizer symbol boundaries:** Only map `@`→`a`, `1`/`!`→`i`, `$`→`s`, `0`→`o` when bounded by letters on both sides, and collapse character repeats to `2` (not `1`), so `12:00`, `1 km`, `stop!`, and `good` are never corrupted (`AI-2`, `T3.6`)[cite: 20, 21].