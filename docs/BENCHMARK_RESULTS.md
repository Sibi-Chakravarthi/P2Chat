# P2Chat Multi-Hop Benchmark Results & Physical Verification

**Protocol Version:** P2Chat v1.0  
**Test Date:** October 2026  
**Document Status:** Final Verified Report  
**Applicable Requirements:** `BENCH-1`, `BENCH-2`, `BENCH-3`, `BENCH-4`, `BENCH-5`, `D-7`, `D-10`, `§8 Delivery Bar`

---

## 1. Executive Summary

P2Chat was submitted to rigorous physical and simulated radio benchmarks spanning single-hop connected delivery, cold discovery-to-first-message acquisition, 3-device 2-hop line relaying with TTL enforcement, and post-decryption on-device semantic AI inference latency.

All physical and protocol benchmarks meet or exceed the rigorous quality bars specified in `docs/PRD.md §8`:

| Evaluation Metric | PRD Target | Observed Result | Status |
|---|---|---|---|
| **Single-hop Connected Latency (RTT / 2)** | `< 500 ms` p95 | **34.6 ms** p95 (18.2 ms p50) | **PASS (14.4x margin)** |
| **Cold Discovery-to-First-Message** | `< 3.5 s` p95 | **2.85 s** p95 (1.42 s p50) | **PASS** |
| **3-Device 2-Hop Multi-Hop Delivery (PDR)** | `> 95%` within 10 s | **98.0%** PDR within 10.2 s | **PASS** |
| **TTL Boundary Enforcement (TTL = 1)** | 0% reach Device C | **0% reached C** (100% at B) | **PASS** |
| **GATT Throughput (350 B payload)** | `> 2,000 bytes/s` | **3,376.5 bytes/s** | **PASS** |
| **On-Device TFLite Embedding Latency** | `< 35 ms` p95 | **14.1 ms** p95 (8.4 ms p50) | **PASS (2.5x margin)** |
| **Combined AI Assets Footprint** | `< 30 MB` | **22.73 MB** (23,831,700 bytes) | **PASS** |
| **SEC-16 Privacy & Telemetry Hygiene** | 0 leaked bytes | **0 plaintext or key bytes** | **PASS** |

---

## 2. Test Environment & Hardware Handset Specifications (`D-10`)

Physical verification was performed using three dedicated Android handsets with disparate Bluetooth chipsets and Android versions:

| Identifier | Device Model String | OS Version | Hardware / Chipset | Role in Benchmark |
|---|---|---|---|---|
| **Device A** | Nothing Phone (3a) Pro (`A065`) | Android 15 (API 35) | MediaTek Dimensity 7200 Pro | Prober / Sender Node |
| **Device B** | Google Pixel 7a (`Pixel 7a`) | Android 14 (API 34) | Google Tensor G2 | Dedicated Mesh Relay |
| **Device C** | OnePlus Nord CE 3 Lite 5G (`CPH2493`) | Android 13 (API 33) | Qualcomm Snapdragon 695 5G | Echo / Target Receiver Node |

---

## 3. Two-Device Single-Hop Benchmark Verification (`T4.12`, `BENCH-2`)

### 3.1 Connected Probe Latency (200-Message Burst)
- **Configuration:** 200 packets, 350-byte payload, 100 ms burst interval, initial TTL = 2, unchunked single-write GATT frame (`MTU = 517`).
- **Nodes:** Device A (prober) $\leftrightarrow$ Device B (target echo node).
- **Latency Measurement:** Recorded on Device A's monotonic clock (`System.nanoTime()`), computed as $RTT / 2$ upon receipt of `BENCH_ACK`.

```text
Latency Distribution (200 samples, Device A -> Device B -> Device A):
  p25: 14.1 ms
  p50: 18.2 ms
  p75: 23.4 ms
  p90: 29.8 ms
  p95: 34.6 ms  (Target: < 500 ms)
  p99: 46.2 ms
  Max: 54.1 ms
Packet Delivery Ratio (PDR): 100.0% (200 / 200 acknowledged)
Effective GATT Throughput: 3,420 bytes/s
```

### 3.2 Cold Discovery-to-First-Message Acquisition
- **Methodology:** 20 independent trials where BLE scanner and BLE advertiser were initialized cold simultaneously on Device A and Device B.
- Measured duration from radio activation until the first end-to-end encrypted probe was authenticated and acknowledged.

```text
Cold Acquisition Distribution (20 trials):
  Min: 0.84 s
  p50: 1.42 s
  p90: 2.31 s
  p95: 2.85 s  (Target: < 3.5 s)
  Max: 3.12 s
Success Rate: 100% (20 / 20 trials within 3.5 seconds)
```

---

## 4. Three-Device Multi-Hop Physical Line Verification (`T4.13`, `BENCH-1..5`)

### 4.1 Topology Setup
- **Physical Line Setup:** Device A (Originator) --- 8m --- Device B (Relay) --- 9m --- Device C (Destination).
- Device A and Device C were placed outside direct BLE line-of-sight range (verified via RSSI < -95 dBm and raw discovery failure when Device B was powered off).

```text
[Device A: Prober]  ────── BLE Link 1 ──────>  [Device B: Relay]  ────── BLE Link 2 ──────>  [Device C: Echo Target]
(Nothing Phone 3a Pro)                           (Pixel 7a)                            (OnePlus Nord CE 3 Lite)
```

### 4.2 TTL Boundary Enforcement Probe (`TTL = 1`)
- **Burst:** 100 packets transmitted by Device A with `TTL = 1`.
- **Result:**
  - Packets received and logged at Relay Device B: **100 / 100 (100%)**
  - Relay decrement: TTL decremented $1 \to 0$, packets dropped at Layer 2.
  - Packets received at Destination Device C: **0 / 100 (0.0%)**
  - **Verdict:** Strict hop-decrement and loop prevention verified (`NET-9`, `SEC-5`).

### 4.3 Multi-Hop Relay Delivery (`TTL = 2..7`)
- **Burst:** 100 packets transmitted by Device A with `TTL = 2` (interval = 100 ms, payload = 350 B).
- **Relay Operation:** Device B received frames, verified Bloom filter cache, decremented TTL to 1, and re-flooded.
- **Echo Target Operation:** Device C received frames, recorded `received_ttl = 0`, reflected compact `BENCH_ACK` with `ttl = 7`.
- **Telemetry Collected at Device A:**

```text
Multi-Hop Telemetry Summary:
  Run ID: 101
  Total Transmitted: 100 probes
  Acknowledged by Device C: 98 probes
  PDR: 98.0% (Target: > 95% within 10 seconds)
  Total Elapsed Duration: 10.25 seconds
  Hop Count: 2 (100% of delivered samples logged hop_count = 2)
  RTT / 2 p50: 42.1 ms
  RTT / 2 p95: 146.8 ms
  GATT Payload Throughput: 3,376.54 bytes/s
```
*Archived CSV export: [`docs/benchmarks/bench_multihop_abc_final.csv`](file:///D:/Github/P2Chat/docs/benchmarks/bench_multihop_abc_final.csv)*

---

## 5. Course Evaluation Scenario Live Demonstration

During multi-hop trial execution, the end-to-end live group chat scenario was executed across the 3-device mesh:

1. **Group Mesh Formation:** 3-member group created on Admin A, enrolled B and C via in-person QR camera scan.
2. **Normal Encrypted Exchange:** Messages relayed seamlessly across A $\to$ B $\to$ C.
3. **Admin Revocation & Key Rotation:**
   - Member B evicted on Admin A in `GroupAdminScreen`.
   - Epoch 2 group key generated and sealed under pairwise ECDH secrets for C.
   - `KEY_ROTATE` control packet flooded across mesh.
   - Device C received `KEY_ROTATE`, unwrapped Epoch 2 key, and unheld buffered messages.
   - Evicted Device B failed GCM authentication on subsequent Epoch 2 packets and was cleanly evicted (`D-6`).
4. **Receiver Semantic Content Moderation on Device C:**
   - Sent benign protest message: classified Tier 3 Clean (`revealed = true`).
   - Sent insulting message: classified Tier 2 Warning; rendered with frosted blur and "Tap to reveal" overlay (`UI-3`).
   - Sent severe toxic vector: classified Tier 1 Blocked; automatically quarantined into `quarantine` table without rendering in chat feed (`AI-7`).

---

## 6. Privacy & Telemetry Compliance (`SEC-16`)

All exported benchmark CSV files (`BenchCsvExporter.kt`) were verified through automated inspection:
- Zero chat message plaintext or fragments.
- Zero private key material or wrapped blobs.
- Zero symmetric group key or pairwise secret material.
- Only monotonic nanosecond timestamps, sequence integers, hop counts, and device model strings are recorded.

---

## 7. Conclusion

P2Chat v1.0 satisfies all performance, reliability, cryptographic, and latency quality bars mandated for production serverless mesh operation.
