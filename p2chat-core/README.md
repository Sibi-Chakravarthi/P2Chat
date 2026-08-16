# P2Chat-Core · BLE Proof-of-Concept

> Laptop-to-laptop demo of the full P2Chat pipeline: **BLE broadcast → AES-256-GCM encryption → BLE scan & read → decryption → on-device AI toxicity filter**.

---

## Architecture

```
┌─────────────────────────────────┐         BLE          ┌──────────────────────────────────────┐
│         SENDER (Bumble)         │ ◄──── GATT Read ──── │         RECEIVER (Bleak)             │
│                                 │                      │                                      │
│  plaintext                      │                      │  encrypted blob                      │
│      │                          │                      │      │                               │
│      ▼                          │                      │      ▼                               │
│  AES-256-GCM encrypt            │                      │  AES-256-GCM decrypt                 │
│      │                          │                      │      │                               │
│      ▼                          │                      │      ▼                               │
│  GATT Characteristic (read)     │                      │  TFLite Toxicity Filter              │
│  Service: 6E400001-...          │                      │      │                               │
│  Char:    6E400002-...          │                      │      ▼                               │
│                                 │                      │  { is_safe, toxicity_score, label }   │
└─────────────────────────────────┘                      └──────────────────────────────────────┘
```

## Wire Format (AES-256-GCM payload)

| Field            | Size     | Description                  |
|------------------|----------|------------------------------|
| `nonce`          | 12 bytes | Random GCM nonce             |
| `tag`            | 16 bytes | Authentication tag           |
| `ciphertext_len` | 4 bytes  | Little-endian `uint32`       |
| `ciphertext`     | N bytes  | Encrypted UTF-8 message      |

## UUIDs (shared with Android `BleConstants.kt`)

| Constant              | UUID                                     |
|-----------------------|------------------------------------------|
| P2Chat Service        | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`   |
| Message Characteristic| `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`   |

---

## Quick Start

### 1. Install dependencies

```powershell
cd d:\Github\P2Chat\p2chat-core
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

### 2. Run the sender (Terminal 1)

```powershell
python sender.py
# Default: virtual TCP transport on port 17321
# Custom message:
python sender.py --message "Hello from P2Chat demo!"
# With a real USB BLE dongle:
python sender.py --transport usb:0
```

### 3. Run the receiver (Terminal 2)

```powershell
python receiver.py
# Adjust scan timeout:
python receiver.py --scan-seconds 20
# Point to a TFLite model directory:
python receiver.py --model-dir ./models
```

### 4. Expected output (receiver)

```
12:00:05  [RECEIVER]  INFO  🎯 Found P2Chat node: P2Chat-Node  [AA:BB:CC:DD:EE:FF]
12:00:06  [RECEIVER]  INFO  Connected ✔  (MTU negotiated)
12:00:06  [RECEIVER]  INFO  Read encrypted payload: 96 bytes
12:00:06  [RECEIVER]  INFO  Decrypted message: Hey from P2Chat! ...
12:00:06  [RECEIVER]  INFO  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
12:00:06  [RECEIVER]  INFO    📨  Decrypted Message : Hey from P2Chat! ...
12:00:06  [RECEIVER]  INFO    🛡️  AI Filter Label   : Clean (Scaffold)
12:00:06  [RECEIVER]  INFO    📊  Toxicity Score    : 0.0400
12:00:06  [RECEIVER]  INFO    ✅  Safe              : True
12:00:06  [RECEIVER]  INFO    🎯  Confidence        : 0.9500
12:00:06  [RECEIVER]  INFO  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## AI Filter Modes

| Mode             | When                                      | Behaviour                                        |
|------------------|-------------------------------------------|--------------------------------------------------|
| **TFLite**       | `mobilebert_quantized.tflite` is present  | Real MobileBERT inference (same as Android app)  |
| **Scaffold**     | No model file found                       | Keyword heuristic fallback (matches Android)     |

---

## Notes for Presentation

- Both scripts use the **exact same Service & Characteristic UUIDs** as the Android app's `BleConstants.kt`.
- The encryption key is hardcoded (symmetric) for the PoC; in production, P2Chat would derive session keys via ECDH.
- The toxicity filter mirrors `MobileBertClassifier.kt` logic 1:1 — same tokenizer stub, same keyword list, same threshold.
- Bumble supports virtual transports (`tcp-server` / `tcp-client`) for demos without physical BLE hardware.
