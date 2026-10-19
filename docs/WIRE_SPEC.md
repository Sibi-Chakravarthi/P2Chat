# P2Chat Wire Protocol Specification

| Document | Wire Protocol Specification v1.0 |
|---|---|
| Status | Frozen in `T0.1` (`M0`) |
| References | [`PRD.md`](PRD.md), [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md), [`../AGENTS.md`](../AGENTS.md) |
| Source Files | [`app/src/main/java/com/p2chat/wire/BleConstants.kt`](../app/src/main/java/com/p2chat/wire/BleConstants.kt), [`scripts/wire_spec.py`](../scripts/wire_spec.py) |
| Verification | `uv run python scripts/check_wire_parity.py` |

---

## 1. Overview & Architectural Boundaries

P2Chat operates over a Bluetooth Low Energy (BLE) GATT topology using epidemic store-and-forward mesh routing (`Layer 2`) and end-to-end cryptographic encapsulation (`Layer 1`).

- **GATT Service UUID:** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **GATT Characteristic UUID:** `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` (Read, Write Without Response, Notify)
- **Endianness:** All multi-byte integers on the wire are **Big-Endian** (`Network Byte Order`).
- **Cryptographic Signature Order:** Every group frame carries a raw 64-byte P-256 ECDSA signature over `(AAD || CoreBlob)`. Receivers verify the signature **strictly before** invoking AES-256-GCM decryption (`SEC-6`).
- **TTL Decrement Boundary:** `ttl (1B)` is located at offset 1 of the fixed header, but is **explicitly excluded** from both the AES-256-GCM Authenticated Additional Data (AAD) and the ECDSA signature so intermediate relay nodes can decrement `ttl` without invalidating cryptographic integrity (`SEC-5`).

---

## 2. Packet Types

One byte at header offset 0 (`packet_type`):

| Code | Identifier | Role / Description | Encryption Key |
|---|---|---|---|
| `0x01` | `PACKET_TYPE_CHAT` | Broadcast group message payload | Active Group Key (`p2chat_room_master_key` epoch) |
| `0x02` | `PACKET_TYPE_KEY_ROTATE` | Flooded group key rotation notification | Pairwise Secret with each recipient |
| `0x03` | `PACKET_TYPE_ROSTER_SYNC` | Membership roster update broadcast | Pairwise Secret with recipient (or Group Key) |
| `0x04` | `PACKET_TYPE_PAIRWISE_INVITE` | Direct peer invite & credential delivery | Direct Pairwise Secret |
| `0x05` | `PACKET_TYPE_BENCH_DATA` | Network benchmark diagnostic probe | Group Key / Reserved `BENCH_GROUP_ID` |
| `0x06` | `PACKET_TYPE_BENCH_ACK` | Benchmark acknowledgment & latency echo | Group Key / Reserved `BENCH_GROUP_ID` |

---

## 3. Transport Chunking Header (`D-17`)

When MTU negotiation yields an effective payload capacity below the complete frame size (or when a frame exceeds single-write capacity, up to 1,024 bytes), frames are split into sequential characteristic chunks preceded by a 4-byte transport header:

| Offset | Field | Type | Description |
|---|---|---|---|
| `0` | `kind` | `uint8` | `0x00` = Single unchunked frame, `0x01` = Fragmented chunk |
| `1` | `frame_id` | `uint8` | Cyclic rolling sequence ID (0–255) to bind related chunks |
| `2` | `chunk_index` | `uint8` | Zero-based fragment index (`0` .. `chunk_count - 1`) |
| `3` | `chunk_count` | `uint8` | Total number of chunks in this reassembly frame |

---

## 4. Fixed Wire Header (24 Bytes)

Every Layer 2/Layer 1 frame begins with this fixed 24-byte header:

| Offset | Field | Type | Encrypted? | Included in AAD? | Included in Sig? | Description |
|---|---|---|---|---|---|---|
| `0` | `packet_type` | `uint8` | No | **Yes (Byte 0)** | **Yes (Byte 0)** | Packet type identifier (`0x01`–`0x06`) |
| `1` | `ttl` | `uint8` | No | **No** | **No** | Time-To-Live (Hop count, default 7, max 15) |
| `2..9` | `msg_id` | `uint64 BE` | No | **Yes (Bytes 1..8)** | **Yes (Bytes 1..8)** | Random unique message ID for deduplication |
| `10..13` | `group_id` | `uint32 BE` | No | **Yes (Bytes 9..12)** | **Yes (Bytes 9..12)** | Target group identifier (`0x42454E43` for Bench) |
| `14..21` | `sender_fp` | `uint64 BE` | No | **Yes (Bytes 13..20)**| **Yes (Bytes 13..20)**| Sender fingerprint (first 8B of SHA-256 of pubkey) |
| `22..23` | `key_epoch` | `uint16 BE` | No | **Yes (Bytes 21..22)**| **Yes (Bytes 21..22)**| Active key epoch number (starts at 1) |

### GCM Authenticated Additional Data (AAD) — Exactly 23 Bytes

$$\text{AAD} = \text{packet\_type (1B)} \parallel \text{msg\_id (8B)} \parallel \text{group\_id (4B)} \parallel \text{sender\_fp (8B)} \parallel \text{key\_epoch (2B)}$$

---

## 5. Core Cryptographic Blob

Appears immediately following the 24-byte header (at frame offset 24):

| Offset in Blob | Field | Size | Description |
|---|---|---|---|
| `0..11` | `nonce` | 12 bytes | Fresh random initialization vector for AES-256-GCM |
| `12..27` | `tag` | 16 bytes | AES-256-GCM authentication tag |
| `28..31` | `ciphertext_len` | 4 bytes (`uint32 BE`) | Length $L$ in bytes of the following ciphertext |
| `32 .. 31+L` | `ciphertext` | $L$ bytes | AES-256-GCM encrypted payload |

### Inner Plaintext Format (Decrypted Plaintext)
Inside `ciphertext`, the decrypted bytes have the following structure:
- `sent_at`: 8 bytes (`int64 BE`, timestamp in milliseconds UTC since Unix Epoch)
- `payload`: Variable bytes (up to 350 bytes UTF-8 text for `CHAT`, or serialized protobuf/JSON/binary for control packets)

$$\text{Inner Payload Max Size} = 8\text{ B (sent\_at)} + 350\text{ B (text)} = 358\text{ bytes}$$

---

## 6. Digital Signature (Raw 64 Bytes)

Appears immediately following the Core Cryptographic Blob (at the tail of the frame):
- **Algorithm:** P-256 (`secp256r1`) ECDSA with SHA-256.
- **Wire Format:** Raw 64-byte `(r || s)`, where `r` is 32 bytes big-endian and `s` is 32 bytes big-endian. (ASN.1 DER encoding is **never** transmitted on the wire; DER is converted to/from raw 64 bytes).
- **Signed Payload:** $\text{SignedData} = \text{AAD (23 bytes)} \parallel \text{Core Cryptographic Blob (32 + } L \text{ bytes)}$.

---

## 7. Worked Example: Maximum-Length CHAT Frame (478 Bytes)

A standard maximum-length chat message (350 UTF-8 bytes) produces an exact 478-byte frame:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. Fixed Header (24 Bytes)                                                 │
│    [0x00]      packet_type (0x01)                                 :   1 Byte │
│    [0x01]      ttl (0x07)                                         :   1 Byte │
│    [0x02..09]  msg_id (e.g. 0x0123456789ABCDEF)                  :   8 Bytes│
│    [0x0A..0D]  group_id (e.g. 0x00000001)                         :   4 Bytes│
│    [0x0E..15]  sender_fp (e.g. 0xA1B2C3D4E5F60718)                :   8 Bytes│
│    [0x16..17]  key_epoch (0x0001)                                 :   2 Bytes│
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. Core Cryptographic Blob (390 Bytes)                                      │
│    [0x18..23]  GCM Nonce                                          :  12 Bytes│
│    [0x24..33]  GCM Tag                                            :  16 Bytes│
│    [0x34..37]  Ciphertext Length L = 358 (0x00000166)             :   4 Bytes│
│    [0x38..19D] Ciphertext (Encrypted sent_at 8B + 350B text)      : 358 Bytes│
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. ECDSA Signature (64 Bytes)                                               │
│    [0x19E..1BD] Signature r                                       :  32 Bytes│
│    [0x1BE..1DD] Signature s                                       :  32 Bytes│
└─────────────────────────────────────────────────────────────────────────────┘
Total Wire Frame Size = 24 + 12 + 16 + 4 + 358 + 64 = 478 Bytes.
```

- When MTU is 517, the effective GATT characteristic write limit is 512 bytes.
- Therefore, the complete 478-byte CHAT frame fits in **one single unfragmented BLE write** without chunking overhead!

---

## 8. Summary of Protocol Constants

| Name | Type | Value | Description |
|---|---|---|---|
| `SERVICE_UUID` | `String` | `"6E400001-B5A3-F393-E0A9-E50E24DCCA9E"` | GATT Service UUID |
| `CHARACTERISTIC_UUID` | `String` | `"6E400002-B5A3-F393-E0A9-E50E24DCCA9E"` | GATT Characteristic UUID |
| `PACKET_TYPE_CHAT` | `Byte` | `0x01` | Group chat message |
| `PACKET_TYPE_KEY_ROTATE` | `Byte` | `0x02` | Key rotation flood |
| `PACKET_TYPE_ROSTER_SYNC` | `Byte` | `0x03` | Roster membership synchronization |
| `PACKET_TYPE_PAIRWISE_INVITE`| `Byte` | `0x04` | Direct pairwise invitation |
| `PACKET_TYPE_BENCH_DATA` | `Byte` | `0x05` | Benchmark latency probe |
| `PACKET_TYPE_BENCH_ACK` | `Byte` | `0x06` | Benchmark latency acknowledgment |
| `HEADER_SIZE_BYTES` | `Int` | `24` | Fixed wire header size |
| `AAD_SIZE_BYTES` | `Int` | `23` | Authenticated Additional Data size |
| `GCM_NONCE_SIZE_BYTES`| `Int` | `12` | GCM initialization vector size |
| `GCM_TAG_SIZE_BYTES` | `Int` | `16` | GCM authentication tag size |
| `CIPHERTEXT_LEN_SIZE_BYTES`| `Int` | `4` | 32-bit big-endian length prefix |
| `ECDSA_SIGNATURE_SIZE_BYTES`| `Int` | `64` | Raw (r \|\| s) P-256 ECDSA signature |
| `HKDF_PAIRWISE_SALT` | `String` | `"P2Chat-v1-Pairwise-Salt"` | Pairwise HKDF-SHA256 salt |
| `HKDF_PAIRWISE_INFO` | `String` | `"P2Chat-v1-Pairwise-AES256GCM"` | Pairwise HKDF-SHA256 info |
| `MAX_PLAINTEXT_BYTES`| `Int` | `350` | Maximum composer UTF-8 characters/bytes |
| `MAX_INNER_PAYLOAD_BYTES`| `Int` | `358` | Inner payload including `sent_at` |
| `CHAT_FRAME_SIZE_BYTES` | `Int` | `478` | Total CHAT frame size |
| `MAX_FRAME_SIZE_BYTES` | `Int` | `1024` | Protocol hard cap on reassembled frame |
| `BLOOM_FILTER_SIZE_BYTES`| `Int` | `1200` | Bloom filter digest size (9,600 bits) |
| `BLOOM_FILTER_K_HASHES`| `Int` | `7` | Number of hash functions for digest |
| `DEFAULT_TTL` | `Int` | `7` | Default hop count |
| `MAX_TTL` | `Int` | `15` | Maximum hop count limit |
| `BENCH_GROUP_ID` | `Long` | `0x42454E43` | Reserved group ID for benchmark suite |
